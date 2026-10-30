package dev.yk.offercollector.core

import java.time.DayOfWeek
import java.time.Instant

/**
 * Before-shift plan and "what works", from the courier's own history. Pure. Every item says how much it can be
 * trusted; nothing is ranked or called better without repeated shifts. Never shown during a shift.
 */
object Insights {
    enum class Label { MEASURED, REPEATED, TESTED, HYPOTHESIS, NOT_ENOUGH_DATA }

    /** A work shift counts for planning when it lasted at least this long (short test sessions are left out). */
    const val MIN_WORK_SHIFT_MS = 60 * 60_000L
    /** A pattern counts as REPEATED once it held on this many shifts. */
    const val MIN_SHIFTS_REPEATED = 5

    data class OfferDecision(
        val seenMs: Long,
        val amountAgorot: Long,
        val distanceKm: Double?,
        /** IDLE | ACTIVE_ROUTE | UNKNOWN at the moment the card appeared. */
        val context: String,
        /** ACCEPTED | DECLINED | UNKNOWN */
        val decision: String,
    )

    data class History(
        val shifts: List<ShiftFacts.Shift>,
        val chains: List<ChainFacts.ChainEconomicsFacts>,
        val offers: List<OfferDecision>,
        val gps: List<ChainFacts.GpsPoint>,
        val eta: EtaValidation.Result?,
        val reviews: List<ShiftReview.Review>,
    ) {
        val workReviews: List<ShiftReview.Review> get() = reviews.filter { it.endMs - it.startMs >= MIN_WORK_SHIFT_MS }
    }

    // ---- when to work ----

    data class TimeWindow(val label: String, val shifts: Int, val netPerHourAgorot: List<Long>, val evidence: Label) {
        val mean: Long? get() = netPerHourAgorot.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it) }
        val low: Long? get() = netPerHourAgorot.minOrNull()
        val high: Long? get() = netPerHourAgorot.maxOrNull()
    }

    private val days = mapOf(
        DayOfWeek.SUNDAY to "א׳", DayOfWeek.MONDAY to "ב׳", DayOfWeek.TUESDAY to "ג׳", DayOfWeek.WEDNESDAY to "ד׳",
        DayOfWeek.THURSDAY to "ה׳", DayOfWeek.FRIDAY to "ו׳", DayOfWeek.SATURDAY to "ש׳",
    )

    fun hourBand(h: Int) = when {
        h < 6 -> "00–06"
        h < 11 -> "06–11"
        h < 15 -> "11–15"
        h < 18 -> "15–18"
        h < 21 -> "18–21"
        else -> "21–24"
    }

    /** Net per shift hour grouped by weekday and start-hour band. */
    fun timeWindows(h: History): List<TimeWindow> =
        h.workReviews.filter { it.netPerShiftHourAgorot != null }
            .groupBy { r ->
                val z = Instant.ofEpochMilli(r.startMs).atZone(OfferAnalytics.ZONE)
                // Hour range isolated (LRI…PDI) so it reads left-to-right inside Hebrew text.
                "${days.getValue(z.dayOfWeek)} \u2066${hourBand(z.hour)}\u2069"
            }
            .map { (k, rs) ->
                TimeWindow(k, rs.size, rs.map { it.netPerShiftHourAgorot!!.mid }, if (rs.size >= MIN_SHIFTS_REPEATED) Label.REPEATED else Label.HYPOTHESIS)
            }
            .sortedByDescending { it.mean ?: Long.MIN_VALUE }

    // ---- where to wait ----

    data class WaitSpot(val cellId: String, val waits: Int, val shifts: Int, val medianMinutes: Double, val evidence: Label)

    /** A wait = from a shift start or a chain end until the next offer card; placed at the GPS position where it began. */
    fun waitSpots(h: History): List<WaitSpot> {
        val waits = mutableListOf<Triple<String, String, Double>>() // cell, shift, minutes
        val gps = h.gps.sortedBy { it.atMs }
        for (s in h.shifts.filter { it.ended_at_ms - it.started_at_ms >= MIN_WORK_SHIFT_MS }) {
            val starts = listOf(s.started_at_ms) + h.chains.filter { it.start_latest_ms in s.started_at_ms..s.ended_at_ms }.mapNotNull { it.end_latest_ms }
            for (from in starts) {
                val next = h.offers.filter { it.seenMs > from && it.seenMs <= s.ended_at_ms }.minOfOrNull { it.seenMs } ?: continue
                val at = gps.filter { kotlin.math.abs(it.atMs - from) <= 120_000 }.minByOrNull { kotlin.math.abs(it.atMs - from) } ?: continue
                waits += Triple(at.cellId, s.shift_id, (next - from) / 60_000.0)
            }
        }
        return waits.groupBy { it.first }.map { (cell, ws) ->
            val shifts = ws.map { it.second }.toSet().size
            val sorted = ws.map { it.third }.sorted()
            WaitSpot(cell, ws.size, shifts, sorted[sorted.size / 2], if (ws.size >= 5 && shifts >= 2) Label.REPEATED else Label.HYPOTHESIS)
        }.sortedByDescending { it.waits }
    }

    // ---- which offers to take: the courier's own rules, checked against history ----

    data class RuleCheck(val rule: String, val offers: Int, val agree: Int, val wouldDeclineAccepted: Int, val wouldAcceptDeclined: Int)

    fun ruleCheck(h: History, acceptAddedOffers: Boolean?, minPriceAgorot: Long?): RuleCheck? {
        if (acceptAddedOffers == null && minPriceAgorot == null) return null
        fun accepts(o: OfferDecision) =
            (minPriceAgorot == null || o.amountAgorot >= minPriceAgorot) && (acceptAddedOffers != false || o.context != "ACTIVE_ROUTE")
        val known = h.offers.filter { it.decision != "UNKNOWN" && inWorkShift(h, it.seenMs) }
        val parts = buildList {
            if (minPriceAgorot != null) add("מינימום ₪${formatAgorot(minPriceAgorot)}")
            if (acceptAddedOffers == false) add("לא לקבל הצעה נוספת בזמן משלוח")
            if (acceptAddedOffers == true) add("לקבל הצעה נוספת בזמן משלוח")
        }
        return RuleCheck(
            rule = parts.joinToString(" · "),
            offers = known.size,
            agree = known.count { (it.decision == "ACCEPTED") == accepts(it) },
            wouldDeclineAccepted = known.count { it.decision == "ACCEPTED" && !accepts(it) },
            wouldAcceptDeclined = known.count { it.decision == "DECLINED" && accepts(it) },
        )
    }

    private fun inWorkShift(h: History, t: Long) = h.shifts.any { it.ended_at_ms - it.started_at_ms >= MIN_WORK_SHIFT_MS && t in it.started_at_ms..it.ended_at_ms }

    // ---- when to stop: revenue by hour of the shift ----

    data class ShiftHour(val index: Int, val shifts: Int, val revenuePerHourAgorot: List<Long>, val evidence: Label) {
        val mean: Long get() = Math.round(revenuePerHourAgorot.average())
    }

    /**
     * Accepted-offer revenue by hour of the shift, before vehicle cost. Each accepted offer counts in the hour its card
     * appeared — an offer added to an active route counts then, not when its chain began.
     */
    fun byShiftHour(h: History): List<ShiftHour> {
        val rows = mutableMapOf<Int, MutableList<Long>>()
        for (s in h.shifts.filter { it.ended_at_ms - it.started_at_ms >= MIN_WORK_SHIFT_MS }) {
            val hoursInShift = ((s.ended_at_ms - s.started_at_ms) / 3_600_000L).toInt()
            for (i in 0 until hoursInShift) {
                val a = s.started_at_ms + i * 3_600_000L
                val rev = h.offers.filter { it.decision == "ACCEPTED" && it.seenMs >= a && it.seenMs < a + 3_600_000L }.sumOf { it.amountAgorot }
                rows.getOrPut(i) { mutableListOf() } += rev
            }
        }
        return rows.toSortedMap().map { (i, xs) -> ShiftHour(i + 1, xs.size, xs, if (xs.size >= MIN_SHIFTS_REPEATED) Label.REPEATED else Label.HYPOTHESIS) }
    }

    // ---- what works ----

    data class Finding(val title: String, val label: Label, val body: String)

    fun findings(h: History): List<Finding> {
        val work = h.workReviews
        val withAdded = h.chains.count { it.offers > 1 }
        val standalone = h.chains.count { it.offers == 1 && it.duration_min_ms != null }
        val out = mutableListOf<Finding>()
        work.lastOrNull()?.let { r ->
            r.netPerShiftHourAgorot?.let { n ->
                out += Finding(
                    "נטו לשעת משמרת",
                    Label.MEASURED,
                    "במשמרת האחרונה: ₪${formatAgorot(n.low)}–₪${formatAgorot(n.high)} לשעה, כולל זמני המתנה ואחרי דלק ובלאי. " +
                        if (work.size < 2) "משמרת אחת — עוד לא ידוע כמה זה משתנה בין משמרות." else "${work.size} משמרות עד עכשיו.",
                )
            }
            r.time?.let { t ->
                out += Finding(
                    "עמידה בתוך משלוחים",
                    if (work.size >= MIN_SHIFTS_REPEATED) Label.REPEATED else Label.HYPOTHESIS,
                    "${pct(t.standingInDeliveries)} מזמן המשמרת האחרונה עמדת במקום בתוך משלוחים (המתנה במסעדה, מסירה, רמזורים — עוד לא אפשר להפריד). זה החלק הגדול ביותר אחרי הנסיעה.",
                )
            }
        }
        out += Finding(
            "לקבל הצעה נוספת בזמן משלוח",
            if (withAdded == 0) Label.NOT_ENOUGH_DATA else Label.HYPOTHESIS,
            if (withAdded == 0) "עוד לא היו משלוחים עם הצעה נוספת."
            else "$withAdded שרשראות עם הצעות נוספות. שרשרת אחת כזו הרוויחה יותר לשעה, אבל דוגמה אחת לא מוכיחה — צריך להשוות משמרות (ניסוי 1).",
        )
        out += Finding(
            "מחיר וק״מ בכרטיס מנבאים רווח",
            Label.NOT_ENOUGH_DATA,
            "$standalone משלוחים בודדים עד עכשיו. צריך הרבה יותר משלוחים, במחירים ובמרחקים שונים, לפני שאפשר לדעת.",
        )
        h.eta?.checks?.mapNotNull { it.error_vs_first_route_target_min }?.takeIf { it.isNotEmpty() }?.let { e ->
            out += Finding(
                "שעת היעד של Wolt",
                Label.TESTED,
                "נבדק מול ${e.size} מסירות: הגעת תמיד אחרי השעה שהציגה Wolt, ב-${e.min().toInt()}–${e.max().toInt()} דק׳. לא לתכנן לפיה.",
            )
        }
        return out
    }

    private fun pct(x: Double) = "${Math.round(x * 100)}%"
}
