package dev.yk.offercollector.core

import kotlin.math.abs

/**
 * The route of one shift, for the map (after the shift — never shown while driving). Pure.
 *
 * Precise when the shift has a track (~5 m, every few seconds); otherwise the ~500 m cell centres of the
 * location samples, marked COARSE (a staircase of squares, not the streets driven).
 */
object ShiftRoute {
    enum class Precision { PRECISE, COARSE, NONE }
    enum class MarkerKind { START, END, PICKUP, DROPOFF }

    data class Point(val atMs: Long, val lat: Double, val lon: Double, val speedMps: Float? = null)

    /** A run of consecutive points, inside a delivery chain or between chains. */
    data class Segment(val delivering: Boolean, val points: List<Point>)

    data class Marker(val kind: MarkerKind, val atMs: Long, val lat: Double, val lon: Double)

    data class Route(
        val precision: Precision,
        val segments: List<Segment>,
        val markers: List<Marker>,
        val km: Double,
        val deliveringKm: Double,
        val startMs: Long?,
        val endMs: Long?,
        val points: Int,
    )

    /** A marker is placed on the route point nearest in time, if one is within this. */
    const val MARKER_MAX_GAP_MS = 3 * 60_000L

    fun build(
        precise: List<Point>,
        coarse: List<Point>,
        chains: List<Pair<Long, Long>>,
        pickupsMs: List<Long>,
        dropoffsMs: List<Long>,
    ): Route {
        val precision = when {
            precise.size >= 2 -> Precision.PRECISE
            coarse.size >= 2 -> Precision.COARSE
            else -> Precision.NONE
        }
        val pts = (if (precision == Precision.PRECISE) precise else coarse).sortedBy { it.atMs }
        if (pts.size < 2) return Route(precision, emptyList(), emptyList(), 0.0, 0.0, pts.firstOrNull()?.atMs, pts.lastOrNull()?.atMs, pts.size)
        fun delivering(t: Long) = chains.any { (a, b) -> t in a..b }

        // Split into runs; the boundary point belongs to both runs so the line has no gaps.
        val segments = mutableListOf<Segment>()
        var run = mutableListOf(pts.first())
        var state = delivering(pts.first().atMs)
        for (p in pts.drop(1)) {
            val s = delivering(p.atMs)
            run += p
            if (s != state) {
                segments += Segment(state, run)
                run = mutableListOf(p)
                state = s
            }
        }
        if (run.size >= 2) segments += Segment(state, run)

        fun nearest(t: Long) = pts.minByOrNull { abs(it.atMs - t) }?.takeIf { abs(it.atMs - t) <= MARKER_MAX_GAP_MS }
        val markers = buildList {
            add(Marker(MarkerKind.START, pts.first().atMs, pts.first().lat, pts.first().lon))
            pickupsMs.forEach { t -> nearest(t)?.let { add(Marker(MarkerKind.PICKUP, t, it.lat, it.lon)) } }
            dropoffsMs.forEach { t -> nearest(t)?.let { add(Marker(MarkerKind.DROPOFF, t, it.lat, it.lon)) } }
            add(Marker(MarkerKind.END, pts.last().atMs, pts.last().lat, pts.last().lon))
        }
        fun km(s: List<Point>) = s.zipWithNext { a, b ->
            val d = ChainFacts.kmBetween(a.lat, a.lon, b.lat, b.lon)
            // Same jitter guard as the distance figures: a tiny step while standing still is noise.
            if (d < 0.025 && (a.speedMps ?: 0f) < 0.5f && (b.speedMps ?: 0f) < 0.5f) 0.0 else d
        }.sum()
        return Route(
            precision = precision,
            segments = segments,
            markers = markers,
            km = km(pts),
            deliveringKm = segments.filter { it.delivering }.sumOf { km(it.points) },
            startMs = pts.first().atMs,
            endMs = pts.last().atMs,
            points = pts.size,
        )
    }
}
