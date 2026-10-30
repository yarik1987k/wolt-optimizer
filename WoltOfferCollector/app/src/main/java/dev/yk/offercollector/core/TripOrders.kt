package dev.yk.offercollector.core

import kotlinx.serialization.Serializable

/**
 * How many ORDERS a trip carried, from Wolt's own route timeline (real shift 29.10.2026).
 *
 * A trip (one accepted offer) is not one order: the pickup screen lists the route as
 * `TimelineItemV3.Label` + `TimelineItemV3.Description` pairs. A stop whose address carries a
 * postal code (LONG_NUMBER) is a business (pickup); one without is a customer (drop-off). Only the
 * redacted structure is used — no names or addresses. The card's "заказы 2" is NOT the trip total
 * (it is the order count at one pickup), so it is reported separately, never used as the count.
 */
object TripOrders {
    const val VERSION = "trip-orders-1"

    data class Stops(val pickups: Int, val dropoffs: Int)

    /** One route stop: PICKUP | DROPOFF and its redacted shape (label + address structure, no text). */
    data class Stop(val kind: String, val shape: String)

    /** The route as listed in one snapshot, in order; empty when the snapshot has no timeline. */
    fun route(f: CandidateFields): List<Stop> {
        val out = mutableListOf<Stop>()
        var label = ""
        for (n in f.nodes) {
            when (n.viewId) {
                "TimelineItemV3.Label" -> label = n.redacted
                "TimelineItemV3.Description" -> out += Stop(if (TokenKind.LONG_NUMBER in n.kinds) "PICKUP" else "DROPOFF", "$label @ ${n.redacted}")
            }
        }
        return out
    }

    /** Stops in one snapshot's route timeline, or null when the snapshot has no timeline. */
    fun timeline(f: CandidateFields): Stops? {
        var pickups = 0
        var dropoffs = 0
        for (n in f.nodes) {
            if (n.viewId != "TimelineItemV3.Description") continue
            if (TokenKind.LONG_NUMBER in n.kinds) pickups++ else dropoffs++
        }
        return if (pickups + dropoffs == 0) null else Stops(pickups, dropoffs)
    }

    @Serializable
    data class Evidence(
        val trip_index: Int,
        /** Snapshots of this trip that showed the route timeline. */
        val timeline_snapshots: Int,
        val pickups_first: Int? = null,
        val dropoffs_first: Int? = null,
        val dropoffs_min: Int? = null,
        val dropoffs_max: Int? = null,
        /** The number of drop-offs changed during the trip (order added/removed, or the list was only partly drawn). */
        val timeline_changed: Boolean = false,
        /** "заказы N" on the accepted card: orders at ONE pickup, not the trip total. */
        val card_order_count: Int? = null,
        /** SINGLE_ORDER | MULTI_ORDER | CHANGED | NO_TIMELINE */
        val status: String,
        /** Order count range supported by the evidence; null = unknown. */
        val orders_low: Int? = null,
        val orders_high: Int? = null,
    ) {
        /** Only a trip whose route showed exactly one pickup and one drop-off every time is a proven single order. */
        val singleOrderVerified: Boolean get() = status == "SINGLE_ORDER"
    }

    /**
     * One entry per trip. A trip's snapshots are those from its start until the next trip starts
     * (the timeline is shown on the pickup screens, right after acceptance).
     */
    fun forTrips(trips: List<DeliveryEventDetector.Trip>, screens: List<OfferScreen>, offers: List<DetectedOffer> = emptyList()): List<Evidence> {
        val sorted = trips.sortedBy { it.started_at_ms }
        val shots = screens.sortedBy { it.observedAtMs }
        return sorted.mapIndexed { i, t ->
            val end = sorted.getOrNull(i + 1)?.started_at_ms ?: Long.MAX_VALUE
            val stops = shots.filter { it.observedAtMs >= t.started_at_ms && it.observedAtMs < end }.mapNotNull { timeline(it.fields) }
            val card = offers.filter { it.firstSeenMs <= t.started_at_ms && t.started_at_ms - it.firstSeenMs <= 5 * 60_000 && it.amountAgorot in t.offer_amounts_agorot }
                .maxByOrNull { it.firstSeenMs }
            if (stops.isEmpty()) {
                Evidence(t.index, 0, card_order_count = card?.orderCount, status = "NO_TIMELINE")
            } else {
                val lo = stops.minOf { it.dropoffs }
                val hi = stops.maxOf { it.dropoffs }
                val changed = lo != hi
                Evidence(
                    trip_index = t.index,
                    timeline_snapshots = stops.size,
                    pickups_first = stops.first().pickups,
                    dropoffs_first = stops.first().dropoffs,
                    dropoffs_min = lo,
                    dropoffs_max = hi,
                    timeline_changed = changed,
                    card_order_count = card?.orderCount,
                    status = when {
                        changed -> "CHANGED"
                        hi == 1 && stops.all { it.pickups == 1 } -> "SINGLE_ORDER"
                        else -> "MULTI_ORDER"
                    },
                    orders_low = lo,
                    orders_high = hi,
                )
            }
        }
    }
}
