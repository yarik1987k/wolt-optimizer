package dev.yk.offercollector.service

import dev.yk.offercollector.App
import dev.yk.offercollector.BuildConfig
import dev.yk.offercollector.core.CandidateFields
import dev.yk.offercollector.core.LocationGrid
import dev.yk.offercollector.core.PickupDemand
import dev.yk.offercollector.data.PickupPlaceEntity
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Business (pickup) addresses: a consent gate, then a local cache geocoded in the background.
 *
 * Gate (spec 1): until the courier confirms on a REAL card that the stored line is the restaurant's
 * address, nothing is stored — the latest candidate is kept in memory only and shown for one
 * yes/no. Rejected → never stored.
 */
object PickupAddresses {
    const val UNVERIFIED = "UNVERIFIED"
    const val CONFIRMED = "CONFIRMED"
    const val REJECTED = "REJECTED"

    data class Candidate(val name: String, val address: String, val atMs: Long)

    private val _candidate = MutableStateFlow<Candidate?>(null)
    /** Memory only: the latest card's (name, address) while the gate is UNVERIFIED. */
    val candidate: StateFlow<Candidate?> = _candidate

    /** Pure gate: what is stored, and (when unverified) the memory-only candidate to show. */
    fun gate(mode: String, f: CandidateFields, atMs: Long): Pair<CandidateFields, Candidate?> {
        val address = f.pickup_address ?: return f to null
        val name = f.pickup_names.singleOrNull() ?: return f.copy(pickup_address = null) to null
        return when (mode) {
            CONFIRMED -> f to null
            REJECTED -> f.copy(pickup_address = null) to null
            else -> f.copy(pickup_address = null) to Candidate(name, address, atMs)
        }
    }

    /** Called on the capture path; returns the fields to store. Never blocks (geocoding is elsewhere). */
    fun filter(app: App, f: CandidateFields, atMs: Long): CandidateFields {
        val mode = app.settings.settings.value.pickupAddressMode
        val (stored, cand) = gate(mode, f, atMs)
        cand?.let { _candidate.value = it }
        if (mode == CONFIRMED && stored.pickup_address != null) {
            val name = stored.pickup_names.single()
            app.appScope.launch { register(app, name, stored.pickup_address, atMs) }
        }
        return stored
    }

    suspend fun confirm(app: App) {
        app.settings.setPickupAddressMode(CONFIRMED)
        _candidate.value?.let { register(app, it.name, it.address, it.atMs) }
        _candidate.value = null
    }

    suspend fun reject(app: App) {
        app.settings.setPickupAddressMode(REJECTED)
        _candidate.value = null
    }

    fun key(name: String, address: String) = "$name|$address"

    private suspend fun register(app: App, name: String, address: String, atMs: Long) {
        if (app.db.pickupPlaces().insertIfAbsent(PickupPlaceEntity(key(name, address), name, address, atMs, "PENDING")) != -1L) {
            geocodePending(app)
        }
    }

    // ---- geocoding (background, once per address, cached) ----

    private val geoMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private const val RETRY_AFTER_MS = 24 * 60 * 60_000L
    /** Nominatim usage policy: at most one request per second, identifying User-Agent, no bulk. */
    private const val SPACING_MS = 1_200L

    /** Bumped when [geocodeQuery] changes; NOT_FOUND results of older queries are retried once. */
    const val QUERY_VERSION = "q3"
    /** A business must be reachable: a match farther than this from the courier when the offer appeared is wrong. */
    const val MAX_KM_FROM_COURIER = 30.0

    /**
     * Street + city only. Wolt appends its delivery-area name ("Rishon Lezion & Hashfela area") and a
     * postal code; with them Nominatim finds nothing (real shift 29.10.2026: 9/9 NOT_FOUND).
     */
    fun geocodeQuery(address: String): String = address.replace(Regex("""ראשל["״]צ"""), "ראשון לציון").split(',')
        .map { it.replace(Regex("""\b\d{7}\b"""), "").trim() }
        .filter { it.isNotEmpty() && !Regex("""\barea\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        .joinToString(", ")

    /** The ONLY thing sent to the geocoder: the business address string. Exposed for tests. */
    fun queryUrl(address: String): String =
        "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=3&countrycodes=il&q=" + URLEncoder.encode(geocodeQuery(address), "UTF-8")

    data class Hit(val lat: Double, val lon: Double)

    /** OK when every returned match lies in the same ~500 m spot; several far-apart matches → AMBIGUOUS. */
    fun decide(hits: List<Hit>): Triple<String, LocationGrid.Cell?, String?> {
        if (hits.isEmpty()) return Triple("NOT_FOUND", null, null)
        val cells = hits.map { LocationGrid.cellOf(it.lat, it.lon) }
        return if (PickupDemand.sameSpot(cells.map { it.id }.toSet())) Triple("OK", cells.first(), null)
        else Triple("AMBIGUOUS", null, "${cells.map { it.id }.toSet().size} מקומות שונים")
    }

    /**
     * Same street names exist in many cities (real: "אריק איינשטיין 1 בת ים" matched a street ~100 km
     * north). A match far from where the courier was when the card appeared is rejected.
     */
    fun nearCourier(decision: Triple<String, LocationGrid.Cell?, String?>, courierLat: Double?, courierLon: Double?): Triple<String, LocationGrid.Cell?, String?> {
        val cell = decision.second ?: return decision
        if (courierLat == null || courierLon == null) return decision
        val km = kmBetween(cell.centerLat, cell.centerLon, courierLat, courierLon)
        return if (km <= MAX_KM_FROM_COURIER) decision else Triple("FAR_FROM_COURIER", null, "${km.toInt()} ק״מ מהשליח")
    }

    private fun kmBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    fun parse(body: String): List<Hit> = (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e.jsonObject
        val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
        if (lat != null && lon != null) Hit(lat, lon) else null
    }

    fun geocodePending(app: App) {
        app.appScope.launch {
            geoMutex.withLock {
                for (p in app.db.pickupPlaces().toGeocode(System.currentTimeMillis() - RETRY_AFTER_MS, QUERY_VERSION)) {
                    val now = System.currentTimeMillis()
                    val result = runCatching { withContext(Dispatchers.IO) { fetch(p.address) } }
                    result.onSuccess { hits ->
                        val courier = app.db.locationSamples().nearest(p.firstSeenUtc, p.firstSeenUtc - 15 * 60_000, p.firstSeenUtc + 15 * 60_000)
                        val (status, cell, note) = nearCourier(decide(hits), courier?.cellCenterLat, courier?.cellCenterLon)
                        app.db.pickupPlaces().setResult(p.key, status, cell?.id, cell?.centerLat, cell?.centerLon, now, note ?: QUERY_VERSION)
                    }.onFailure { e ->
                        app.db.pickupPlaces().setResult(p.key, "ERROR", null, null, null, now, e.javaClass.simpleName)
                    }
                    delay(SPACING_MS)
                }
            }
        }
    }

    private fun fetch(address: String): List<Hit> {
        val c = URL(queryUrl(address)).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "WoltOfferCollector/${BuildConfig.VERSION_NAME} (personal courier tool; business addresses only)")
            c.setRequestProperty("Accept-Language", "he,en")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return parse(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }
}
