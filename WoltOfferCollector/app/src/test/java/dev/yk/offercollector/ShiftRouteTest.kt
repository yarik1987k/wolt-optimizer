package dev.yk.offercollector

import dev.yk.offercollector.core.ShiftRoute
import dev.yk.offercollector.core.ShiftRoute.MarkerKind
import dev.yk.offercollector.core.ShiftRoute.Point
import dev.yk.offercollector.core.ShiftRoute.Precision
import dev.yk.offercollector.service.ShiftLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftRouteTest {
    private val S = 1_000L
    /** ~111 m per 0.001° of latitude. */
    private fun p(t: Long, lat: Double, speed: Float? = 8f) = Point(t * S, lat, 34.78, speed)

    @Test fun precisePreferredAndSplitIntoDeliveringRuns() {
        val precise = (0..10L).map { p(it * 10, 32.0 + it * 0.001) } // 10 steps of ~111 m
        val coarse = listOf(p(0, 32.0), p(100, 32.01))
        val r = ShiftRoute.build(precise, coarse, chains = listOf(30 * S to 70 * S), pickupsMs = listOf(40 * S), dropoffsMs = listOf(70 * S))
        assertEquals(Precision.PRECISE, r.precision)
        assertEquals(listOf(false, true, false), r.segments.map { it.delivering })
        // Runs share their boundary point, so the line is continuous.
        assertEquals(r.segments[0].points.last(), r.segments[1].points.first())
        assertEquals(1.11, r.km, 0.02)
        assertEquals(0.555, r.deliveringKm, 0.02) // a step belongs to its start point: 30 s → 80 s = 5 steps
        assertEquals(listOf(MarkerKind.START, MarkerKind.PICKUP, MarkerKind.DROPOFF, MarkerKind.END), r.markers.map { it.kind })
    }

    @Test fun coarseFallbackAndNothingInvented() {
        val coarse = listOf(p(0, 32.0), p(45, 32.005), p(90, 32.005))
        val r = ShiftRoute.build(emptyList(), coarse, emptyList(), pickupsMs = listOf(10_000 * S), dropoffsMs = emptyList())
        assertEquals(Precision.COARSE, r.precision)
        // A pickup far from any route point in time is not placed anywhere.
        assertTrue(r.markers.none { it.kind == MarkerKind.PICKUP })
        assertEquals(Precision.NONE, ShiftRoute.build(emptyList(), listOf(p(0, 32.0)), emptyList(), emptyList(), emptyList()).precision)
    }

    @Test fun standingStillJitterIsNotDistance() {
        // Parked: 5 m jumps at walking-zero speed for 10 minutes.
        val still = (0..20L).map { p(it * 30, 32.0 + (if (it % 2 == 0L) 0.0 else 0.00005), 0.1f) }
        assertEquals(0.0, ShiftRoute.build(still, emptyList(), emptyList(), emptyList(), emptyList()).km, 1e-9)
    }

    @Test fun trackKeepsMovementAndOnePointPer30sWhenStill() {
        // Accuracy and mock filters.
        assertFalse(ShiftLocation.keepTrackFix(null, null, null, 0, 32.0, 34.78, 40f, false))
        assertFalse(ShiftLocation.keepTrackFix(null, null, null, 0, 32.0, 34.78, 5f, true))
        assertFalse(ShiftLocation.keepTrackFix(null, null, null, 0, 32.0, 34.78, null, false))
        assertTrue(ShiftLocation.keepTrackFix(null, null, null, 0, 32.0, 34.78, 5f, false))
        // Moved ~11 m after 5 s → kept; moved ~1 m after 5 s → dropped; still for 30 s → kept.
        assertTrue(ShiftLocation.keepTrackFix(0, 32.0, 34.78, 5_000, 32.0001, 34.78, 5f, false))
        assertFalse(ShiftLocation.keepTrackFix(0, 32.0, 34.78, 5_000, 32.00001, 34.78, 5f, false))
        assertTrue(ShiftLocation.keepTrackFix(0, 32.0, 34.78, 30_000, 32.00001, 34.78, 5f, false))
        // Out-of-order fix → dropped.
        assertFalse(ShiftLocation.keepTrackFix(10_000, 32.0, 34.78, 5_000, 32.01, 34.78, 5f, false))
    }
}
