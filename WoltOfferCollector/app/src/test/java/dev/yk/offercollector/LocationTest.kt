package dev.yk.offercollector

import dev.yk.offercollector.core.LocationFix
import dev.yk.offercollector.core.LocationGrid
import dev.yk.offercollector.core.LocationQuality
import dev.yk.offercollector.core.LocationSamplingPolicy
import dev.yk.offercollector.data.LocationSampleEntity
import dev.yk.offercollector.data.ShiftSessionEntity
import dev.yk.offercollector.export.ExportService
import dev.yk.offercollector.ui.shiftSampleSummary
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Points are synthetic test inputs (Rishon LeZion area coordinates), not collected data. */
class LocationTest {
    private val now = 1_800_000_000_000L
    private fun fix(lat: Double = 31.9640, lon: Double = 34.8040, acc: Float? = 12f, ageMs: Long = 2_000, mock: Boolean = false) =
        LocationFix(lat, lon, acc, now - ageMs, mock)

    @Test fun cellIsAbout500mAndStable() {
        val a = LocationGrid.cellOf(31.9640, 34.8040)
        assertEquals(a, LocationGrid.cellOf(31.9640, 34.8040))
        assertTrue(a.id.startsWith("g500:"))
        // ~100 m away (well inside a 500 m cell, unless on a boundary): neighbours differ by at most one cell.
        val (_, y, x) = a.id.split(":")
        val b = LocationGrid.cellOf(31.9640 + 0.0009, 34.8040 + 0.001)
        val (_, y2, x2) = b.id.split(":")
        assertTrue(abs(y.toLong() - y2.toLong()) <= 1 && abs(x.toLong() - x2.toLong()) <= 1)
        // ~1 km north is never the same cell.
        assertNotEquals(a.id, LocationGrid.cellOf(31.9640 + 0.009, 34.8040).id)
        // Cell centre is within half a cell (~250 m per axis) of the point.
        assertTrue(abs(a.centerLat - 31.9640) * 111_320 <= 251)
        assertTrue(abs(a.centerLon - 34.8040) * 111_320 * cos(Math.toRadians(31.964)) <= 251)
    }

    @Test fun centreIsRoundedNotExact() {
        val c = LocationGrid.cellOf(31.96401234, 34.80405678)
        assertEquals(c.centerLat, Math.round(c.centerLat * 100_000) / 100_000.0, 0.0)
        assertNotEquals(31.96401234, c.centerLat, 1e-6)
    }

    @Test fun qualityGateMarksUnknown() {
        assertEquals("OK", LocationQuality.evaluate(fix(), now).status)
        assertEquals("NO_FIX", LocationQuality.evaluate(null, now).unknownReason)
        assertEquals("STALE_FIX", LocationQuality.evaluate(fix(ageMs = 121_000), now).unknownReason)
        assertEquals("LOW_ACCURACY", LocationQuality.evaluate(fix(acc = 151f), now).unknownReason)
        assertEquals("LOW_ACCURACY", LocationQuality.evaluate(fix(acc = null), now).unknownReason)
        assertEquals("MOCK_LOCATION", LocationQuality.evaluate(fix(mock = true), now).unknownReason)
        // An UNKNOWN reading never carries a cell.
        assertNull(LocationQuality.evaluate(fix(acc = 500f), now).cell)
        assertEquals(12, LocationQuality.evaluate(fix(), now).accuracyM)
        assertEquals(2, LocationQuality.evaluate(fix(), now).fixAgeS)
    }

    @Test fun triggersFollowLiveState() {
        assertEquals("OFFER_PRESENTED", LocationSamplingPolicy.triggerFor("OFFER_PRESENTED", "SCREEN"))
        assertEquals("OFFER_PRESENTED", LocationSamplingPolicy.triggerFor("OFFER_PRESENTED", "NOTIFICATION"))
        assertEquals("DELIVERY_SCREEN", LocationSamplingPolicy.triggerFor("DELIVERY_ACTIVE", "SCREEN"))
        assertEquals("AVAILABLE_SCREEN", LocationSamplingPolicy.triggerFor("AVAILABLE", "SCREEN"))
        assertEquals("OFFLINE", LocationSamplingPolicy.triggerFor("OFFLINE", "SCREEN"))
        assertNull(LocationSamplingPolicy.triggerFor("AVAILABLE", "NOTIFICATION"))
        assertNull(LocationSamplingPolicy.triggerFor("OFFER_PRESENTED", "ACTION"))
        assertNull(LocationSamplingPolicy.triggerFor("UNKNOWN", "SYSTEM"))
        assertNull(LocationSamplingPolicy.triggerFor("UNKNOWN", "SCREEN"))
        assertEquals(setOf("DELIVERY_ACCEPTED", "PICKUP_DONE", "DROPOFF_DONE", "DELIVERY_COMPLETED"), LocationSamplingPolicy.DETECTOR_EVENT_TRIGGERS)
    }

    @Test fun periodicAndWatchdog() {
        val p = LocationSamplingPolicy
        assertFalse(p.isDuplicate(null, now))
        assertTrue(p.isDuplicate(now - 29_000, now))
        assertFalse(p.isDuplicate(now - 30_000, now))
        assertTrue(p.periodicDue(now, null, 45_000))
        assertFalse(p.periodicDue(now, now - 30_000, 45_000))
        assertTrue(p.periodicDue(now, now - 41_000, 45_000)) // 90 % of the interval
        // No fix for 2 intervals → a NO_FIX sample is due (gap recorded, old position not repeated).
        assertTrue(p.noFixDue(now, now - 90_000, now - 50_000, 45_000))
        assertFalse(p.noFixDue(now, now - 30_000, now - 50_000, 45_000))
        assertFalse(p.shiftExpired(now, now + 6 * 3_600_000L - 1))
        assertTrue(p.shiftExpired(now, now + 6 * 3_600_000L))
    }

    @Test fun eventUsesTheFixNearestTheEventOrNone() {
        val fixes = listOf(fix(ageMs = 100_000), fix(ageMs = 40_000), fix(ageMs = 5_000))
        assertEquals(now - 5_000, LocationSamplingPolicy.fixForEvent(fixes, now)!!.fixTimeMs)
        assertEquals(now - 40_000, LocationSamplingPolicy.fixForEvent(fixes, now - 45_000)!!.fixTimeMs)
        // Event 3 min ago: no fix within 60 s → none (stored as STALE/NO_FIX, not as measured then).
        assertNull(LocationSamplingPolicy.fixForEvent(fixes, now - 180_000))
        val stale = LocationQuality.evaluate(fix(ageMs = 100_000), now - 180_000, LocationSamplingPolicy.EVENT_MAX_FIX_DISTANCE_MS)
        assertEquals("STALE_FIX", stale.unknownReason)
        assertNull(stale.cell)
    }

    @Test fun stationaryNeedsThreeSlowNearFixes() {
        val still = listOf(fix(ageMs = 90_000).copy(speedMps = 0.2f), fix(ageMs = 45_000).copy(speedMps = 0f), fix(ageMs = 0).copy(speedMps = 0.4f))
        assertTrue(LocationSamplingPolicy.isStationary(still))
        assertFalse(LocationSamplingPolicy.isStationary(still.take(2)))
        val moving = still.mapIndexed { i, f -> f.copy(lat = f.lat + i * 0.002) } // ~220 m apart
        assertFalse(LocationSamplingPolicy.isStationary(moving))
        assertFalse(LocationSamplingPolicy.isStationary(still.map { it.copy(speedMps = 8f) }))
    }

    @Test fun gapsAreReportedNotHidden() {
        val p = LocationSamplingPolicy
        val start = now
        val times = listOf(start + 45_000, start + 90_000, start + 900_000, start + 945_000) // 13.5 min silence
        val g = p.gaps(start, start + 1_000_000, "USER", times, 120_000)
        assertEquals(1, g.size)
        assertEquals("NO_SAMPLES", g[0].kind)
        assertEquals(start + 90_000, g[0].fromMs)
        val killed = p.gaps(start, start + 100_000, "SERVICE_STOPPED", listOf(start + 45_000, start + 90_000), 120_000)
        assertEquals(listOf("SERVICE_STOPPED"), killed.map { it.kind })
    }

    private fun sample(id: String, status: String, reason: String? = null, cell: String? = null, at: Long = now, trigger: String = "PERIODIC") =
        LocationSampleEntity(
            id, "s1", at, trigger, "UNKNOWN", status, reason, cell, cell?.let { 31.96 }, cell?.let { 34.80 }, 10, 1, "fused",
            source = if (trigger == "PERIODIC") "PERIODIC" else "EVENT", locationAgeMs = 2_000,
            debugLat = 31.9640123, debugLon = 34.8040456,
        )

    @Test fun summaryCountsUnknownReasons() {
        val s = listOf(sample("1", "OK", cell = "g500:1:1"), sample("2", "OK", cell = "g500:1:1"), sample("3", "UNKNOWN", "LOW_ACCURACY"))
        assertEquals("3 דגימות · 2 באזור ידוע (1 אזורים) · 1 לא ידוע (LOW_ACCURACY×1)", shiftSampleSummary(s))
    }

    @Test fun exportCarriesCellsButNoExactCoordinates() {
        val shift = ShiftSessionEntity("s1", now, now + 60_000, "USER", true, now + 60_000)
        val e = ExportService.build(
            emptyList(), emptyList(), emptyList(), "com.wolt.courierapp",
            shifts = listOf(shift), locationSamples = listOf(sample("1", "OK", cell = "g500:1:1"), sample("2", "UNKNOWN", "NO_FIX")),
            nowMs = now, sdkInt = 36, manufacturer = "x", model = "y",
        )
        assertEquals(1, e.shift_sessions.size)
        assertEquals("USER", e.shift_sessions[0].end_reason)
        assertEquals(listOf("OK", "UNKNOWN"), e.location_samples.map { it.status })
        assertNull(e.location_samples[1].cell_id)
        assertEquals("g500", e.location_grid.scheme)
        assertEquals(11, e.schema_version)
        assertEquals("loc-2", e.location_parser_version)
        assertEquals(1, e.location_sessions.size)
        assertEquals("STOPPED", e.location_sessions[0].status)
        assertEquals(2, e.location_sessions[0].sample_count)
        assertEquals(1, e.location_sessions[0].missing_location_count)
        assertEquals(listOf("OK", "NO_FIX"), e.location_samples.map { it.capture_status })
        assertTrue(e.location_quality_summary != null)
        val text = ExportService.toJson(e)
        // Debug exact values (31.9640123 / 34.8040456) never leave the device.
        assertFalse(text.contains("31.9640123") || text.contains("34.8040456") || text.contains("debug"))
        assertFalse(text.contains("\"latitude\"") || text.contains("\"longitude\""))
        // Backward compatible: 0.3.0 fields are still there with the same meaning.
        assertTrue(text.contains("\"shift_sessions\"") && text.contains("\"cell_id\"") && text.contains("\"unknown_reason\""))
    }

    /** Writes a synthetic Phase 3 export so the (unchanged) Optimizer's parser can be checked against it. */
    @Test fun writeCompatibilityExport() {
        val shift = ShiftSessionEntity("s1", now, now + 60_000, "USER", true, now + 60_000, 45, 120, 90, 89)
        val e = ExportService.build(
            emptyList(), emptyList(), emptyList(), "com.wolt.courierapp",
            shifts = listOf(shift), locationSamples = listOf(sample("1", "OK", cell = "g500:1:1"), sample("2", "UNKNOWN", "NO_FIX", trigger = "DELIVERY_ACCEPTED")),
            nowMs = now, sdkInt = 36, manufacturer = "x", model = "y",
        )
        java.io.File("build/phase3-compat-export.json").writeText(ExportService.toJson(e))
    }
}
