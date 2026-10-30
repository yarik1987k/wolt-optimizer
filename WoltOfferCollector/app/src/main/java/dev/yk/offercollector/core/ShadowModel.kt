package dev.yk.offercollector.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Shadow mode, rule "shadow-v1" — an EXPERIMENTAL classification computed silently when an offer
 * appears and stored immutably. It is never shown during a shift and never alerts.
 *
 * The goal is total shift revenue. v1 compares the offer's net money per hour with the courier's
 * HistoricalShiftHourlyRate — a BASELINE (what past shift hours in this hour window actually paid),
 * NOT the true opportunity cost of declining (which depends on future dispatches nobody can observe).
 *
 * Duration = REAL observed delivery durations (accept → complete: drive, waiting at the business,
 * drop-off together). Nothing is decomposed or synthesised. Only completed SINGLE-order deliveries
 * with both ends known and a plausible length are used; bundles and incomplete trips are excluded.
 *
 * ACCEPT / DECLINE are experimental shadow classifications, not proven optimal decisions.
 */
object ShadowModel {
    const val RULE_VERSION = "shadow-v1"
    const val DEFAULT_MARGIN_PCT = 15
    const val MIN_TRIPS_FOR_DURATION = 5
    const val SIMILAR_DISTANCE_KM = 2.0
    const val FAIR_CONFIDENCE_TRIPS = 15
    const val MIN_TRIPS_FOR_BASELINE = 5
    const val MIN_SHIFT_HOURS_FOR_BASELINE = 3.0
    const val MIN_PLAUSIBLE_MIN = 3.0
    const val MAX_PLAUSIBLE_MIN = 120.0

    /** A delivery from history, as detected. Only some qualify for the duration model. */
    data class Completed(
        val startMs: Long,
        val endMs: Long?,
        val distanceKm: Double?,
        val amountAgorot: Long,
        val orders: Int = 1,
        val startEvidence: String = "OBSERVED",
        val endEvidence: String = "OBSERVED",
        /** Wolt's route timeline showed exactly one pickup and one drop-off (see TripOrders). Unproven = excluded. */
        val singleOrderVerified: Boolean = false,
    ) {
        val minutes: Double? get() = endMs?.let { (it - startMs) / 60_000.0 }
        /**
         * Proven single order, both ends known, plausible length — bundles, trips whose order count is
         * unproven (real 29.10.2026: offers that looked single carried 2–3 orders) and incomplete trips never qualify.
         */
        val qualifies: Boolean get() = orders == 1 && singleOrderVerified && endMs != null && startEvidence != "UNKNOWN" && endEvidence != "UNKNOWN" &&
            minutes!! in MIN_PLAUSIBLE_MIN..MAX_PLAUSIBLE_MIN
        val id: String get() = "trip@$startMs"
    }

    data class History(
        val completed: List<Completed>,
        /** Measured shift time per local hour of day (ms), from the courier's own shifts. */
        val shiftMsByHour: LongArray,
    )

    data class Input(
        val offerAtMs: Long,
        val amountAgorot: Long,
        val distanceKm: Double?,
        val courierCell: String?,
        val pickupCell: String?,
        val pickupBasis: String,
        val shiftId: String?,
        val plannedShiftEndMs: Long?,
        val vehicleCostPerKmAgorot: Long?,
        /** Experimental threshold, configurable; stored with every prediction. */
        val marginPct: Int = DEFAULT_MARGIN_PCT,
        /**
         * IDLE (case A) | ACTIVE_ROUTE (case B: added while carrying orders) | UNKNOWN. v1 only models case A:
         * an added offer's value is incremental to the existing route, never an isolated delivery.
         */
        val routeContext: String = "UNKNOWN",
    )

    data class Duration(
        val medianMin: Double,
        val lowMin: Double, // 20th percentile
        val highMin: Double, // 80th percentile
        val confidence: String, // LOW | FAIR
        val distanceMatched: Boolean,
        val usedTripIds: List<String>,
    )

    data class HourlyRate(val agorotPerHour: Long, val trips: Int, val shiftHours: Double, val usedTripIds: List<String>)

    data class Prediction(
        val input: Input,
        val remainingShiftMin: Double?,
        val duration: Duration?,
        val vehicleCostAgorot: Long?,
        /** (amount − vehicle cost) ÷ hours, at the median / slow (high) / fast (low) duration. */
        val netPerHourMedian: Long?,
        val netPerHourAtSlow: Long?,
        val netPerHourAtFast: Long?,
        /** Baseline, not the true opportunity cost. */
        val historicalShiftHourlyRate: HourlyRate?,
        /** historicalShiftHourlyRate × median duration — the baseline value of the time, not a true opportunity cost. */
        val baselineTimeValueAgorot: Long?,
        val decision: String, // ACCEPT | DECLINE | INSUFFICIENT_DATA  (experimental shadow classification)
        val reasons: List<String>,
    )

    private fun quantile(sorted: List<Double>, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val lo = sorted[pos.toInt()]
        val hi = sorted[minOf(sorted.size - 1, pos.toInt() + 1)]
        return lo + (hi - lo) * (pos - pos.toInt())
    }

    /** Observed durations of similar-distance single-order deliveries (or all of them, flagged); null below the minimum. */
    fun duration(history: History, distanceKm: Double?): Duration? {
        val ok = history.completed.filter { it.qualifies }
        val similar = if (distanceKm == null) emptyList() else ok.filter { it.distanceKm != null && abs(it.distanceKm - distanceKm) <= SIMILAR_DISTANCE_KM }
        val (used, matched) = when {
            similar.size >= MIN_TRIPS_FOR_DURATION -> similar to true
            ok.size >= MIN_TRIPS_FOR_DURATION -> ok to false
            else -> return null
        }
        val mins = used.map { it.minutes!! }.sorted()
        return Duration(
            medianMin = quantile(mins, 0.5),
            lowMin = quantile(mins, 0.2),
            highMin = quantile(mins, 0.8),
            confidence = if (matched && used.size >= FAIR_CONFIDENCE_TRIPS) "FAIR" else "LOW",
            distanceMatched = matched,
            usedTripIds = used.map { it.id },
        )
    }

    /** HistoricalShiftHourlyRate in the offer's hour window (±1 h): net earned ÷ measured shift hours. Baseline only. */
    fun historicalShiftHourlyRate(history: History, hour: Int, costPerKm: Long?): HourlyRate? {
        val hours = listOf((hour + 23) % 24, hour, (hour + 1) % 24)
        val shiftH = hours.sumOf { history.shiftMsByHour[it] } / 3_600_000.0
        // Earnings, count and vehicle cost from the SAME completed deliveries (any order count — they are real earnings).
        val inWindow = history.completed.filter { it.endMs != null && it.endEvidence != "UNKNOWN" && OfferAnalytics.localHour(it.startMs) in hours }
        if (inWindow.size < MIN_TRIPS_FOR_BASELINE || shiftH < MIN_SHIFT_HOURS_FOR_BASELINE) return null
        val costs = if (costPerKm == null) 0L else inWindow.sumOf { ((it.distanceKm ?: 0.0) * costPerKm).roundToLong() }
        return HourlyRate(((inWindow.sumOf { it.amountAgorot } - costs) / shiftH).roundToLong(), inWindow.size, shiftH, inWindow.map { it.id })
    }

    fun predict(i: Input, history: History): Prediction {
        val reasons = mutableListOf<String>()
        val remaining = i.plannedShiftEndMs?.let { (it - i.offerAtMs) / 60_000.0 }
        if (remaining == null) reasons += "לא נקבעה שעת סיום משמרת"
        if (i.courierCell == null) reasons += "אין מיקום שליח (לא משפיע על v1, נרשם)"
        val d = duration(history, i.distanceKm)
        if (d == null) reasons += "רק ${history.completed.count { it.qualifies }} משלוחים בודדים שהושלמו (צריך $MIN_TRIPS_FOR_DURATION) — אין הערכת משך"
        else if (!d.distanceMatched) reasons += "משך לפי כל המשלוחים — אין מספיק משלוחים במרחק דומה"
        if (i.distanceKm == null) reasons += "אין מרחק בכרטיס"
        val cost = i.vehicleCostPerKmAgorot?.let { c -> i.distanceKm?.let { (it * c).roundToLong() } }
        if (i.vehicleCostPerKmAgorot == null) reasons += "לא הוגדרה עלות רכב לק״מ"
        val rate = historicalShiftHourlyRate(history, OfferAnalytics.localHour(i.offerAtMs), i.vehicleCostPerKmAgorot)
        if (rate == null) reasons += "אין מספיק היסטוריה לקצב משמרת שעתי (צריך $MIN_TRIPS_FOR_BASELINE משלוחים ו-$MIN_SHIFT_HOURS_FOR_BASELINE ש׳ באותן שעות)"
        fun perHour(min: Double) = cost?.let { ((i.amountAgorot - it) / (min / 60.0)).roundToLong() }
        val atMedian = d?.let { perHour(it.medianMin) }
        val atSlow = d?.let { perHour(it.highMin) }
        val atFast = d?.let { perHour(it.lowMin) }
        val m = i.marginPct / 100.0

        if (i.routeContext == "ACTIVE_ROUTE") reasons += "הצעה נוספת בזמן מסלול פעיל — דרוש מודל תוספתי (הכנסה/זמן/מרחק ביחס למסלול), עוד לא קיים"
        if (i.routeContext == "UNKNOWN") reasons += "לא ידוע אם יש מסלול פעיל"
        val decision = when {
            i.routeContext != "IDLE" -> "INSUFFICIENT_DATA"
            d == null || cost == null || rate == null || remaining == null || atSlow == null || atFast == null -> "INSUFFICIENT_DATA"
            d.highMin > remaining -> "DECLINE".also { reasons += "בטווח האיטי המשלוח חורג מסוף המשמרת" }
            atSlow >= rate.agorotPerHour * (1 + m) -> "ACCEPT"
            atFast <= rate.agorotPerHour * (1 - m) -> "DECLINE"
            else -> "INSUFFICIENT_DATA".also { reasons += "טווח המשך חוצה את קו הבסיס (±${i.marginPct}%) — לא חד-משמעי" }
        }
        return Prediction(
            i, remaining, d, cost, atMedian, atSlow, atFast, rate,
            if (d != null && rate != null) (rate.agorotPerHour * d.medianMin / 60.0).roundToLong() else null,
            decision, reasons,
        )
    }

    // ---- evaluation (computed later; stored predictions are never changed) ----

    data class Evaluated(
        val decision: String,
        val predictedMin: Double?,
        val predictedLow: Double?,
        val predictedHigh: Double?,
        /** Only for offers that became a completed single-order delivery. */
        val actualMin: Double?,
        val actualDecision: String?, // ACCEPTED | DECLINED | UNKNOWN
    )

    data class Accuracy(val n: Int, val maeMin: Double?, val withinRange: Double?)

    /** 1. Prediction accuracy — estimated vs actual duration on completed deliveries that had an estimate. */
    fun accuracy(xs: List<Evaluated>): Accuracy {
        val pairs = xs.filter { it.predictedMin != null && it.actualMin != null }
        if (pairs.isEmpty()) return Accuracy(0, null, null)
        return Accuracy(
            n = pairs.size,
            maeMin = pairs.map { abs(it.predictedMin!! - it.actualMin!!) }.average(),
            withinRange = pairs.count { it.actualMin!! in it.predictedLow!!..it.predictedHigh!! }.toDouble() / pairs.size,
        )
    }

    /** 2. Historical policy simulation — HYPOTHETICAL: agreement only; declined offers' outcomes can't be reconstructed. */
    data class Agreement(val decided: Int, val sameAsCourier: Int, val modelAcceptCourierDeclined: Int, val modelDeclineCourierAccepted: Int)

    fun agreement(xs: List<Evaluated>): Agreement {
        val d = xs.filter { it.decision != "INSUFFICIENT_DATA" && (it.actualDecision == "ACCEPTED" || it.actualDecision == "DECLINED") }
        return Agreement(
            decided = d.size,
            sameAsCourier = d.count { (it.decision == "ACCEPT") == (it.actualDecision == "ACCEPTED") },
            modelAcceptCourierDeclined = d.count { it.decision == "ACCEPT" && it.actualDecision == "DECLINED" },
            modelDeclineCourierAccepted = d.count { it.decision == "DECLINE" && it.actualDecision == "ACCEPTED" },
        )
    }

    data class Readiness(val ready: Boolean, val checks: List<Pair<String, Boolean>>)

    /** Ready for a CONTROLLED test only — never for live recommendations by itself. */
    fun readiness(xs: List<Evaluated>, shifts: Int): Readiness {
        val acc = accuracy(xs)
        val coverage = if (xs.isEmpty()) 0.0 else xs.count { it.decision != "INSUFFICIENT_DATA" }.toDouble() / xs.size
        val f = { v: Double -> String.format(java.util.Locale.US, "%.1f", v) }
        val checks = listOf(
            "לפחות 30 משלוחים שהושלמו עם תחזית (יש ${acc.n})" to (acc.n >= 30),
            "טעות משך ממוצעת עד 6 דק׳ (${acc.maeMin?.let(f) ?: "—"})" to ((acc.maeMin ?: 99.0) <= 6.0),
            "לפחות 60% מהמשלוחים בתוך טווח התחזית 20–80 (${acc.withinRange?.let { "${(it * 100).toInt()}%" } ?: "—"})" to ((acc.withinRange ?: 0.0) >= 0.6),
            "לפחות 70% מההצעות קיבלו סיווג (${(coverage * 100).toInt()}%)" to (coverage >= 0.7),
            "לפחות 5 משמרות (יש $shifts)" to (shifts >= 5),
        )
        return Readiness(checks.all { it.second }, checks)
    }
}
