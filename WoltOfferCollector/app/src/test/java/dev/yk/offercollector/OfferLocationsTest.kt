package dev.yk.offercollector

import dev.yk.offercollector.core.DetectedOffer
import dev.yk.offercollector.core.OfferLocations
import dev.yk.offercollector.data.LocationSampleEntity
import dev.yk.offercollector.data.ShiftSessionEntity
import dev.yk.offercollector.export.ExportService
import dev.yk.offercollector.export.LocationReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic inputs (no collected data). Acceptance #4, #5, #6, #7 of the Phase 3 spec. */
class OfferLocationsTest {
    private val t0 = 1_800_000_000_000L
    private fun offer(at: Long, id: String) = DetectedOffer(at, at + 5_000, 3000, "5", null, null, pickupNames = emptyList(), snapshotCount = 2, firstScreenId = id)
    private fun s(id: String, at: Long, trigger: String, ok: Boolean, cell: String? = "g500:1:1", age: Long? = 3_000, shift: String = "S") =
        OfferLocations.Sample(id, shift, at, trigger, ok, if (ok) cell else null, if (ok) 12 else null, age)
    private val session = listOf(OfferLocations.Session("S", t0, t0 + 3_600_000))

    @Test fun confidenceLevels() {
        val offers = listOf(offer(t0 + 60_000, "o1"), offer(t0 + 600_000, "o2"), offer(t0 + 1_200_000, "o3"), offer(t0 + 1_800_000, "o4"))
        val samples = listOf(
            s("a", t0 + 61_000, "OFFER_PRESENTED", true, "g500:1:1"),                 // o1 HIGH
            s("b", t0 + 601_000, "OFFER_PRESENTED", true, "g500:2:2", age = 45_000),  // o2 MEDIUM (fix 45 s from sample)
            s("c", t0 + 1_230_000, "PERIODIC", true, "g500:3:3"),                    // o3 LOW (periodic 30 s later)
            s("d", t0 + 1_801_000, "OFFER_PRESENTED", false),                        // o4: offer sample but no valid fix
        )
        val r = OfferLocations.link(offers, samples, session, ExportService::iso).associateBy { it.offer_id }
        assertEquals("HIGH", r["o1"]!!.match_confidence); assertEquals("g500:1:1", r["o1"]!!.grid_cell_id)
        assertEquals("MEDIUM", r["o2"]!!.match_confidence)
        assertEquals("LOW", r["o3"]!!.match_confidence); assertEquals("g500:3:3", r["o3"]!!.grid_cell_id)
        assertEquals("NONE", r["o4"]!!.match_confidence); assertNull(r["o4"]!!.grid_cell_id)
    }

    @Test fun neverBorrowsAnOldSampleOrAnotherSession() {
        val offers = listOf(offer(t0 + 600_000, "late"), offer(t0 - 60_000, "before_session"))
        val samples = listOf(s("old", t0 + 400_000, "OFFER_PRESENTED", true), s("other", t0 + 600_000, "OFFER_PRESENTED", true, shift = "X"))
        val r = OfferLocations.link(offers, samples, session, ExportService::iso)
        assertEquals(listOf("late"), r.map { it.offer_id }) // offers outside a session get no row
        assertEquals("NONE", r[0].match_confidence) // 200 s old sample and other-session sample are not used
    }

    @Test fun reportCountsOffersGapsAndBattery() {
        val shift = ShiftSessionEntity("S", t0, t0 + 3_600_000, "USER", true, t0 + 3_600_000, 45, 120, batteryStartPct = 90, batteryEndPct = 80)
        fun e(id: String, at: Long, ok: Boolean, trigger: String = "PERIODIC") = LocationSampleEntity(
            id, "S", at, trigger, "UNKNOWN", if (ok) "OK" else "UNKNOWN", if (ok) null else "LOW_ACCURACY",
            if (ok) "g500:1:1" else null, null, null, if (ok) 10 else 400, 1, "fused", locationAgeMs = 1_000,
        )
        val samples = listOf(e("1", t0 + 1_000, true, "SHIFT_START"), e("2", t0 + 60_000, true, "OFFER_PRESENTED"), e("3", t0 + 2_000_000, false), e("4", t0 + 3_600_000, true, "SHIFT_END"))
        val r = LocationReport.build(listOf(shift), samples, listOf(offer(t0 + 59_000, "o")), ExportService::iso)
        assertEquals(1, r.summary.offers_with_location)
        assertEquals(mapOf("LOW_ACCURACY" to 1, "OK" to 3), r.summary.samples_by_capture_status)
        assertEquals(2, r.summary.monitoring_gaps.size) // two silences > 2.5 × 120 s
        assertEquals(10.0, r.summary.battery_drop_pct_per_hour!!, 0.01)
        assertEquals(1, r.sessions[0].missing_location_count)
        assertEquals(10, r.summary.median_accuracy_m)
    }
}
