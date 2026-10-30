package dev.yk.offercollector.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime

/**
 * Validates what Wolt's route numbers mean against OBSERVED completions (real shift 29.10.2026).
 *
 * WOLT_DISPLAY values used, never treated as facts:
 *  - route screen: "[MIN n]" right after a stop = minutes until that stop's TARGET clock time
 *    (snapshot time + n stays constant while the courier waits — not a live travel ETA);
 *  - drop-off screen: clock-time pairs per active order, and the map marker's minutes (|now − target|).
 *
 * Stop identity is only "reliable" when it comes from the drop-off screen itself: the order whose
 * clock-time pair disappears across the slide is the one completed. Otherwise the identity is UNKNOWN.
 */
object EtaValidation {
    const val VERSION = "eta-validation-1"
    /** Two targets within this are the same target: screen clocks are HH:MM and route minutes are whole (±1 min each). */
    const val SAME_TARGET_MS = 120_000L

    @Serializable
    data class Check(
        /** OBSERVED: the drop-off slide (completion) time. */
        val completed_at_ms: Long,
        /**
         * TIME_PAIR_REMOVED (reliable: its clock-time pair vanished from the drop-off screen) |
         * SINGLE_ORDER_ON_SCREEN (reliable) | TARGET_GONE_FROM_ROUTE (its target left the route list; the
         * other targets stayed — reliable only if no other change happened in between) | UNKNOWN
         */
        val identity: String,
        /** WOLT_DISPLAY: the completed order's clock times as shown on the drop-off screen. */
        val order_times_ms: List<Long> = emptyList(),
        /** The later of the pair — matches the route's target for that stop when the route was seen. */
        val target_at_ms: Long? = null,
        /** Route snapshots that showed a drop-off stop with this target (±90 s). */
        val route_first_seen_at_ms: Long? = null,
        val route_target_first_ms: Long? = null,
        val route_target_last_ms: Long? = null,
        /** Errors in minutes: completion − target (positive = later than Wolt's target). */
        val error_vs_drop_screen_min: Double? = null,
        val error_vs_first_route_target_min: Double? = null,
        val error_vs_last_route_target_min: Double? = null,
        /** Minutes between the first time the stop's target was seen and completion (how far ahead it was "predicted"). */
        val lead_min: Double? = null,
        val notes: List<String> = emptyList(),
    )

    @Serializable
    data class Semantics(
        /** Pairs of consecutive route frames (≥30 s apart, same stops) comparing each stop's implied target. */
        val target_pairs_compared: Int,
        val target_pairs_stable: Int,
        /** Frames whose targets changed and changed back within 15 s (a stale render). */
        val stale_frames: Int,
        /** Drop-off screens where marker minutes = |now − one of the shown targets| (±1). */
        val marker_checks: Int,
        val marker_matches: Int,
        val conclusions: List<String>,
    )

    @Serializable
    data class Result(val checks: List<Check>, val semantics: Semantics)

    // ---- reading snapshots ----

    data class Stop(val position: Int, val kind: String, val shape: String, val etaMinutes: Int?, val targetMs: Long?)
    data class Route(val atMs: Long, val stops: List<Stop>)
    data class DropScreen(val atMs: Long, val times: List<Long>, val markerMinutes: Int?, val slide: Boolean)

    private val minRx = Regex("""\[MIN (\d+)]""")
    private val timeRx = Regex("""\[TIME (\d{1,2}):(\d{2})]""")

    /** Ordered stops with the minutes shown right after each stop (WOLT_DISPLAY) and the implied target clock. */
    fun route(f: CandidateFields, atMs: Long): Route? {
        val out = mutableListOf<Stop>()
        var label = ""
        for (n in f.nodes) {
            when {
                n.viewId == "TimelineItemV3.Label" -> label = n.redacted
                n.viewId == "TimelineItemV3.Description" ->
                    out += Stop(out.size, if (TokenKind.LONG_NUMBER in n.kinds) "PICKUP" else "DROPOFF", "$label @ ${n.redacted}", null, null)
                n.viewId == null && TokenKind.DURATION_MIN in n.kinds && out.isNotEmpty() && out.last().etaMinutes == null -> {
                    val m = minRx.find(n.redacted)?.groupValues?.get(1)?.toInt() ?: continue
                    out[out.lastIndex] = out.last().copy(etaMinutes = m, targetMs = atMs + m * 60_000L)
                }
            }
        }
        return if (out.isEmpty()) null else Route(atMs, out)
    }

    /** "HH:MM" shown on screen → the nearest such clock time to [atMs] (local zone). */
    fun clock(h: Int, m: Int, atMs: Long): Long {
        val zone = OfferAnalytics.ZONE
        val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        return listOf(-1L, 0L, 1L).map { d -> day.plusDays(d).atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli() }
            .minBy { kotlin.math.abs(it - atMs) }
    }

    fun dropScreen(f: CandidateFields, atMs: Long): DropScreen? {
        if (f.nodes.none { it.viewId == "CompleteDropoff" || it.viewId == "DropoffBottomSheet.RestaurantName" }) return null
        val times = f.nodes.filter { it.viewId == null && TokenKind.CLOCK_TIME in it.kinds }
            .flatMap { n -> timeRx.findAll(n.redacted).map { clock(it.groupValues[1].toInt(), it.groupValues[2].toInt(), atMs) } }
        val marker = f.nodes.firstOrNull { it.viewId == "TaskMarker.PopoutLabel" }?.let { minRx.find(it.redacted)?.groupValues?.get(1)?.toInt() }
        return DropScreen(atMs, times, marker, ChainFacts.isSlide(f))
    }

    // ---- validation ----

    fun build(screens: List<OfferScreen>): Result {
        val sorted = screens.sortedBy { it.observedAtMs }
        val routes = sorted.mapNotNull { route(it.fields, it.observedAtMs) }
        val drops = sorted.mapNotNull { dropScreen(it.fields, it.observedAtMs) }
        val stale = staleFrames(routes)
        val good = routes.filter { it.atMs !in stale }
        val slides = ChainFacts.slides(sorted)

        // Route stops (snapshot time, position) already matched to an earlier completion.
        val claimed = HashSet<Pair<Long, Int>>()
        val checks = slides.map { s ->
            val notes = mutableListOf<String>()
            val pre = drops.lastOrNull { it.atMs <= s && it.times.isNotEmpty() }
            val post = drops.firstOrNull { it.atMs > s && !it.slide && it.times.isNotEmpty() && pre != null && it.times.size < pre.times.size }?.takeIf { it.atMs - s <= 10 * 60_000 }
            val routeBefore = good.lastOrNull { it.atMs <= s }
            val routeAfter = good.firstOrNull { it.atMs > s }?.takeIf { it.atMs - s <= 10 * 60_000 }
            // Drop-off targets shown on this screen that were in the route before and are gone from the route after.
            val gone = if (pre != null && routeBefore != null && routeAfter != null) {
                val shown = pre.times.filter { t -> routeBefore.stops.any { it.kind == "DROPOFF" && it.targetMs != null && kotlin.math.abs(it.targetMs - t) <= SAME_TARGET_MS } }
                shown.filter { t -> routeAfter.stops.none { it.kind == "DROPOFF" && it.targetMs != null && kotlin.math.abs(it.targetMs - t) <= SAME_TARGET_MS } }
            } else emptyList()
            var identity = "UNKNOWN"
            var pair: List<Long> = emptyList()
            when {
                pre == null -> notes += "no drop-off screen with clock times before the slide"
                post != null -> {
                    val left = post.times.toMutableList()
                    val removed = pre.times.filter { t -> !left.remove(t) }
                    if (removed.size == 2) { identity = "TIME_PAIR_REMOVED"; pair = removed }
                    else notes += "${removed.size} times disappeared across the slide (expected 2)"
                }
                pre.times.size == 2 -> { identity = "SINGLE_ORDER_ON_SCREEN"; pair = pre.times }
                gone.size == 1 -> {
                    identity = "TARGET_GONE_FROM_ROUTE"; pair = gone
                    val between = slides.count { it != s && it > routeBefore!!.atMs && it < routeAfter!!.atMs }
                    if (between > 0) notes += "$between other completion(s) between the two routes — identity less certain"
                }
                else -> notes += "several orders on screen and no later drop-off screen to tell which one was completed"
            }
            val target = pair.maxOrNull()
            // Per route snapshot: the closest drop-off stop to this target that no earlier completion has claimed.
            val matched = target?.let { tg ->
                good.filter { it.atMs <= s }.mapNotNull { r ->
                    r.stops.filter { it.kind == "DROPOFF" && it.targetMs != null && (r.atMs to it.position) !in claimed && kotlin.math.abs(it.targetMs - tg) <= SAME_TARGET_MS }
                        .minByOrNull { kotlin.math.abs(it.targetMs!! - tg) }?.let { r.atMs to it }
                }
            }.orEmpty()
            claimed += matched.map { (at, stop) -> at to stop.position }
            val seen = matched.map { (at, stop) -> at to stop.targetMs!! }
            if (target != null && seen.isEmpty()) notes += "target not seen on any route screen before completion (as an unclaimed stop)"
            fun err(x: Long?) = x?.let { (s - it) / 60_000.0 }
            Check(
                completed_at_ms = s,
                identity = identity,
                order_times_ms = pair.sorted(),
                target_at_ms = target,
                route_first_seen_at_ms = seen.firstOrNull()?.first,
                route_target_first_ms = seen.firstOrNull()?.second,
                route_target_last_ms = seen.lastOrNull()?.second,
                error_vs_drop_screen_min = err(target),
                error_vs_first_route_target_min = err(seen.firstOrNull()?.second),
                error_vs_last_route_target_min = err(seen.lastOrNull()?.second),
                lead_min = seen.firstOrNull()?.let { (s - it.first) / 60_000.0 },
                notes = notes,
            )
        }
        return Result(checks, semantics(good, stale.size, drops, checks))
    }

    /** A frame whose targets differ (≥3 min) from both neighbours that agree with each other, within 15 s. */
    fun staleFrames(routes: List<Route>): Set<Long> {
        val out = HashSet<Long>()
        fun same(a: Route, b: Route) = a.stops.map { it.kind to it.shape } == b.stops.map { it.kind to it.shape }
        fun differs(a: Route, b: Route) = a.stops.zip(b.stops).any { (x, y) -> x.targetMs != null && y.targetMs != null && kotlin.math.abs(x.targetMs - y.targetMs) >= 3 * 60_000 }
        for (i in routes.indices) {
            val next = routes.getOrNull(i + 1) ?: continue
            if (next.atMs - routes[i].atMs <= 15_000 && same(routes[i], next) && differs(routes[i], next)) out += routes[i].atMs
        }
        return out
    }

    private fun semantics(routes: List<Route>, stale: Int, drops: List<DropScreen>, checks: List<Check>): Semantics {
        var compared = 0
        var stable = 0
        for ((a, b) in routes.zipWithNext()) {
            if (b.atMs - a.atMs < 30_000 || a.stops.map { it.kind to it.shape } != b.stops.map { it.kind to it.shape }) continue
            for ((x, y) in a.stops.zip(b.stops)) {
                if (x.targetMs == null || y.targetMs == null) continue
                compared++
                if (kotlin.math.abs(x.targetMs - y.targetMs) <= SAME_TARGET_MS) stable++
            }
        }
        var mChecks = 0
        var mMatch = 0
        for (d in drops) {
            val m = d.markerMinutes ?: continue
            if (d.times.isEmpty()) continue
            mChecks++
            if (d.times.any { kotlin.math.abs(kotlin.math.abs(d.atMs - it) / 60_000.0 - m) <= 1.0 }) mMatch++
        }
        val errs = checks.mapNotNull { it.error_vs_drop_screen_min }
        val conclusions = buildList {
            add("Route minutes are per stop (each follows one stop in the list); stops already reached show none.")
            if (compared > 0) add("Snapshot time + minutes stayed within ±2 min in $stable of $compared comparisons ≥30 s apart → a TARGET clock time per stop, not a live travel ETA from the courier's position.")
            if (stale > 0) add("$stale frame(s) showed old minutes right after the screen resumed — excluded.")
            if (mChecks > 0) add("Drop-off map marker = |now − a shown target| in $mMatch of $mChecks screens (counts down to the target, then up while late).")
            if (errs.isNotEmpty()) add("Completions vs target: ${errs.size} orders with a reliable identity; error ${"%.1f".format(java.util.Locale.US, errs.min())} to ${"%.1f".format(java.util.Locale.US, errs.max())} min (positive = later than the target).")
        }
        return Semantics(compared, stable, stale, mChecks, mMatch, conclusions)
    }
}
