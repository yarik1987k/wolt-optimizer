package dev.yk.offercollector.core

import kotlin.math.abs

/**
 * Pickup locations and the three separate demand metrics:
 *  1. Offer reception rate   — offers per verified available hour, by the COURIER's area (AreaDensity).
 *  2. Pickup demand density  — offers counted by the BUSINESS's area (where the order starts).
 *  3. Accessible offer density — offers that meet the courier's price/distance terms, per verified hour,
 *     by the courier's area.
 *
 * A business is identified by NAME + ADDRESS (branches stay apart) — never by name alone. Its area comes
 * from two independent sources:
 *   ADDRESS        the business address on the card, geocoded once (only after the courier confirmed it);
 *   GPS_AT_PICKUP  the courier's GPS at the detected "picked up" step of a one-order, one-business trip.
 * Both and in the same spot → ADDRESS_AND_GPS. Both but different → NEEDS_REVIEW (no automatic choice).
 * Neither → UNKNOWN. Nothing is ever inferred from a business name.
 */
object PickupDemand {
    data class Event(val type: String, val evidence: String, val atMs: Long, val tripIndex: Int?)
    data class TripInfo(val index: Int, val orders: Int)
    data class Fix(val atMs: Long, val cellId: String?, val eventKey: String?)
    data class OfferIn(
        val firstSeenMs: Long,
        val amountAgorot: Long,
        val distanceKm: Double?,
        val pickupNames: List<String>,
        /** Trip this offer became (accepted offers only). */
        val acceptedTripIndex: Int?,
        val pickupAddress: String? = null,
    ) {
        /** Business identity: name + address; null when either is missing or there are several businesses. */
        val businessKey: String? get() = pickupNames.singleOrNull()?.let { n -> pickupAddress?.let { "$n|$it" } }
    }

    enum class Basis { ADDRESS_AND_GPS, ADDRESS, GPS_AT_PICKUP, NEEDS_REVIEW, UNKNOWN }

    data class Assigned(val offer: OfferIn, val pickupCell: String?, val basis: Basis)

    /** Cells next to each other count as one spot (a business on a cell edge). */
    fun sameSpot(cells: Set<String>): Boolean {
        val pts = cells.mapNotNull { id -> id.split(":").takeIf { it.size == 3 }?.let { it[1].toLong() to it[2].toLong() } }
        return pts.all { a -> pts.all { b -> abs(a.first - b.first) <= 1 && abs(a.second - b.second) <= 1 } }
    }

    /** One real pickup: the offer, its business, and the courier's area at the "picked up" step. */
    data class Observation(val offerFirstSeenMs: Long, val businessKey: String?, val cellId: String, val evidence: String)

    /** Where the courier was at each detected "picked up" step of a one-order, one-business trip. */
    fun observations(offers: List<OfferIn>, events: List<Event>, trips: List<TripInfo>, fixes: List<Fix>): List<Observation> = offers.mapNotNull { o ->
        val trip = trips.firstOrNull { it.index == o.acceptedTripIndex } ?: return@mapNotNull null
        o.pickupNames.singleOrNull() ?: return@mapNotNull null
        if (trip.orders != 1) return@mapNotNull null
        val pickup = events.firstOrNull { it.type == "PICKUP_DONE" && it.tripIndex == trip.index && it.evidence != "UNKNOWN" } ?: return@mapNotNull null
        val fix = fixes.firstOrNull { it.eventKey == "PICKUP_DONE|${pickup.atMs}" && it.cellId != null }
            ?: fixes.filter { it.cellId != null && abs(it.atMs - pickup.atMs) <= 60_000 }.minByOrNull { abs(it.atMs - pickup.atMs) }
            ?: return@mapNotNull null
        Observation(o.firstSeenMs, o.businessKey, fix.cellId!!, pickup.evidence)
    }

    /**
     * @param geocoded business key → cell of an OK geocode (absent when pending / not found / ambiguous).
     */
    fun assign(offers: List<OfferIn>, obs: List<Observation>, geocoded: Map<String, String>): List<Assigned> {
        val own = obs.associateBy { it.offerFirstSeenMs }
        val gpsByKey = obs.filter { it.businessKey != null }.groupBy({ it.businessKey!! }, { it.cellId })
        return offers.map { o ->
            val key = o.businessKey
            val g = key?.let { geocoded[it] }
            val gps = ((key?.let { gpsByKey[it] }.orEmpty()) + listOfNotNull(own[o.firstSeenMs]?.cellId)).toSet()
            when {
                g != null && gps.isNotEmpty() ->
                    if (sameSpot(gps + g)) Assigned(o, g, Basis.ADDRESS_AND_GPS) else Assigned(o, null, Basis.NEEDS_REVIEW)
                g != null -> Assigned(o, g, Basis.ADDRESS)
                gps.isNotEmpty() && sameSpot(gps) -> Assigned(o, own[o.firstSeenMs]?.cellId ?: gps.first(), Basis.GPS_AT_PICKUP)
                gps.isNotEmpty() -> Assigned(o, null, Basis.NEEDS_REVIEW)
                else -> Assigned(o, null, Basis.UNKNOWN)
            }
        }
    }

    data class PickupArea(
        val cellId: String,
        val offers: Int,
        val meanAgorot: Double?,
        val medianAgorot: Double?,
        val byHour: IntArray,
        val businesses: List<Pair<String, Int>>,
        /** How the areas are known: counts per basis. */
        val bases: Map<Basis, Int>,
    )

    data class PickupResult(val areas: List<PickupArea>, val known: Int, val unknown: Int, val needsReview: Int)

    /** Counts by business area — a count of observed offers, not a rate (exposure to each area is unknown). */
    fun pickupDensity(assigned: List<Assigned>, window: AreaDensity.Window): PickupResult {
        val inW = assigned.filter { window.contains(it.offer.firstSeenMs) }
        val known = inW.filter { it.pickupCell != null }
        val areas = known.groupBy { it.pickupCell!! }.map { (cell, xs) ->
            val amounts = xs.map { it.offer.amountAgorot.toDouble() }.sorted()
            val hours = IntArray(24)
            xs.forEach { hours[OfferAnalytics.localHour(it.offer.firstSeenMs)]++ }
            PickupArea(
                cellId = cell,
                offers = xs.size,
                meanAgorot = amounts.average(),
                medianAgorot = if (amounts.size % 2 == 1) amounts[amounts.size / 2] else (amounts[amounts.size / 2 - 1] + amounts[amounts.size / 2]) / 2,
                byHour = hours,
                businesses = xs.flatMap { it.offer.pickupNames }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value },
                bases = xs.groupingBy { it.basis }.eachCount(),
            )
        }.sortedWith(compareByDescending<PickupArea> { it.offers }.thenBy { it.cellId })
        return PickupResult(areas, known.size, inW.count { it.basis == Basis.UNKNOWN }, inW.count { it.basis == Basis.NEEDS_REVIEW })
    }

    /** The courier's terms for an "accessible" offer. Null field = no limit. */
    data class Terms(val minAgorot: Long?, val maxKm: Double?, val minAgorotPerKm: Long?) {
        /** Unknown distance fails a distance/₪-per-km term — never assumed to pass. */
        fun accepts(o: OfferIn): Boolean {
            if (minAgorot != null && o.amountAgorot < minAgorot) return false
            if (maxKm != null && (o.distanceKm == null || o.distanceKm > maxKm)) return false
            if (minAgorotPerKm != null && (o.distanceKm == null || o.distanceKm <= 0 || o.amountAgorot / o.distanceKm < minAgorotPerKm)) return false
            return true
        }
        val label: String get() = listOfNotNull(
            minAgorot?.let { "מ-₪${it / 100}" },
            maxKm?.let { "עד ${it.toInt()} ק״מ" },
            minAgorotPerKm?.let { "לפחות ₪${it / 100} לק״מ" },
        ).joinToString(" · ").ifEmpty { "בלי תנאים" }
    }
}
