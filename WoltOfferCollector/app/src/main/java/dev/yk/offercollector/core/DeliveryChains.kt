package dev.yk.offercollector.core

import kotlinx.serialization.Serializable

/**
 * Delivery chains: overlapping accepted offers (real shift 29.10.2026).
 *
 * Wolt adds offers while earlier orders are still on board: offer A+B → A delivered → offer C accepted
 * while B is carried → C delivered → offer D accepted → D delivered → B delivered. So an accepted offer
 * is NOT an isolated trip, and the detector's per-offer trip end inside a chain is not trustworthy.
 *
 * Evidence: the route timeline on the pickup screen lists EVERY active stop. An order's pickup stays
 * listed until the order is delivered, so a pickup already listed for the previous offer is a CARRIED
 * order. Stops are matched by their redacted shape only (no text) — a carried link is therefore
 * always SHAPE_MATCH (uncertain), never a proven identity.
 *
 * Kept apart: OFFER (card) · ACCEPTED OFFER (this list) · ORDER (one customer's order; counted, never
 * given an invented id) · CHAIN (from an acceptance with nothing carried until the next such acceptance).
 */
object DeliveryChains {
    const val VERSION = "chains-1"

    @Serializable
    data class AcceptedOffer(
        /** Detector trip index = acceptance index. */
        val trip_index: Int,
        val accepted_at_ms: Long,
        val offer_amount_agorot: Long?,
        val chain_index: Int,
        /** Stable chain key: acceptance time of the chain's first offer. */
        val chain_started_at_ms: Long = 0,
        /** IDLE (route showed nothing carried) | ADDED_TO_ACTIVE_ROUTE | UNKNOWN (no route seen). */
        val accept_context: String,
        val route_pickups_at_accept: Int? = null,
        val route_dropoffs_at_accept: Int? = null,
        /** Pickups also listed for the previous accepted offer = orders still on board. */
        val carried_orders: Int? = null,
        /** NONE | SHAPE_MATCH — carried links are matched by redacted shape only. */
        val carried_link: String = "NONE",
        /** New orders this offer added: route drop-offs at acceptance − carried (one drop-off per carried order assumed). */
        val new_orders_low: Int? = null,
        val new_orders_high: Int? = null,
        /** "заказы N" on the card — orders at one pickup, not the offer total. */
        val card_order_count: Int? = null,
        /** False when the offer shares a chain: the detector's per-offer end time is not its own completion. */
        val trip_boundary_valid: Boolean,
        val notes: List<String> = emptyList(),
    )

    @Serializable
    data class Chain(
        val index: Int,
        val started_at_ms: Long,
        /** End of the last accepted offer's trip, when the detector found one (ESTIMATED at best). */
        val ended_at_ms: Long? = null,
        val end_evidence: String,
        val trip_indexes: List<Int>,
        /** Sum of the accepted offers' amounts — each offer counted once. */
        val revenue_agorot: Long,
        val orders_low: Int? = null,
        val orders_high: Int? = null,
    )

    data class Result(val offers: List<AcceptedOffer>, val chains: List<Chain>) {
        /** Alone in its chain, accepted while idle: its detector trip is its own delivery. */
        fun isolated(tripIndex: Int) = offers.firstOrNull { it.trip_index == tripIndex }?.let { it.trip_boundary_valid && it.accept_context == "IDLE" } == true
    }

    /**
     * At decision time (shadow mode): is a route active? The latest task screen (pickup/drop-off) being
     * newer than the latest idle home screen → ACTIVE_ROUTE. It may be the last order being finished —
     * that cannot be known yet, so it is never treated as IDLE.
     */
    fun contextAt(items: List<DeliveryEventDetector.Item>, atMs: Long): String {
        val screens = items.filter { it.atMs < atMs && it.markers.source == "SCREEN" && !it.markers.offer_card }
        val task = screens.lastOrNull { DeliveryEventDetector.isPickupScreen(it.markers) || DeliveryEventDetector.isDropoffScreen(it.markers) }?.atMs
        val idle = screens.lastOrNull { it.markers.home_screen }?.atMs
        return when {
            task == null && idle == null -> "UNKNOWN"
            task != null && (idle == null || task > idle) -> "ACTIVE_ROUTE"
            else -> "IDLE"
        }
    }

    fun build(trips: List<DeliveryEventDetector.Trip>, screens: List<OfferScreen>, offers: List<DetectedOffer> = emptyList()): Result {
        val sorted = trips.sortedBy { it.started_at_ms }
        val shots = screens.sortedBy { it.observedAtMs }
        // Route snapshots per accepted offer: from its acceptance until the next acceptance.
        val routes = sorted.mapIndexed { i, t ->
            val end = sorted.getOrNull(i + 1)?.started_at_ms ?: Long.MAX_VALUE
            shots.filter { it.observedAtMs >= t.started_at_ms && it.observedAtMs < end }.map { TripOrders.route(it.fields) }.filter { it.isNotEmpty() }
        }
        val out = mutableListOf<AcceptedOffer>()
        var chain = 0
        sorted.forEachIndexed { i, t ->
            val card = offers.filter { it.firstSeenMs <= t.started_at_ms && t.started_at_ms - it.firstSeenMs <= 5 * 60_000 && it.amountAgorot in t.offer_amounts_agorot }
                .maxByOrNull { it.firstSeenMs }
            val first = routes[i].firstOrNull()
            val notes = mutableListOf<String>()
            if (first == null) {
                chain += 1 // nothing known about what was carried: start a new chain, say so
                notes += "no route timeline seen — carried orders unknown"
                out += AcceptedOffer(t.index, t.started_at_ms, t.offer_amounts_agorot.firstOrNull(), chain, accept_context = "UNKNOWN", card_order_count = card?.orderCount, trip_boundary_valid = false, notes = notes)
                return@forEachIndexed
            }
            val pickups = first.filter { it.kind == "PICKUP" }
            val drops = first.count { it.kind == "DROPOFF" }
            // Pickups listed for the previous offer (any of its snapshots) that are still listed now.
            val before = routes.getOrNull(i - 1).orEmpty().flatten().filter { it.kind == "PICKUP" }.map { it.shape }.toMutableList()
            var carried = 0
            for (p in pickups) if (before.remove(p.shape)) carried++
            val context = if (carried > 0) "ADDED_TO_ACTIVE_ROUTE" else "IDLE"
            if (context == "IDLE") chain += 1
            val dropsMin = routes[i].minOf { r -> r.count { it.kind == "DROPOFF" } }
            if (dropsMin != drops) notes += "drop-offs in route changed $drops→$dropsMin during pickup (merged stop or removed order — unknown)"
            if (carried > 0) notes += "$carried carried order(s) matched by stop shape only"
            out += AcceptedOffer(
                trip_index = t.index,
                accepted_at_ms = t.started_at_ms,
                offer_amount_agorot = t.offer_amounts_agorot.firstOrNull(),
                chain_index = chain,
                accept_context = context,
                route_pickups_at_accept = pickups.size,
                route_dropoffs_at_accept = drops,
                carried_orders = carried,
                carried_link = if (carried > 0) "SHAPE_MATCH" else "NONE",
                new_orders_low = maxOf(0, minOf(drops, dropsMin) - carried),
                new_orders_high = maxOf(0, drops - carried),
                card_order_count = card?.orderCount,
                trip_boundary_valid = true, // set below once chains are known
                notes = notes,
            )
        }
        // A detector trip end is the offer's own completion only when its chain holds that one offer.
        val sizes = out.groupingBy { it.chain_index }.eachCount()
        val fixed = out.map { o ->
            if (o.accept_context == "UNKNOWN") o
            else if (sizes[o.chain_index]!! > 1) o.copy(trip_boundary_valid = false, notes = o.notes + "shares chain ${o.chain_index}: detector trip end is not this offer's completion")
            else o
        }
        val chains = fixed.groupBy { it.chain_index }.map { (idx, os) ->
            val last = sorted.first { it.index == os.last().trip_index }
            val known = os.all { it.new_orders_low != null }
            Chain(
                index = idx,
                started_at_ms = os.first().accepted_at_ms,
                ended_at_ms = last.completed_at_ms,
                end_evidence = last.end_evidence,
                trip_indexes = os.map { it.trip_index },
                revenue_agorot = os.sumOf { it.offer_amount_agorot ?: 0 },
                orders_low = if (known) os.sumOf { it.new_orders_low!! } else null,
                orders_high = if (known) os.sumOf { it.new_orders_high!! } else null,
            )
        }
        val starts = chains.associate { it.index to it.started_at_ms }
        return Result(fixed.map { it.copy(chain_started_at_ms = starts.getValue(it.chain_index)) }, chains)
    }
}
