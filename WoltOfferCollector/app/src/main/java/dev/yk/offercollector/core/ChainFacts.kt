package dev.yk.offercollector.core

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Facts for chain economics and the future incremental-offer model. Pure, rebuilt from raw data on
 * every export (raw events are never edited). Three kinds of value are kept apart:
 *  - OBSERVED: seen on screen with a time (e.g. a drop-off slide);
 *  - WOLT_DISPLAY: a number Wolt showed (ETA minutes, clock times) whose meaning is not verified;
 *  - ESTIMATE: computed by us, always with its method and bounds.
 *
 * Completion (chain-facts-2): completing a drop-off needs Wolt on screen — the courier slides
 * "заказ доставлен". While sliding, the button text gains a token; that state is captured even when
 * Wolt is otherwise in the background. Real shift 29.10.2026: 10 slides = Wolt's 10 completed deliveries.
 * Per-offer completion times inside a chain are still never assigned.
 */
object ChainFacts {
    const val VERSION = "chain-facts-3"
    /** Gaps up to this count as covered by GPS (the sampler runs every 45–120 s). */
    const val COVERED_GAP_MS = 120_000L
    /** A chain's GPS distance is usable for vehicle cost only when no gap exceeds this. */
    const val RELIABLE_MAX_GAP_MS = 180_000L

    /** A location sample reduced to what distance needs (coarse ~500 m cell centre + speed). */
    data class GpsPoint(val atMs: Long, val lat: Double, val lon: Double, val speedMps: Float?, val cellId: String)

    @Serializable
    data class RouteStop(
        val kind: String, // PICKUP | DROPOFF
        /** Redacted structure of label + address (no text). */
        val shape: String,
        /** NEW | CARRIED (shape match) | AMBIGUOUS (several identical shapes) | null in a before-route. */
        val status: String? = null,
        /** Order in Wolt's route list (0 = first). */
        val position: Int = 0,
        /** WOLT_DISPLAY: minutes shown right after this stop; null = none (e.g. the stop was already reached). */
        val eta_minutes: Int? = null,
        /** snapshot time + eta_minutes = the stop's TARGET clock time (see EtaValidation: not a live ETA). */
        val target_at_ms: Long? = null,
    )

    @Serializable
    data class RouteSnapshot(
        val at_ms: Long,
        val stops: List<RouteStop>,
        /** A frame showing old minutes right after the screen resumed (EtaValidation.staleFrames). */
        val stale: Boolean = false,
    )

    /** WOLT_DISPLAY: the drop-off screen's state — clock times of the active orders (pairs) and the marker. */
    @Serializable
    data class DropScreenSnapshot(val at_ms: Long, val order_times_ms: List<Long>, val marker_minutes: Int? = null)

    /** One change of the active route inside a chain, with the full ordered stop list. */
    @Serializable
    data class RouteChange(
        val at_ms: Long,
        val active_pickups: Int,
        val active_dropoffs: Int,
        /** Stops present now but not in the previous snapshot / gone since (by shape). */
        val added_stops: Int,
        val removed_stops: Int,
        val stops: List<RouteStop> = emptyList(),
    )

    /** WOLT_DISPLAY on drop-off screens: the map marker's minutes (meaning unverified). */
    @Serializable
    data class MarkerMinutes(val at_ms: Long, val minutes: Int)

    @Serializable
    data class OfferContext(
        val trip_index: Int,
        val chain_started_at_ms: Long,
        /** IDLE (case A) | ADDED_TO_ACTIVE_ROUTE (case B) | UNKNOWN */
        val accept_context: String,
        val offer_seen_at_ms: Long? = null,
        val offer_amount_agorot: Long? = null,
        /** Courier's ~500 m cell when the card appeared (nearest fix within 2 min). */
        val offer_cell: String? = null,
        /** WOLT_DISPLAY on the card: distance and minutes (meaning of the minutes unverified). */
        val card_distance_km: Double? = null,
        val card_minutes: Int? = null,
        /** OBSERVED range: accepted between the card closing and the pickup screen appearing. */
        val accepted_earliest_ms: Long? = null,
        val accepted_latest_ms: Long,
        /** Orders already on board (carried pickups still listed); null = unknown. */
        val active_orders_before: Int? = null,
        val new_orders_low: Int? = null,
        val new_orders_high: Int? = null,
        /** HIGH (exact, no shape links) | MEDIUM (exact, carried link by shape) | LOW (a range) | UNKNOWN */
        val new_orders_confidence: String,
        /** Last route seen before acceptance (ADDED only) and its age at acceptance. */
        val route_before: RouteSnapshot? = null,
        val route_before_age_ms: Long? = null,
        /** Last drop-off screen before acceptance (ADDED only): the active orders' clock times right before. */
        val drop_screen_before: DropScreenSnapshot? = null,
        val drop_screen_before_age_ms: Long? = null,
        /** First route seen after acceptance, each stop marked NEW / CARRIED / AMBIGUOUS. */
        val route_after: RouteSnapshot? = null,
        val route_after_delay_ms: Long? = null,
        val new_pickup_stops: Int? = null,
        val new_dropoff_stops_by_shape: Int? = null,
        val ambiguous_dropoff_stops: Int? = null,
        /** OBSERVED: completions between the before-route and the acceptance — the route changed for another reason. */
        val completions_between_before_and_accept: Int? = null,
        /** WOLT_DISPLAY: largest move of a carried stop's target between before and after (minutes, signed). */
        val carried_target_shift_min: Double? = null,
        /** True when something other than this offer changed the route between the two snapshots. */
        val route_changed_for_other_reasons: Boolean? = null,
        /**
         * The chain's duration without this offer is UNKNOWN: Wolt's targets do not move when work is added
         * (EtaValidation), and no justified route model exists. No bound or estimate is given.
         */
        val counterfactual_duration: String = "UNKNOWN",
        val notes: List<String> = emptyList(),
    )

    @Serializable
    data class ChainEconomicsFacts(
        val chain_started_at_ms: Long,
        val trip_indexes: List<Int>,
        val offers: Int,
        val revenue_agorot: Long,
        val orders_low: Int? = null,
        val orders_high: Int? = null,
        val start_earliest_ms: Long? = null,
        val start_latest_ms: Long,
        val end_earliest_ms: Long? = null,
        val end_latest_ms: Long? = null,
        /** OBSERVED_SLIDE | ESTIMATED (detector screen sequence) | UNKNOWN */
        val end_evidence: String,
        /** OBSERVED: drop-off slides in this chain (one per completed delivery). */
        val completion_slides_ms: List<Long> = emptyList(),
        val duration_min_ms: Long? = null,
        val duration_max_ms: Long? = null,
        val gps_samples: Int = 0,
        val gps_max_gap_ms: Long? = null,
        /** Share of the chain's time with a fix at most COVERED_GAP_MS apart. */
        val gps_coverage: Double? = null,
        /** Two estimates: path through ~500 m cell centres, and Σ speed × time. */
        val gps_path_km: Double? = null,
        val gps_speed_km: Double? = null,
        val gps_distance_reliable: Boolean = false,
        /** OBSERVED (GPS speed): whole chain, and split at the first pickup completion (to-pickup + wait | delivery). */
        val gps_stationary_ms: Long? = null,
        val gps_moving_ms: Long? = null,
        val first_pickup_done_ms: Long? = null,
        val pickup_phase_stationary_ms: Long? = null,
        val pickup_phase_moving_ms: Long? = null,
        val delivery_phase_stationary_ms: Long? = null,
        val delivery_phase_moving_ms: Long? = null,
        val route_changes: List<RouteChange> = emptyList(),
        val marker_minutes_shown: List<MarkerMinutes> = emptyList(),
        val missing: List<String> = emptyList(),
    )

    data class Result(val offers: List<OfferContext>, val chains: List<ChainEconomicsFacts>)

    // ---- observations from screen snapshots ----

    private fun minutes(redacted: String): List<Int> = Regex("""\[MIN (\d+)]""").findAll(redacted).map { it.groupValues[1].toInt() }.toList()

    /** The drop-off button is mid-slide: its text gains a token after "доставлен". */
    fun isSlide(f: CandidateFields): Boolean =
        f.nodes.any { it.viewId == "CompleteDropoff" && TokenKind.OTHER_TEXT in it.kinds }

    /** Completion slides: a run of consecutive slide snapshots counts once (its first time). */
    fun slides(screens: List<OfferScreen>): List<Long> {
        val out = mutableListOf<Long>()
        var inSlide = false
        for (s in screens.sortedBy { it.observedAtMs }) {
            val ids = s.fields.nodes.map { it.viewId }
            if ("CompleteDropoff" !in ids && s.fields.nodes.isEmpty()) continue // empty frame: no information
            val slide = isSlide(s.fields)
            if (slide && !inSlide) out += s.observedAtMs
            inSlide = slide
        }
        return out
    }

    // ---- build ----

    fun build(
        detected: DeliveryEventDetector.Result,
        chains: DeliveryChains.Result,
        screens: List<OfferScreen>,
        offers: List<DetectedOffer>,
        gps: List<GpsPoint>,
    ): Result {
        val sorted = screens.sortedBy { it.observedAtMs }
        val rawRoutes = sorted.mapNotNull { EtaValidation.route(it.fields, it.observedAtMs) }
        val stale = EtaValidation.staleFrames(rawRoutes)
        val routes = rawRoutes.map { r ->
            RouteSnapshot(r.atMs, r.stops.map { RouteStop(it.kind, it.shape, null, it.position, it.etaMinutes, it.targetMs) }, r.atMs in stale)
        }
        val dropScreens = sorted.mapNotNull { EtaValidation.dropScreen(it.fields, it.observedAtMs) }.filter { it.times.isNotEmpty() }
        val markers = sorted.mapNotNull { s ->
            s.fields.nodes.firstOrNull { it.viewId == "TaskMarker.PopoutLabel" && TokenKind.DURATION_MIN in it.kinds }
                ?.let { n -> minutes(n.redacted).firstOrNull()?.let { MarkerMinutes(s.observedAtMs, it) } }
        }
        val allSlides = slides(sorted)
        val points = gps.sortedBy { it.atMs }
        val trips = detected.trips.associateBy { it.index }
        val accepts = detected.events.filter { it.type == "DELIVERY_ACCEPTED" && it.trip_index != null }.associateBy { it.trip_index!! }

        // Chain windows: from its first acceptance until the next chain starts.
        val windows = chains.chains.mapIndexed { i, c ->
            val from = accepts[c.trip_indexes.first()]?.earliest_at_ms ?: c.started_at_ms
            val to = chains.chains.getOrNull(i + 1)?.let { n -> accepts[n.trip_indexes.first()]?.earliest_at_ms ?: n.started_at_ms } ?: Long.MAX_VALUE
            c.index to (from until to)
        }.toMap()

        val facts = chains.chains.map { c ->
            val window = windows.getValue(c.index)
            val last = trips.getValue(c.trip_indexes.last())
            val missing = mutableListOf<String>()
            val startEarliest = accepts[c.trip_indexes.first()]?.earliest_at_ms
            val startLatest = c.started_at_ms
            val slides = allSlides.filter { it in window && it >= startLatest }
            var endEarliest: Long? = null
            var endLatest: Long? = null
            val endEvidence: String
            if (slides.isNotEmpty()) {
                endEarliest = slides.last()
                // The slide state ends with the next snapshot; that bounds the completion from above.
                endLatest = sorted.firstOrNull { it.observedAtMs > slides.last() && !isSlide(it.fields) }?.observedAtMs?.takeIf { it - slides.last() <= 60_000 } ?: slides.last()
                endEvidence = "OBSERVED_SLIDE"
                val lo = c.orders_low
                val hi = c.orders_high
                if (lo != null && hi != null && slides.size !in lo..hi) missing += "observed ${slides.size} completion slides but the route showed $lo–$hi orders"
            } else {
                val done = detected.events.lastOrNull { it.type == "DELIVERY_COMPLETED" && it.trip_index == last.index }
                endEarliest = done?.let { it.earliest_at_ms ?: it.action_at_ms }
                endLatest = done?.action_at_ms
                endEvidence = if (done == null) "UNKNOWN" else done.evidence
                if (done == null) missing += "end unknown: no completion slide and ${last.end_note ?: "no confirmed final drop-off"}"
                else missing += "end from screen sequence only (no completion slide seen)"
            }
            if (startEarliest == null) missing += "start known only as the pickup screen time"
            val durMin = endEarliest?.let { it - startLatest }
            val durMax = endLatest?.let { it - (startEarliest ?: startLatest) }
            val g = if (endLatest != null) gps(points, startEarliest ?: startLatest, endLatest) else null
            // Phase split at the first pickup completion of the chain's first offer (ESTIMATED: the drop-off screen appearing).
            val pickupDone = detected.events.firstOrNull { it.type == "PICKUP_DONE" && it.trip_index == c.trip_indexes.first() }?.action_at_ms
            val gPick = pickupDone?.let { gps(points, startEarliest ?: startLatest, it) }
            val gDrop = if (pickupDone != null && endLatest != null) gps(points, pickupDone, endLatest) else null
            if (g == null) missing += "GPS distance needs a known end"
            else if (!g.reliable) missing += "GPS not reliable: ${g.reason}"
            val inChain = routes.filter { !it.stale && it.at_ms in window && endLatest?.let { e -> it.at_ms <= e } != false }
            ChainEconomicsFacts(
                chain_started_at_ms = c.started_at_ms,
                trip_indexes = c.trip_indexes,
                offers = c.trip_indexes.size,
                revenue_agorot = c.revenue_agorot,
                orders_low = c.orders_low,
                orders_high = c.orders_high,
                start_earliest_ms = startEarliest,
                start_latest_ms = startLatest,
                end_earliest_ms = endEarliest,
                end_latest_ms = endLatest,
                end_evidence = endEvidence,
                completion_slides_ms = slides,
                duration_min_ms = durMin?.takeIf { it >= 0 },
                duration_max_ms = durMax,
                gps_samples = g?.samples ?: 0,
                gps_max_gap_ms = g?.maxGapMs,
                gps_coverage = g?.coverage,
                gps_path_km = g?.pathKm,
                gps_speed_km = g?.speedKm,
                gps_distance_reliable = g?.reliable == true,
                gps_stationary_ms = g?.stationaryMs,
                gps_moving_ms = g?.movingMs,
                first_pickup_done_ms = pickupDone,
                pickup_phase_stationary_ms = gPick?.stationaryMs,
                pickup_phase_moving_ms = gPick?.movingMs,
                delivery_phase_stationary_ms = gDrop?.stationaryMs,
                delivery_phase_moving_ms = gDrop?.movingMs,
                route_changes = changes(inChain),
                marker_minutes_shown = markers.filter { it.at_ms in window && endLatest?.let { e -> it.at_ms <= e } != false }.dedupe(),
                missing = missing,
            )
        }

        val ctx = chains.offers.map { o ->
            val t = trips.getValue(o.trip_index)
            val card = offers.filter { it.firstSeenMs <= t.started_at_ms && t.started_at_ms - it.firstSeenMs <= 5 * 60_000 && it.amountAgorot in t.offer_amounts_agorot }
                .maxByOrNull { it.firstSeenMs }
            val acceptedEarliest = accepts[o.trip_index]?.earliest_at_ms
            val acceptAt = acceptedEarliest ?: t.started_at_ms
            val after = routes.firstOrNull { !it.stale && it.at_ms >= t.started_at_ms }
                ?.takeIf { r -> chains.offers.none { it.trip_index != o.trip_index && it.accepted_at_ms > t.started_at_ms && it.accepted_at_ms <= r.at_ms } }
            val added = o.accept_context == "ADDED_TO_ACTIVE_ROUTE"
            val before = if (added) routes.lastOrNull { !it.stale && it.at_ms < acceptAt && it.at_ms >= o.chain_started_at_ms } else null
            val dropBefore = if (added) dropScreens.lastOrNull { it.atMs < acceptAt && it.atMs >= o.chain_started_at_ms } else null
            val marked = after?.let { mark(before, it) }
            val notes = mutableListOf<String>()
            var between: Int? = null
            var shift: Double? = null
            var other: Boolean? = null
            if (added) {
                if (before == null) notes += "no route snapshot before acceptance"
                if (after == null) notes += "no route snapshot after acceptance"
                if (before != null && marked != null) {
                    between = allSlides.count { it > before.at_ms && it < acceptAt }
                    // Carried stops: same shape before and after (CARRIED); their target moves are not caused by data we have.
                    shift = marked.stops.filter { it.status == "CARRIED" && it.target_at_ms != null }.mapNotNull { a ->
                        before.stops.firstOrNull { it.kind == a.kind && it.shape == a.shape && it.target_at_ms != null }?.let { b -> (a.target_at_ms!! - b.target_at_ms!!) / 60_000.0 }
                    }.maxByOrNull { kotlin.math.abs(it) }
                    other = between > 0
                    if (between > 0) notes += "$between completion(s) between the before-route and acceptance — the route also changed for that reason"
                    if (shift != null) notes += "carried stop target moved ${"%+.0f".format(java.util.Locale.US, shift)} min (WOLT_DISPLAY; targets are not live ETAs)"
                }
            }
            OfferContext(
                trip_index = o.trip_index,
                chain_started_at_ms = o.chain_started_at_ms,
                accept_context = o.accept_context,
                offer_seen_at_ms = card?.firstSeenMs,
                offer_amount_agorot = o.offer_amount_agorot,
                offer_cell = nearest(points, card?.firstSeenMs)?.cellId,
                card_distance_km = card?.distanceKm?.toDoubleOrNull(),
                card_minutes = card?.durationMin,
                accepted_earliest_ms = acceptedEarliest,
                accepted_latest_ms = t.started_at_ms,
                active_orders_before = o.carried_orders,
                new_orders_low = o.new_orders_low,
                new_orders_high = o.new_orders_high,
                new_orders_confidence = when {
                    o.new_orders_low == null -> "UNKNOWN"
                    o.new_orders_low != o.new_orders_high -> "LOW"
                    o.carried_link == "SHAPE_MATCH" -> "MEDIUM"
                    else -> "HIGH"
                },
                route_before = before,
                route_before_age_ms = before?.let { acceptAt - it.at_ms },
                drop_screen_before = dropBefore?.let { DropScreenSnapshot(it.atMs, it.times, it.markerMinutes) },
                drop_screen_before_age_ms = dropBefore?.let { acceptAt - it.atMs },
                route_after = marked,
                route_after_delay_ms = marked?.let { it.at_ms - acceptAt },
                new_pickup_stops = marked?.stops?.count { it.kind == "PICKUP" && it.status == "NEW" },
                new_dropoff_stops_by_shape = marked?.stops?.count { it.kind == "DROPOFF" && it.status == "NEW" },
                ambiguous_dropoff_stops = marked?.stops?.count { it.kind == "DROPOFF" && it.status == "AMBIGUOUS" },
                completions_between_before_and_accept = between,
                carried_target_shift_min = shift,
                route_changed_for_other_reasons = other,
                notes = notes,
            )
        }
        return Result(ctx, facts)
    }

    private fun List<MarkerMinutes>.dedupe() = fold(listOf<MarkerMinutes>()) { acc, m -> if (acc.lastOrNull()?.minutes == m.minutes) acc else acc + m }

    /** Route snapshots → changes (consecutive identical snapshots collapse). */
    fun changes(snaps: List<RouteSnapshot>): List<RouteChange> {
        val out = mutableListOf<RouteChange>()
        var prev: RouteSnapshot? = null
        for (s in snaps) {
            if (prev != null && prev.stops == s.stops) continue
            val p = prev?.stops.orEmpty().groupingBy { it.kind to it.shape }.eachCount()
            val n = s.stops.groupingBy { it.kind to it.shape }.eachCount()
            out += RouteChange(
                at_ms = s.at_ms,
                active_pickups = s.stops.count { it.kind == "PICKUP" },
                active_dropoffs = s.stops.count { it.kind == "DROPOFF" },
                added_stops = n.entries.sumOf { (k, v) -> (v - (p[k] ?: 0)).coerceAtLeast(0) },
                removed_stops = p.entries.sumOf { (k, v) -> (v - (n[k] ?: 0)).coerceAtLeast(0) },
                stops = s.stops,
            )
            prev = s
        }
        return out
    }

    /**
     * Marks each stop of the route after acceptance. A shape absent before → NEW; present once before and
     * once after → CARRIED (by shape only); repeated shapes cannot be told apart → AMBIGUOUS. With nothing
     * active before (IDLE), every stop is NEW.
     */
    fun mark(before: RouteSnapshot?, after: RouteSnapshot): RouteSnapshot {
        val prev = before?.stops.orEmpty().groupingBy { it.kind to it.shape }.eachCount()
        val now = after.stops.groupingBy { it.kind to it.shape }.eachCount()
        return after.copy(stops = after.stops.map { s ->
            val k = s.kind to s.shape
            s.copy(status = when {
                (prev[k] ?: 0) == 0 -> "NEW"
                prev[k] == 1 && now[k] == 1 -> "CARRIED"
                else -> "AMBIGUOUS"
            })
        })
    }

    private fun nearest(points: List<GpsPoint>, atMs: Long?): GpsPoint? =
        atMs?.let { at -> points.filter { kotlin.math.abs(it.atMs - at) <= 120_000 }.minByOrNull { kotlin.math.abs(it.atMs - at) } }

    data class Gps(
        val samples: Int,
        val maxGapMs: Long,
        val coverage: Double,
        val pathKm: Double,
        val speedKm: Double?,
        val reliable: Boolean,
        val reason: String?,
        /** OBSERVED: time whose sample showed GPS speed < 1 m/s / ≥ 1 m/s / no speed (intervals capped at COVERED_GAP_MS). */
        val stationaryMs: Long = 0,
        val movingMs: Long = 0,
        val unknownMotionMs: Long = 0,
    )

    /** A sample at or below this speed counts as stationary (red lights, waiting, walking slowly all look alike). */
    const val STATIONARY_MPS = 1.0f

    fun gps(points: List<GpsPoint>, fromMs: Long, toMs: Long): Gps {
        val inside = points.filter { it.atMs in fromMs..toMs }
        val span = (toMs - fromMs).coerceAtLeast(1)
        val edges = listOf(fromMs) + inside.map { it.atMs } + listOf(toMs)
        val gaps = edges.zipWithNext { a, b -> b - a }
        val maxGap = gaps.maxOrNull() ?: span
        val uncovered = gaps.sumOf { (it - COVERED_GAP_MS).coerceAtLeast(0) }
        // Jitter guard: with a precise track, standing still produces small random jumps; a step under
        // 25 m with both ends slower than 0.5 m/s is noise, not distance.
        val path = inside.zipWithNext { a, b ->
            val d = km(a.lat, a.lon, b.lat, b.lon)
            if (d < 0.025 && (a.speedMps ?: 0f) < 0.5f && (b.speedMps ?: 0f) < 0.5f) 0.0 else d
        }.sum()
        val speed = if (inside.any { it.speedMps != null }) inside.zipWithNext { a, b -> (a.speedMps ?: 0f) * minOf(b.atMs - a.atMs, COVERED_GAP_MS) / 1000.0 / 1000.0 }.sum() else null
        val reason = when {
            inside.size < 3 -> "only ${inside.size} fixes"
            maxGap > RELIABLE_MAX_GAP_MS -> "gap of ${maxGap / 1000} s"
            else -> null
        }
        var still = 0L
        var moving = 0L
        var unknown = 0L
        for ((a, b) in inside.zipWithNext()) {
            val dt = minOf(b.atMs - a.atMs, COVERED_GAP_MS)
            when {
                a.speedMps == null -> unknown += dt
                a.speedMps < STATIONARY_MPS -> still += dt
                else -> moving += dt
            }
        }
        return Gps(inside.size, maxGap, 1.0 - uncovered.toDouble() / span, path, speed, reason == null, reason, still, moving, unknown)
    }

    /** Great-circle distance in km. */
    fun kmBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double = km(aLat, aLon, bLat, bLon)

    private fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).let { it * it }
        return 2 * 6371.0 * asin(sqrt(h))
    }
}
