package dev.yk.offercollector.core

import kotlinx.serialization.Serializable

/**
 * Shift-level facts: the optimisation target is earnings after variable vehicle cost per SHIFT hour,
 * so idle time counts. Time is split into chain (delivery) time and the rest, and the rest by evidence:
 * VERIFIED idle (Wolt's status button showed online) vs ONLINE (only an older/weaker signal) vs OFFLINE
 * vs UNKNOWN. Chain time is a range (chain starts/ends are ranges).
 */
object ShiftFacts {
    @Serializable
    data class Shift(
        val shift_id: String,
        val started_at_ms: Long,
        val ended_at_ms: Long,
        val ended: Boolean,
        /** Chain (delivery) time inside the shift: shortest … longest reading of the chain ranges. */
        val chain_ms_low: Long,
        val chain_ms_high: Long,
        val chains: Int,
        val chains_with_unknown_end: Int,
        /** Outside chains (the longest chain reading removed). */
        val verified_idle_ms: Long,
        val online_unverified_ms: Long,
        val offline_ms: Long,
        val unknown_ms: Long,
        /** Accepted offers whose chain started in this shift, each counted once. */
        val revenue_agorot: Long,
        /** Between chains (no accepted offer active): time, GPS km and moving time — repositioning. */
        val between_chains_ms: Long? = null,
        val between_chains_km: Double? = null,
        val between_chains_moving_ms: Long? = null,
        /** OBSERVED (GPS speed) over the whole shift. */
        val shift_stationary_ms: Long? = null,
        val shift_moving_ms: Long? = null,
        val completion_slides: Int,
        val gps_samples: Int,
        val gps_max_gap_ms: Long? = null,
        val gps_path_km: Double? = null,
        val gps_speed_km: Double? = null,
        val gps_distance_reliable: Boolean,
        val missing: List<String> = emptyList(),
    )

    fun build(
        shifts: List<AreaDensity.Shift>,
        samples: List<AreaDensity.Sample>,
        marks: List<AreaDensity.StateMark>,
        chains: List<ChainFacts.ChainEconomicsFacts>,
        gps: List<ChainFacts.GpsPoint>,
        nowMs: Long,
    ): List<Shift> = shifts.map { sh ->
        val ended = sh.endMs <= nowMs
        val own = chains.filter { it.start_latest_ms in sh.startMs..sh.endMs }
        val missing = mutableListOf<String>()
        val unknownEnd = own.count { it.end_latest_ms == null }
        if (unknownEnd > 0) missing += "$unknownEnd chain(s) without an observed end — their time is counted as UNKNOWN, not as idle"
        // Longest reading of each chain = DELIVERY time for the state split; unknown-end chains are left out.
        val asTrips = own.mapNotNull { c -> c.end_latest_ms?.let { AreaDensity.Trip(c.start_earliest_ms ?: c.start_latest_ms, it) } }
        val pieces = AreaDensity.pieces(listOf(sh), samples, marks, asTrips)
        fun sum(s: AreaDensity.TimeState) = pieces.filter { it.state == s }.sumOf { it.endMs - it.startMs }
        val low = own.sumOf { it.duration_min_ms ?: 0L }
        val high = own.sumOf { it.duration_max_ms ?: 0L }
        val sortedGps = gps.sortedBy { it.atMs }
        val g = ChainFacts.gps(sortedGps, sh.startMs, sh.endMs)
        // Gaps between chains (longest chain reading), only when every chain has an end.
        val between = if (unknownEnd == 0) {
            val windows = own.map { (it.start_earliest_ms ?: it.start_latest_ms) to it.end_latest_ms!! }.sortedBy { it.first }
            val edges = listOf(sh.startMs) + windows.flatMap { listOf(it.first, it.second) } + listOf(sh.endMs)
            edges.chunked(2).filter { it.size == 2 && it[1] > it[0] }.map { ChainFacts.gps(sortedGps, it[0], it[1]) to (it[1] - it[0]) }
        } else null
        if (!g.reliable) missing += "shift GPS not reliable: ${g.reason}"
        Shift(
            shift_id = sh.id,
            started_at_ms = sh.startMs,
            ended_at_ms = sh.endMs,
            ended = ended,
            chain_ms_low = low,
            chain_ms_high = high,
            chains = own.size,
            chains_with_unknown_end = unknownEnd,
            verified_idle_ms = sum(AreaDensity.TimeState.VERIFIED),
            online_unverified_ms = sum(AreaDensity.TimeState.ONLINE),
            offline_ms = sum(AreaDensity.TimeState.OFFLINE),
            unknown_ms = sum(AreaDensity.TimeState.UNKNOWN),
            revenue_agorot = own.sumOf { it.revenue_agorot },
            between_chains_ms = between?.sumOf { it.second },
            between_chains_km = between?.sumOf { it.first.pathKm },
            between_chains_moving_ms = between?.sumOf { it.first.movingMs },
            shift_stationary_ms = g.stationaryMs,
            shift_moving_ms = g.movingMs,
            completion_slides = own.sumOf { it.completion_slides_ms.size },
            gps_samples = g.samples,
            gps_max_gap_ms = g.maxGapMs,
            gps_path_km = g.pathKm,
            gps_speed_km = g.speedKm,
            gps_distance_reliable = g.reliable,
            missing = missing,
        )
    }
}
