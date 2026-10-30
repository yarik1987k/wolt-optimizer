package dev.yk.offercollector

import dev.yk.offercollector.core.ShadowModel
import dev.yk.offercollector.core.ShadowModel.Completed
import dev.yk.offercollector.core.ShadowModel.Evaluated
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic history (test only). */
class ShadowModelTest {
    private val MIN = 60_000L
    private val t19 = ZonedDateTime.of(2026, 11, 2, 19, 0, 0, 0, ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()

    /** Single-order deliveries of [km] km, each lasting [mins] (cycled), paying ₪30, all starting in the 19:00 hour. */
    private fun trips(mins: List<Int>, km: Double = 5.0, orders: Int = 1) =
        mins.mapIndexed { i, m -> Completed(t19 + i * 3 * MIN, t19 + i * 3 * MIN + m * MIN, km, 3000, orders, singleOrderVerified = orders == 1) }

    private fun history(completed: List<Completed>, shiftHours: Double): ShadowModel.History {
        val shift = LongArray(24); shift[18] = (shiftHours / 3 * 3_600_000).toLong(); shift[19] = shift[18]; shift[20] = shift[18]
        return ShadowModel.History(completed, shift)
    }

    private fun input(amount: Long, km: Double? = 5.0, end: Long? = t19 + 180 * MIN, cost: Long? = 50, cell: String? = "g500:1:1", margin: Int = 15) =
        ShadowModel.Input(t19, amount, km, cell, null, "UNKNOWN", "s", end, cost, margin, routeContext = "IDLE")

    private val steady = history(trips(List(10) { 25 }), 10.0) // every delivery 25 min; ₪30 × 10 in 10 h

    @Test fun insufficientDataMeansNoInventedNumbers() {
        val p = ShadowModel.predict(input(3000), history(emptyList(), 0.0))
        assertEquals("INSUFFICIENT_DATA", p.decision)
        assertNull(p.duration); assertNull(p.historicalShiftHourlyRate); assertNull(p.netPerHourMedian); assertNull(p.baselineTimeValueAgorot)
        val q = ShadowModel.predict(input(3000, end = null, cost = null), steady)
        assertEquals("INSUFFICIENT_DATA", q.decision)
        assertTrue(q.reasons.contains("לא נקבעה שעת סיום משמרת"))
        assertTrue(q.reasons.contains("לא הוגדרה עלות רכב לק״מ"))
    }

    @Test fun durationUsesObservedTripsWithARange() {
        val h = history(trips(listOf(20, 22, 25, 28, 30, 35, 40)), 10.0)
        val d = ShadowModel.duration(h, 5.0)!!
        assertEquals(28.0, d.medianMin, 1e-9)
        assertTrue(d.lowMin < d.medianMin && d.medianMin < d.highMin)
        assertTrue(d.distanceMatched)
        assertEquals("LOW", d.confidence) // < 15 trips
        assertEquals(7, d.usedTripIds.size)
        // Far distance → not matched, falls back to all trips and says so.
        val far = ShadowModel.duration(h, 15.0)!!
        assertFalse(far.distanceMatched)
        assertTrue(ShadowModel.predict(input(3000, km = 15.0), h).reasons.any { it.contains("מרחק דומה") })
    }

    @Test fun bundledAndIncompleteDeliveriesNeverEnterTheDurationModel() {
        val bundles = trips(List(10) { 60 }, orders = 2)
        val incomplete = List(10) { Completed(t19 + it * MIN, null, 5.0, 3000) }
        val unknownEnd = trips(List(10) { 20 }).map { it.copy(endEvidence = "UNKNOWN") }
        val implausible = trips(List(10) { 300 })
        // Looks single but the order count was never proven (no route timeline) → excluded.
        val unproven = trips(List(10) { 25 }).map { it.copy(singleOrderVerified = false) }
        assertNull(ShadowModel.duration(history(unproven, 10.0), 5.0))
        assertNull(ShadowModel.duration(history(bundles + incomplete + unknownEnd + implausible, 10.0), 5.0))
        // Add 5 clean single-order trips: only they are used.
        val d = ShadowModel.duration(history(bundles + incomplete + trips(List(5) { 25 }), 10.0), 5.0)!!
        assertEquals(5, d.usedTripIds.size)
        assertEquals(25.0, d.medianMin, 1e-9)
    }

    @Test fun acceptAndDeclineAreClearAtBothEndsOfTheRange() {
        // Baseline: ₪30 × 10 − ₪0.50 × 5 km × 10 = ₪275 over 10 h = ₪27.50/h.
        val good = ShadowModel.predict(input(4500), steady) // (45 − 2.5) / (25/60) ≈ ₪102/h
        assertEquals("ACCEPT", good.decision)
        assertEquals(2750L, good.historicalShiftHourlyRate!!.agorotPerHour)
        assertEquals(250L, good.vehicleCostAgorot)
        assertEquals(10, good.historicalShiftHourlyRate!!.usedTripIds.size)
        assertEquals("DECLINE", ShadowModel.predict(input(1000), steady).decision) // ≈ ₪18/h
    }

    @Test fun uncertainEstimateStaysUndecided() {
        // Durations 10…60 min: the offer beats the baseline when fast but not when slow → no classification.
        val wide = history(trips(listOf(10, 12, 15, 20, 40, 50, 55, 60)), 10.0)
        val p = ShadowModel.predict(input(2000), wide) // baseline ₪22/h; slow ≈ ₪19.8/h, fast ≈ ₪79/h
        assertEquals("INSUFFICIENT_DATA", p.decision)
        assertTrue(p.reasons.any { it.contains("לא חד-משמעי") })
        assertTrue(p.netPerHourAtFast!! > p.netPerHourAtSlow!!)
        // The margin is configurable and part of the input (stored with the prediction).
        assertEquals(40, ShadowModel.predict(input(4500, margin = 40), steady).input.marginPct)
    }

    @Test fun missingCourierLocationIsRecordedNotGuessed() {
        val p = ShadowModel.predict(input(4500, cell = null), steady)
        assertNull(p.input.courierCell)
        assertTrue(p.reasons.any { it.contains("אין מיקום שליח") })
        assertEquals("ACCEPT", p.decision) // v1 does not use location; it is logged for later models
    }

    @Test fun offerAddedToAnActiveRouteIsNotJudgedAsAnIsolatedDelivery() {
        // Case B (real 29.10: offers accepted while an earlier order was still on board).
        val added = ShadowModel.predict(input(4500).copy(routeContext = "ACTIVE_ROUTE"), steady)
        assertEquals("INSUFFICIENT_DATA", added.decision)
        assertTrue(added.reasons.any { it.contains("מסלול פעיל") })
        assertEquals("INSUFFICIENT_DATA", ShadowModel.predict(input(4500).copy(routeContext = "UNKNOWN"), steady).decision)
        assertEquals("ACCEPT", ShadowModel.predict(input(4500), steady).decision) // case A, idle
    }

    @Test fun shiftEndBoundary() {
        // Slow end of the range = 25 min. Exactly 25 min left fits; 24 min does not.
        assertEquals("ACCEPT", ShadowModel.predict(input(4500, end = t19 + 25 * MIN), steady).decision)
        val late = ShadowModel.predict(input(4500, end = t19 + 24 * MIN), steady)
        assertEquals("DECLINE", late.decision)
        assertTrue(late.reasons.any { it.contains("סוף המשמרת") })
    }

    @Test fun evaluationKeepsTheMethodsApart() {
        val xs = listOf(
            Evaluated("ACCEPT", 25.0, 20.0, 30.0, 27.0, "ACCEPTED"),
            Evaluated("ACCEPT", 25.0, 20.0, 30.0, 40.0, "ACCEPTED"),
            Evaluated("DECLINE", 20.0, 15.0, 25.0, null, "DECLINED"),
            Evaluated("ACCEPT", 20.0, 15.0, 25.0, null, "DECLINED"),
            Evaluated("INSUFFICIENT_DATA", null, null, null, null, "DECLINED"),
        )
        val acc = ShadowModel.accuracy(xs)
        assertEquals(2, acc.n); assertEquals(8.5, acc.maeMin!!, 1e-9); assertEquals(0.5, acc.withinRange!!, 1e-9)
        val ag = ShadowModel.agreement(xs)
        assertEquals(4, ag.decided); assertEquals(3, ag.sameAsCourier); assertEquals(1, ag.modelAcceptCourierDeclined)
        assertFalse(ShadowModel.readiness(xs, shifts = 1).ready)
    }
}
