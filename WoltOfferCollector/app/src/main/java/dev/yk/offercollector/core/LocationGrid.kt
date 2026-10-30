package dev.yk.offercollector.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ~500 m grid ("g500"). Only the cell id and the cell centre leave this file — never the exact fix.
 * Rows are 500 m of latitude; each row's longitude step is scaled by cos(row-centre latitude),
 * so cells stay ~500 × 500 m. Spec: LOCATION_EXPORT_FORMAT.md.
 */
object LocationGrid {
    const val SCHEME = "g500"
    const val CELL_SIZE_M = 500
    const val LAT_STEP_DEG = CELL_SIZE_M / 111_320.0

    data class Cell(val id: String, val centerLat: Double, val centerLon: Double)

    fun cellOf(lat: Double, lon: Double): Cell {
        val y = floor(lat / LAT_STEP_DEG).toLong()
        val latC = (y + 0.5) * LAT_STEP_DEG
        val lonStep = LAT_STEP_DEG / cos(Math.toRadians(latC))
        val x = floor(lon / lonStep).toLong()
        return Cell("$SCHEME:$y:$x", round5(latC), round5((x + 0.5) * lonStep))
    }

    /** Corner coordinates of a cell, [lon, lat] × 5 (closed ring), for drawing it on a map. */
    fun polygon(cellId: String): List<Pair<Double, Double>>? {
        val parts = cellId.split(":")
        if (parts.size != 3 || parts[0] != SCHEME) return null
        val y = parts[1].toLongOrNull() ?: return null
        val x = parts[2].toLongOrNull() ?: return null
        val lat0 = y * LAT_STEP_DEG
        val lat1 = lat0 + LAT_STEP_DEG
        val lonStep = LAT_STEP_DEG / cos(Math.toRadians((y + 0.5) * LAT_STEP_DEG))
        val lon0 = x * lonStep
        val lon1 = lon0 + lonStep
        return listOf(lon0 to lat0, lon1 to lat0, lon1 to lat1, lon0 to lat1, lon0 to lat0)
    }

    private fun round5(v: Double) = Math.round(v * 100_000) / 100_000.0
}

/** A raw fix as Android reports it. Lives only in memory; never stored. */
data class LocationFix(
    val lat: Double, val lon: Double, val accuracyM: Float?, val fixTimeMs: Long, val isMock: Boolean,
    val speedMps: Float? = null, val provider: String = "fused",
)

/** What is stored for one sample. status = UNKNOWN whenever the fix can't be trusted at cell level. */
data class LocationReading(
    val status: String, // OK | UNKNOWN
    val unknownReason: String?, // NO_PERMISSION | NO_FIX | STALE_FIX | LOW_ACCURACY | MOCK_LOCATION | TIMEOUT | ERROR
    val cell: LocationGrid.Cell?,
    val accuracyM: Int?,
    val fixAgeS: Int?,
    val provider: String?,
    val ageMs: Long? = null,
    val speedMps: Float? = null,
    /** Exact fix, kept ONLY for the optional debug mode; never exported. */
    val exact: LocationFix? = null,
)

object LocationQuality {
    const val MAX_FIX_AGE_MS = 120_000L
    const val MAX_ACCURACY_M = 150f

    fun unknown(reason: String) = LocationReading("UNKNOWN", reason, null, null, null, null)

    /** [atMs] = the moment the sample stands for (event time or now). Age = |atMs − fix time|. */
    fun evaluate(fix: LocationFix?, atMs: Long, maxAgeMs: Long = MAX_FIX_AGE_MS): LocationReading {
        if (fix == null) return unknown("NO_FIX")
        val age = abs(atMs - fix.fixTimeMs)
        val provider = fix.provider
        val acc = fix.accuracyM?.roundToInt()
        fun bad(reason: String) = LocationReading("UNKNOWN", reason, null, acc, (age / 1000).toInt(), provider, age, fix.speedMps, fix)
        return when {
            fix.isMock -> bad("MOCK_LOCATION")
            age > maxAgeMs -> bad("STALE_FIX")
            fix.accuracyM == null || fix.accuracyM > MAX_ACCURACY_M -> bad("LOW_ACCURACY")
            else -> LocationReading("OK", null, LocationGrid.cellOf(fix.lat, fix.lon), acc, (age / 1000).toInt(), provider, age, fix.speedMps, fix)
        }
    }
}

/**
 * When to take a sample (pure, unit-tested). Phase 3 (spec §5–6):
 * - only inside a session the courier started;
 * - EVENT samples near: offer, accept, pickup, drop-off, completion, available (home screen), offline;
 * - PERIODIC samples every [baseIntervalMs] during the whole session (cells only — no exact track),
 *   stretched to [stationaryIntervalMs] while the device is stationary;
 * - a missing fix is stored as UNKNOWN (NO_FIX), never filled with the previous position;
 * - the same trigger is not repeated within [DEDUP_MS].
 */
object LocationSamplingPolicy {
    const val DEDUP_MS = 30_000L
    const val DEFAULT_BASE_INTERVAL_S = 45
    const val DEFAULT_STATIONARY_INTERVAL_S = 120
    val BASE_INTERVAL_CHOICES_S = listOf(30, 45, 60)
    const val MAX_SHIFT_MS = 6 * 60 * 60_000L
    /** A fix further than this from the event time is not "the location at the event". */
    const val EVENT_MAX_FIX_DISTANCE_MS = 60_000L
    /** Stationary = last [STATIONARY_FIXES] fixes within [STATIONARY_RADIUS_M] and slower than [STATIONARY_SPEED_MPS]. */
    const val STATIONARY_FIXES = 3
    const val STATIONARY_RADIUS_M = 50.0
    const val STATIONARY_SPEED_MPS = 1.0f
    /** No sample for this many expected intervals = a monitoring gap. */
    const val GAP_FACTOR = 2.5

    /** Detector events that get their own sample (spec §5). */
    val DETECTOR_EVENT_TRIGGERS = setOf("DELIVERY_ACCEPTED", "PICKUP_DONE", "DROPOFF_DONE", "DELIVERY_COMPLETED")

    /** Live candidate state → trigger; null = no sample for this state. */
    fun triggerFor(candidateState: String, source: String): String? = when {
        source == "SYSTEM" || source == "ACTION" -> null
        candidateState == "OFFER_PRESENTED" -> "OFFER_PRESENTED"
        candidateState == "DELIVERY_ACTIVE" -> "DELIVERY_SCREEN"
        // Kept as AVAILABLE_SCREEN (= spec's AVAILABLE) so existing importers keep their meaning.
        candidateState == "AVAILABLE" && source == "SCREEN" -> "AVAILABLE_SCREEN"
        candidateState == "OFFLINE" -> "OFFLINE"
        else -> null
    }

    fun isDuplicate(lastAtForTrigger: Long?, nowMs: Long) = lastAtForTrigger != null && nowMs - lastAtForTrigger in 0 until DEDUP_MS

    fun periodicDue(nowMs: Long, lastPeriodicAtMs: Long?, intervalMs: Long): Boolean =
        lastPeriodicAtMs == null || nowMs - lastPeriodicAtMs >= intervalMs * 9 / 10

    /** No fix arrived for 2 intervals → write a NO_FIX periodic sample instead of staying silent. */
    fun noFixDue(nowMs: Long, lastFixAtMs: Long?, lastPeriodicAtMs: Long?, intervalMs: Long): Boolean =
        (lastFixAtMs == null || nowMs - lastFixAtMs >= 2 * intervalMs) && periodicDue(nowMs, lastPeriodicAtMs, intervalMs)

    fun shiftExpired(startedAtMs: Long, nowMs: Long) = nowMs - startedAtMs >= MAX_SHIFT_MS

    /** The fix closest in time to [eventAtMs], if within [EVENT_MAX_FIX_DISTANCE_MS]; else null. */
    fun fixForEvent(recent: List<LocationFix>, eventAtMs: Long): LocationFix? =
        recent.filter { abs(it.fixTimeMs - eventAtMs) <= EVENT_MAX_FIX_DISTANCE_MS }.minByOrNull { abs(it.fixTimeMs - eventAtMs) }

    fun isStationary(recent: List<LocationFix>): Boolean {
        val last = recent.sortedBy { it.fixTimeMs }.takeLast(STATIONARY_FIXES)
        if (last.size < STATIONARY_FIXES) return false
        val slow = last.all { it.speedMps == null || it.speedMps < STATIONARY_SPEED_MPS }
        val near = last.all { metersBetween(it, last.last()) <= STATIONARY_RADIUS_M }
        return slow && near
    }

    data class Gap(val fromMs: Long, val toMs: Long, val kind: String) // NO_SAMPLES | SERVICE_STOPPED

    /** Gaps inside one session: silences longer than GAP_FACTOR × the longest expected interval, plus a killed service. */
    fun gaps(sessionStartMs: Long, sessionEndMs: Long?, endReason: String?, sampleTimesMs: List<Long>, stationaryIntervalMs: Long): List<Gap> {
        val limit = (stationaryIntervalMs * GAP_FACTOR).toLong()
        val times = (listOf(sessionStartMs) + sampleTimesMs.sorted()).distinct()
        val out = times.zipWithNext().filter { (a, b) -> b - a > limit }.map { (a, b) -> Gap(a, b, "NO_SAMPLES") }.toMutableList()
        if (endReason == "SERVICE_STOPPED" && sessionEndMs != null) out += Gap(sessionEndMs, sessionEndMs, "SERVICE_STOPPED")
        return out
    }

    fun metersBetween(a: LocationFix, b: LocationFix): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
