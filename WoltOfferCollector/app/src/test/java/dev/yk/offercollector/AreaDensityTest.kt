package dev.yk.offercollector

import dev.yk.offercollector.core.AreaDensity
import dev.yk.offercollector.core.AreaDensity.Offer
import dev.yk.offercollector.core.AreaDensity.Sample
import dev.yk.offercollector.core.AreaDensity.Shift
import dev.yk.offercollector.core.AreaDensity.StateMark
import dev.yk.offercollector.core.AreaDensity.TimeState
import dev.yk.offercollector.core.AreaDensity.Window
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic inputs (test only). */
class AreaDensityTest {
    private val MIN = 60_000L
    private val t0 = ZonedDateTime.of(2026, 10, 29, 18, 0, 0, 0, ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()
    private val A = "g500:7123:6559"
    private val B = "g500:7130:6560"
    private val ALL = Window(0, 24)
    private fun samples(cell: String?, from: Long, to: Long, shift: String = "s1") =
        (from..to step 45_000).map { Sample(shift, it, cell, 31.99, 34.76, 8) }
    private fun minutes(ms: Long?) = Math.round((ms ?: 0) / 60_000.0)

    @Test fun unverifiedOnlineTimeNeverBecomesAVerifiedRate() {
        val shifts = listOf(Shift("s1", t0, t0 + 60 * MIN))
        val marks = (0..55 step 5).map { StateMark(t0 + it * MIN, "AVAILABLE", "ESTIMATED") }
        val offers = (1..6).map { Offer(t0 + it * 9 * MIN, 3000, A, listOf("Shufersal")) }
        val r = AreaDensity.analyze(shifts, samples(A, t0, t0 + 60 * MIN), marks, emptyList(), offers, ALL)
        val a = r.areas.single()
        assertEquals(0L, a.verifiedMs)
        assertEquals(60L, minutes(a.onlineUnverifiedMs))
        assertEquals(AreaDensity.RateKind.ESTIMATE, a.rate.kind) // labelled estimate, not a verified value
        assertEquals(6.0, a.rate.perHour!!, 0.01)
        assertEquals(AreaDensity.Quality.NONE, a.quality)
        assertEquals(listOf("Shufersal" to 6), a.restaurants)
    }

    @Test fun verifiedRateWithExactInterval() {
        val shifts = listOf(Shift("s1", t0, t0 + 60 * MIN))
        val marks = (0..55 step 5).map { StateMark(t0 + it * MIN, "AVAILABLE", "OBSERVED") }
        val offers = (1..6).map { Offer(t0 + it * 9 * MIN, 3000 + it * 500L, A, emptyList()) }
        val a = AreaDensity.analyze(shifts, samples(A, t0, t0 + 60 * MIN), marks, emptyList(), offers, ALL).areas.single()
        assertEquals(AreaDensity.RateKind.VERIFIED, a.rate.kind)
        assertEquals(6.0, a.rate.perHour!!, 0.01)
        // Garwood 95% for k = 6: 2.202 – 13.06
        assertEquals(2.202, a.rate.low!!, 0.01)
        assertEquals(13.06, a.rate.high!!, 0.02)
        assertEquals(0.5, a.shareAbove[50]!!, 1e-9) // 5000, 5500, 6000 of 6
    }

    @Test fun gpsTimeAloneGivesNoRate() {
        val shifts = listOf(Shift("s1", t0, t0 + 60 * MIN))
        val a = AreaDensity.analyze(shifts, samples(A, t0, t0 + 60 * MIN), emptyList(), emptyList(), listOf(Offer(t0 + MIN, 3000, A, emptyList())), ALL).areas.single()
        assertEquals(60L, minutes(a.timeMs[TimeState.UNKNOWN]))
        assertEquals(AreaDensity.RateKind.NONE, a.rate.kind)
        assertNull(a.rate.perHour)
    }

    @Test fun staleStateAndLocationExpire() {
        val shifts = listOf(Shift("s1", t0, t0 + 30 * MIN))
        // One verified mark at 0, one sample at 0: state lasts 10 min, area 6 min.
        val p = AreaDensity.pieces(shifts, listOf(Sample("s1", t0, A, null, null, 5)), listOf(StateMark(t0, "AVAILABLE", "OBSERVED")), emptyList())
        assertEquals(10L, minutes(p.filter { it.state == TimeState.VERIFIED }.sumOf { it.endMs - it.startMs }))
        assertEquals(6L, minutes(p.filter { it.cellId == A }.sumOf { it.endMs - it.startMs }))
    }

    @Test fun tripTimeIsDelivery() {
        val shifts = listOf(Shift("s1", t0, t0 + 30 * MIN))
        val marks = (0..25 step 5).map { StateMark(t0 + it * MIN, "AVAILABLE", "OBSERVED") }
        val p = AreaDensity.pieces(shifts, samples(A, t0, t0 + 30 * MIN), marks, listOf(AreaDensity.Trip(t0 + 10 * MIN, t0 + 20 * MIN)))
        assertEquals(10L, minutes(p.filter { it.state == TimeState.DELIVERY }.sumOf { it.endMs - it.startMs }))
    }

    @Test fun sortedByMeasuredRateNotByCount() {
        // A: 4 offers in 30 min verified (8/h). B: 6 offers in 60 min verified (6/h). B has more offers, A ranks first.
        val shifts = listOf(Shift("s1", t0, t0 + 30 * MIN), Shift("s2", t0 + 120 * MIN, t0 + 180 * MIN))
        val marks = (0..175 step 5).map { StateMark(t0 + it * MIN, "AVAILABLE", "OBSERVED") }
        val smp = samples(A, t0, t0 + 30 * MIN, "s1") + samples(B, t0 + 120 * MIN, t0 + 180 * MIN, "s2")
        val offers = (1..4).map { Offer(t0 + it * 6 * MIN, 3000, A, emptyList()) } + (1..6).map { Offer(t0 + 120 * MIN + it * 9 * MIN, 3000, B, emptyList()) }
        val r = AreaDensity.analyze(shifts, smp, marks, emptyList(), offers, ALL)
        assertEquals(listOf(A, B), r.areas.map { it.cellId })
        assertEquals(listOf(4, 6), r.areas.map { it.offers })
    }

    @Test fun windowFiltersAndSaysWhatToMeasure() {
        val shifts = listOf(Shift("s1", t0, t0 + 150 * MIN)) // 18:00–20:30
        val marks = (0..145 step 5).map { StateMark(t0 + it * MIN, "AVAILABLE", "OBSERVED") }
        val smp = samples(A, t0, t0 + 120 * MIN) + samples(B, t0 + 120 * MIN, t0 + 150 * MIN)
        val r1820 = AreaDensity.analyze(shifts, smp, marks, emptyList(), emptyList(), Window(18, 20))
        val r2022 = AreaDensity.analyze(shifts, smp, marks, emptyList(), emptyList(), Window(20, 22))
        assertEquals(listOf(A), r1820.areas.map { it.cellId })
        assertEquals(listOf(B), r2022.areas.map { it.cellId }) // at 20:00 the courier is already in B
        assertTrue(r1820.toMeasure.first().contains("צריך לפחות 5"))
        val per = AreaDensity.shiftsPerWindow(shifts, smp, marks, emptyList())
        assertEquals(1, per[Window(18, 20)])
        assertEquals(0, per[Window(10, 12)])
    }

    @Test fun offersWithoutCourierAreaAreCountedApart() {
        val shifts = listOf(Shift("s1", t0, t0 + 10 * MIN))
        val r = AreaDensity.analyze(shifts, samples(A, t0, t0 + 10 * MIN), emptyList(), emptyList(), listOf(Offer(t0 + MIN, 3000, null, listOf("X"))), ALL)
        assertEquals(1, r.offersWithoutArea)
        assertEquals(0, r.areas.single().offers)
    }

    @Test fun rateWordingNeverLooksCertainWhenItIsNot() {
        assertEquals("אין נתונים", dev.yk.offercollector.ui.rateText(AreaDensity.Rate(AreaDensity.RateKind.NONE, null, null, null, 0.0)))
        assertTrue(dev.yk.offercollector.ui.rateText(AreaDensity.Rate(AreaDensity.RateKind.ESTIMATE, 6.0, 2.0, 13.0, 1.0)).contains("אומדן"))
        assertTrue(dev.yk.offercollector.ui.rateText(AreaDensity.Rate(AreaDensity.RateKind.VERIFIED, 6.0, 2.2, 13.1, 1.0)).startsWith("6.0 לשעה"))
        assertEquals(dev.yk.offercollector.core.shortAreaCode(A), dev.yk.offercollector.core.shortAreaCode(A))
    }
}
