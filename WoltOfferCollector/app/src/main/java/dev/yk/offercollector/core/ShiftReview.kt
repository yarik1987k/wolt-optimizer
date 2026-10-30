package dev.yk.offercollector.core

/**
 * After-shift review: what the courier actually earned per hour of the shift, after vehicle cost, and where the
 * time went. Pure. Every figure carries its evidence: MEASURED (from this shift), ESTIMATE (a stated assumption,
 * e.g. the per-km cost), or MISSING (with the reason). Nothing here is shown during a shift.
 */
object ShiftReview {
    enum class Evidence { MEASURED, ESTIMATE, MISSING }

    data class Money(val low: Long, val high: Long) {
        val mid: Long get() = (low + high) / 2
        val exact: Boolean get() = low == high
    }

    /** Shares of the shift's wall-clock time (sum ≤ 1; the rest is unknown). */
    data class TimeSplit(val driving: Double, val standingInDeliveries: Double, val betweenDeliveries: Double, val unknown: Double)

    data class ChainLine(
        val startMs: Long,
        val offers: Int,
        val revenueAgorot: Long,
        val minutesLow: Double,
        val minutesHigh: Double,
        /** Revenue per active minute (agorot), from the longest/shortest reading. */
        val perMinuteLow: Double,
        val perMinuteHigh: Double,
        val withAddedOffers: Boolean,
    )

    data class Review(
        val shiftId: String,
        val startMs: Long,
        val endMs: Long,
        val hours: Double,
        val revenueAgorot: Long,
        val revenueEvidence: Evidence,
        /** GPS km × cost per km; null when distance or cost is missing. */
        val vehicleCostAgorot: Money?,
        val vehicleCostEvidence: Evidence,
        val netAgorot: Money?,
        /** The main number: net per FULL shift hour (idle included). */
        val netPerShiftHourAgorot: Money?,
        val netPerActiveHourAgorot: Money?,
        val km: Pair<Double, Double>?,
        val precise: Boolean,
        val deliveries: Int,
        val offersSeen: Int,
        val offersAccepted: Int,
        val offersDeclined: Int,
        val time: TimeSplit?,
        val chains: List<ChainLine>,
        val worst: ChainLine?,
        val best: ChainLine?,
        val missing: List<String>,
    )

    fun build(
        shift: ShiftFacts.Shift,
        chains: List<ChainFacts.ChainEconomicsFacts>,
        offersSeen: Int,
        offersAccepted: Int,
        offersDeclined: Int,
        costPerKmAgorot: Long?,
        precise: Boolean,
    ): Review {
        val missing = mutableListOf<String>()
        val hours = (shift.ended_at_ms - shift.started_at_ms) / 3_600_000.0
        val own = chains.filter { it.start_latest_ms in shift.started_at_ms..shift.ended_at_ms }
        val km = if (shift.gps_distance_reliable && shift.gps_path_km != null) {
            val a = shift.gps_path_km
            val b = shift.gps_speed_km ?: a
            minOf(a, b) to maxOf(a, b)
        } else null
        if (km == null) missing += "אין מרחק GPS אמין למשמרת"
        if (costPerKmAgorot == null) missing += "לא הוגדרה עלות רכב לק״מ"
        val cost = if (km != null && costPerKmAgorot != null) Money(Math.round(km.first * costPerKmAgorot), Math.round(km.second * costPerKmAgorot)) else null
        val revenue = shift.revenue_agorot
        val net = cost?.let { Money(revenue - it.high, revenue - it.low) }
        val perShiftHour = net?.takeIf { hours > 0 }?.let { Money(Math.round(it.low / hours), Math.round(it.high / hours)) }
        val activeLowH = shift.chain_ms_low / 3_600_000.0
        val activeHighH = shift.chain_ms_high / 3_600_000.0
        val perActiveHour = if (net != null && activeLowH > 0) Money(Math.round(net.low / activeHighH), Math.round(net.high / activeLowH)) else null
        if (shift.chains_with_unknown_end > 0) missing += "${shift.chains_with_unknown_end} שרשראות בלי סיום שנצפה"

        val total = (shift.ended_at_ms - shift.started_at_ms).toDouble()
        val inMoving = own.sumOf { it.gps_moving_ms ?: 0L }.toDouble()
        val inStill = own.sumOf { it.gps_stationary_ms ?: 0L }.toDouble()
        val between = shift.between_chains_ms?.toDouble()
        val time = if (total > 0 && between != null && own.all { it.gps_moving_ms != null }) {
            val d = inMoving / total
            val s = inStill / total
            val w = between / total
            TimeSplit(d, s, w, (1.0 - d - s - w).coerceAtLeast(0.0))
        } else null

        val lines = own.mapNotNull { c ->
            val lo = c.duration_min_ms ?: return@mapNotNull null
            val hi = c.duration_max_ms ?: return@mapNotNull null
            val minLo = lo / 60_000.0
            val minHi = hi / 60_000.0
            ChainLine(c.start_latest_ms, c.offers, c.revenue_agorot, minLo, minHi, c.revenue_agorot / minHi, c.revenue_agorot / minLo, c.offers > 1)
        }
        val byRate = lines.sortedBy { (it.perMinuteLow + it.perMinuteHigh) / 2 }
        return Review(
            shiftId = shift.shift_id,
            startMs = shift.started_at_ms,
            endMs = shift.ended_at_ms,
            hours = hours,
            revenueAgorot = revenue,
            revenueEvidence = Evidence.MEASURED,
            vehicleCostAgorot = cost,
            vehicleCostEvidence = if (cost == null) Evidence.MISSING else Evidence.ESTIMATE,
            netAgorot = net,
            netPerShiftHourAgorot = perShiftHour,
            netPerActiveHourAgorot = perActiveHour,
            km = km,
            precise = precise,
            deliveries = shift.completion_slides,
            offersSeen = offersSeen,
            offersAccepted = offersAccepted,
            offersDeclined = offersDeclined,
            time = time,
            chains = lines.sortedBy { it.startMs },
            worst = byRate.firstOrNull()?.takeIf { lines.size >= 2 },
            best = byRate.lastOrNull()?.takeIf { lines.size >= 2 },
            missing = missing,
        )
    }
}
