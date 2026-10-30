package dev.yk.offercollector.core

import java.time.Instant
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln

/**
 * Area demand density: offers per hour of availability, per ~500 m area (the COURIER's position when
 * the offer appeared — not the restaurant's) and hour window. Pure; the web engine's explore.ts rules:
 *
 *  - Every moment of a shift gets ONE time state, from the latest state marker (≤ [STATE_STALE_MS] old):
 *      VERIFIED  AVAILABLE / OFFER_PRESENTED with evidence OBSERVED
 *      ONLINE    same, but ESTIMATED (online, not verified)
 *      DELIVERY  inside a detected trip, or a delivery screen
 *      OFFLINE   OFFLINE marker
 *      UNKNOWN   nothing recent
 *  - …and one area: the latest location sample ≤ [LOC_STALE_MS] old (no fix → no area).
 *  - Rate = offers ÷ VERIFIED hours (needs ≥ [MIN_RATE_HOURS]); otherwise an ESTIMATE over verified +
 *    unverified online time (labelled), otherwise "no data". Never offers ÷ GPS time.
 */
object AreaDensity {
    const val STATE_STALE_MS = 10 * 60_000L
    const val LOC_STALE_MS = 6 * 60_000L
    const val MIN_RATE_HOURS = 0.5
    const val MIN_OFFERS = 5
    const val MIN_HOURS = 1.0
    const val MIN_SHIFTS = 2
    const val FAIR_OFFERS = 20
    const val FAIR_HOURS = 3.0
    const val FAIR_SHIFTS = 5
    val THRESHOLDS_SHEKEL = listOf(30, 35, 40, 50)

    enum class TimeState { VERIFIED, ONLINE, DELIVERY, OFFLINE, UNKNOWN }
    enum class Quality { NONE, LOW, FAIR }
    enum class RateKind { VERIFIED, ESTIMATE, NONE }

    data class Shift(val id: String, val startMs: Long, val endMs: Long)
    data class Sample(val shiftId: String, val atMs: Long, val cellId: String?, val centerLat: Double?, val centerLon: Double?, val accuracyM: Int?)
    data class StateMark(val atMs: Long, val state: String, val evidence: String)
    data class Trip(val startMs: Long, val endMs: Long?)
    /** cellId = courier's area at the offer (from OfferLocations); null when unknown. */
    data class Offer(val firstSeenMs: Long, val amountAgorot: Long, val cellId: String?, val pickupNames: List<String>)

    data class Window(val fromHour: Int, val toHour: Int, val fromDate: LocalDate? = null) {
        fun contains(ms: Long): Boolean {
            val z = Instant.ofEpochMilli(ms).atZone(OfferAnalytics.ZONE)
            if (fromDate != null && z.toLocalDate().isBefore(fromDate)) return false
            if (fromHour == 0 && toHour == 24) return true
            val h = z.hour
            return if (fromHour < toHour) h in fromHour until toHour else h >= fromHour || h < toHour
        }
        /** "18:00–20:00", isolated as a left-to-right run so it doesn't flip inside Hebrew text. */
        val label: String get() = if (fromHour == 0 && toHour == 24) "כל היום" else "\u2066%02d:00–%02d:00\u2069".format(fromHour, toHour % 24)
    }

    data class Piece(val shiftId: String, val cellId: String?, val state: TimeState, val startMs: Long, val endMs: Long)

    data class Rate(val kind: RateKind, val perHour: Double?, val low: Double?, val high: Double?, val basisHours: Double)

    data class Area(
        val cellId: String,
        val centerLat: Double?,
        val centerLon: Double?,
        val offers: Int,
        val meanAgorot: Double?,
        val medianAgorot: Double?,
        val shareAbove: Map<Int, Double?>,
        val timeMs: Map<TimeState, Long>,
        val rate: Rate,
        val shifts: Int,
        val quality: Quality,
        val limits: List<String>,
        val restaurants: List<Pair<String, Int>>,
        val medianAccuracyM: Int?,
    ) {
        val verifiedMs get() = timeMs[TimeState.VERIFIED] ?: 0
        val onlineUnverifiedMs get() = timeMs[TimeState.ONLINE] ?: 0
    }

    data class Result(
        val window: Window,
        /** Highest verified rate first, then estimates, then areas without a rate (never by offer count). */
        val areas: List<Area>,
        val totalsMs: Map<TimeState, Long>,
        val shifts: Int,
        val offersInWindow: Int,
        val offersWithoutArea: Int,
        val toMeasure: List<String>,
    )

    // ---- timeline ----

    private fun category(state: String) = when (state) {
        "AVAILABLE", "OFFER_PRESENTED" -> "AVAILABLE"
        else -> state
    }

    /**
     * The state at [at]: the latest recent mark decides the category; it counts as VERIFIED when an OBSERVED
     * mark of that same category was seen since the last conflicting mark (a weaker signal that agrees —
     * e.g. Wolt's background notification — must not downgrade a verified screen state).
     */
    private fun stateAt(stateful: List<StateMark>, at: Long): TimeState {
        val recent = stateful.filter { it.atMs <= at && at - it.atMs < STATE_STALE_MS }
        val last = recent.lastOrNull() ?: return TimeState.UNKNOWN
        val cat = category(last.state)
        val sameRun = recent.takeLastWhile { category(it.state) == cat }
        val best = sameRun.lastOrNull { it.evidence == "OBSERVED" } ?: last
        return stateFrom(best)
    }

    private fun stateFrom(m: StateMark?): TimeState = when {
        m == null -> TimeState.UNKNOWN
        m.state == "DELIVERY_ACTIVE" -> TimeState.DELIVERY
        m.state == "OFFLINE" -> TimeState.OFFLINE
        m.state == "AVAILABLE" || m.state == "OFFER_PRESENTED" -> if (m.evidence == "OBSERVED") TimeState.VERIFIED else TimeState.ONLINE
        else -> TimeState.UNKNOWN
    }

    fun pieces(shifts: List<Shift>, samples: List<Sample>, marks: List<StateMark>, trips: List<Trip>): List<Piece> {
        val stateful = marks.filter { it.state in setOf("AVAILABLE", "OFFER_PRESENTED", "DELIVERY_ACTIVE", "OFFLINE") }.sortedBy { it.atMs }
        val out = mutableListOf<Piece>()
        for (sh in shifts) {
            if (sh.endMs <= sh.startMs) continue
            val own = samples.filter { it.shiftId == sh.id && it.atMs in sh.startMs..sh.endMs }.sortedBy { it.atMs }
            val cuts = sortedSetOf(sh.startMs, sh.endMs)
            fun add(t: Long) { if (t > sh.startMs && t < sh.endMs) cuts += t }
            own.forEach { add(it.atMs); add(it.atMs + LOC_STALE_MS) }
            stateful.forEach { add(it.atMs); add(it.atMs + STATE_STALE_MS) }
            trips.forEach { add(it.startMs); it.endMs?.let(::add) }
            var h = Instant.ofEpochMilli(sh.startMs).atZone(OfferAnalytics.ZONE).withMinute(0).withSecond(0).withNano(0).plusHours(1)
            while (h.toInstant().toEpochMilli() < sh.endMs) { add(h.toInstant().toEpochMilli()); h = h.plusHours(1) }
            val pts = cuts.toList()
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val loc = own.lastOrNull { it.atMs <= a }?.takeIf { a - it.atMs < LOC_STALE_MS }
                val inTrip = trips.any { a >= it.startMs && it.endMs != null && a < it.endMs }
                out += Piece(sh.id, loc?.cellId, if (inTrip) TimeState.DELIVERY else stateAt(stateful, a), a, b)
            }
        }
        return out
    }

    // ---- statistics ----

    private fun poissonCdf(k: Int, lambda: Double): Double {
        if (lambda <= 0) return 1.0
        var term = exp(-lambda)
        var sum = term
        for (i in 1..k) { term *= lambda / i; sum += term }
        return sum.coerceIn(0.0, 1.0)
    }

    /** Exact (Garwood) 95% interval for a Poisson count. */
    fun poissonInterval(k: Int): Pair<Double, Double> {
        fun solve(f: (Double) -> Double, target: Double): Double { // f decreasing in λ
            var lo = 0.0
            var hi = k + 10.0 * (1 + ln(1.0 + k)) + 20
            repeat(100) { val mid = (lo + hi) / 2; if (f(mid) > target) lo = mid else hi = mid }
            return (lo + hi) / 2
        }
        val upper = solve({ poissonCdf(k, it) }, 0.025)
        val lower = if (k == 0) 0.0 else solve({ poissonCdf(k - 1, it) }, 0.975)
        return lower to upper
    }

    private fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun rateFor(offers: Int, verifiedMs: Long, onlineMs: Long): Rate {
        val v = verifiedMs / 3_600_000.0
        val o = (verifiedMs + onlineMs) / 3_600_000.0
        return when {
            v >= MIN_RATE_HOURS -> poissonInterval(offers).let { (lo, hi) -> Rate(RateKind.VERIFIED, offers / v, lo / v, hi / v, v) }
            o >= MIN_RATE_HOURS -> poissonInterval(offers).let { (lo, hi) -> Rate(RateKind.ESTIMATE, offers / o, lo / o, hi / o, o) }
            else -> Rate(RateKind.NONE, null, null, null, v)
        }
    }

    private fun quality(offers: Int, verifiedH: Double, shifts: Int): Pair<Quality, List<String>> {
        val limits = mutableListOf<String>()
        if (offers < MIN_OFFERS) limits += "רק $offers הצעות (צריך לפחות $MIN_OFFERS)"
        if (verifiedH < MIN_HOURS) limits += if (verifiedH == 0.0) "אין זמן זמינות מאומת" else "רק ${Math.round(verifiedH * 60)} דק׳ זמינות מאומתת (צריך לפחות שעה)"
        if (shifts < MIN_SHIFTS) limits += "נמדד במשמרת אחת בלבד"
        if (limits.isNotEmpty()) return Quality.NONE to limits
        val fair = offers >= FAIR_OFFERS && verifiedH >= FAIR_HOURS && shifts >= FAIR_SHIFTS
        if (!fair) limits += "ראשוני — פחות מ-$FAIR_SHIFTS משמרות או $FAIR_HOURS ש׳ מאומתות"
        return (if (fair) Quality.FAIR else Quality.LOW) to limits
    }

    // ---- per window ----

    fun analyze(
        shifts: List<Shift>, samples: List<Sample>, marks: List<StateMark>, trips: List<Trip>, offers: List<Offer>, window: Window,
    ): Result {
        val all = pieces(shifts, samples, marks, trips).filter { window.contains(it.startMs) }
        val inShift = { ms: Long -> shifts.any { ms in it.startMs..it.endMs } }
        val shiftOf = { ms: Long -> shifts.firstOrNull { ms in it.startMs..it.endMs }?.id }
        val wOffers = offers.filter { window.contains(it.firstSeenMs) && inShift(it.firstSeenMs) }
        val centers = samples.filter { it.cellId != null }.associate { it.cellId!! to (it.centerLat to it.centerLon) }
        val cells = (all.mapNotNull { it.cellId } + wOffers.mapNotNull { it.cellId }).distinct()

        val areas = cells.map { id ->
            val ps = all.filter { it.cellId == id }
            val os = wOffers.filter { it.cellId == id }
            val time = TimeState.entries.associateWith { st -> ps.filter { it.state == st }.sumOf { it.endMs - it.startMs } }
            val amounts = os.map { it.amountAgorot.toDouble() }
            val shiftIds = (ps.map { it.shiftId } + os.mapNotNull { shiftOf(it.firstSeenMs) }).toSet()
            val (q, limits) = quality(os.size, (time[TimeState.VERIFIED] ?: 0) / 3_600_000.0, shiftIds.size)
            Area(
                cellId = id,
                centerLat = centers[id]?.first,
                centerLon = centers[id]?.second,
                offers = os.size,
                meanAgorot = amounts.takeIf { it.isNotEmpty() }?.average(),
                medianAgorot = median(amounts),
                shareAbove = THRESHOLDS_SHEKEL.associateWith { t -> if (amounts.isEmpty()) null else amounts.count { it >= t * 100 }.toDouble() / amounts.size },
                timeMs = time,
                rate = rateFor(os.size, time[TimeState.VERIFIED] ?: 0, time[TimeState.ONLINE] ?: 0),
                shifts = shiftIds.size,
                quality = q,
                limits = limits,
                restaurants = os.flatMap { it.pickupNames }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key to it.value },
                medianAccuracyM = median(samples.filter { it.cellId == id && it.accuracyM != null }.map { it.accuracyM!!.toDouble() })?.toInt(),
            )
        }.sortedWith(
            compareBy<Area> { it.rate.kind.ordinal }.thenByDescending { it.rate.perHour ?: -1.0 }.thenBy { it.cellId },
        )

        val shiftCount = all.map { it.shiftId }.toSet().size
        val toMeasure = buildList {
            if (shiftCount < FAIR_SHIFTS) add("${window.label}: נמדד ב-$shiftCount משמרות — צריך לפחות $FAIR_SHIFTS כדי להשוות אזורים.")
            if (areas.none { it.rate.kind == RateKind.VERIFIED }) add("${window.label}: אין עדיין חצי שעה של זמינות מאומתת באף אזור.")
            areas.filter { it.quality == Quality.NONE }.forEach { add("@${it.cellId}|${it.limits.first()}") }
        }
        return Result(
            window = window,
            areas = areas,
            totalsMs = TimeState.entries.associateWith { st -> all.filter { it.state == st }.sumOf { it.endMs - it.startMs } },
            shifts = shiftCount,
            offersInWindow = wOffers.size,
            offersWithoutArea = wOffers.count { it.cellId == null },
            toMeasure = toMeasure,
        )
    }

    /** Hour windows offered in the UI, and how many shifts each has data for — to plan the next measurements. */
    val PRESET_WINDOWS = listOf(Window(0, 24), Window(18, 20), Window(20, 22), Window(18, 22), Window(10, 12), Window(12, 14), Window(14, 16), Window(16, 18))

    fun shiftsPerWindow(shifts: List<Shift>, samples: List<Sample>, marks: List<StateMark>, trips: List<Trip>): Map<Window, Int> {
        val ps = pieces(shifts, samples, marks, trips)
        return PRESET_WINDOWS.associateWith { w -> ps.filter { w.contains(it.startMs) }.map { it.shiftId }.toSet().size }
    }
}

/** Short, stable label for an area without a user-given name, e.g. "K7Q" (same scheme as the web engine). */
fun shortAreaCode(cellId: String): String {
    var h = 2166136261L
    for (ch in cellId) h = ((h xor ch.code.toLong()) * 16777619L) and 0xFFFFFFFFL
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (0 until 3).map { i -> alphabet[((h ushr (i * 5)) % alphabet.length).toInt()] }.joinToString("")
}
