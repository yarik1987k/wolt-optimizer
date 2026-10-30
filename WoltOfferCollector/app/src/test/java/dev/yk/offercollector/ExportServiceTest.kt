package dev.yk.offercollector

import dev.yk.offercollector.core.SnapshotAnalyzer
import dev.yk.offercollector.core.RawNode
import dev.yk.offercollector.data.NotificationEventEntity
import dev.yk.offercollector.data.ScreenEventEntity
import dev.yk.offercollector.export.ExportService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportServiceTest {
    @Test fun exportIsValidJsonWithIsoUtcAndNoRawText() {
        val analysis = SnapshotAnalyzer.analyze(
            "W",
            listOf(RawNode("TextView", null, "₪19.90 איסוף", null, false, 0), RawNode("TextView", null, "משה פרץ 054-7654321", null, false, 0)),
            false,
        )
        val fieldsJson = ExportService.json.encodeToString(dev.yk.offercollector.core.CandidateFields.serializer(), analysis.fields)
        val screen = ScreenEventEntity(
            "s1", 1_793_250_000_000, "com.example", "WINDOW_STATE_CHANGED", analysis.category.name, 2, 2, fieldsJson,
            "VISIBLE", null, SnapshotAnalyzer.PARSE_VERSION, analysis.fingerprint, "W", 180, 3, 1.2,
        )
        val rootNull = ScreenEventEntity(
            "s2", 1_793_250_001_000, "com.example", "WINDOW_CONTENT_CHANGED", "UNKNOWN", null, null, null,
            "NO_ACCESSIBLE_TEXT", "ROOT_NULL", SnapshotAnalyzer.PARSE_VERSION, null, "W", 40, 1, null,
        )
        val notif = NotificationEventEntity("n1", 1_793_249_999_000, "com.example", "abc", "POSTED", "[UI:הזמנה] [TEXT]", null, "{}", null, "sig", 1_793_249_999_000)
        val stateEvent = dev.yk.offercollector.data.StateMarkerEventEntity(
            "m1", 1_793_250_000_500, "NOTIFICATION", "OFFER_PRESENTED", "OBSERVED", "notif.incoming_task_new_order",
            """{"source":"NOTIFICATION","notification_event":"POSTED incoming_task"}""", "p1", "t1",
        )
        val session = dev.yk.offercollector.data.TestSessionEntity("t1", "A: Online/Offline", 1_793_250_000_000, null)
        val out = ExportService.toJson(ExportService.build(listOf(notif), listOf(screen, rootNull), emptyList(), "com.example", nowMs = 1_793_250_002_000, sdkInt = 34, manufacturer = "Google", model = "Pixel",
            stateEvents = listOf(stateEvent), testSessions = listOf(session)))

        val root = Json.parseToJsonElement(out).jsonObject
        assertEquals("2026-10-29T05:00:02Z", root["exported_at_utc"]!!.jsonPrimitive.content)
        val screens = root["screen_events"]!!.jsonArray
        assertEquals("ROOT_NULL", screens[1].jsonObject["failure_reason"]!!.jsonPrimitive.content)
        assertEquals("null", screens[1].jsonObject["redacted_candidate_fields"].toString())
        val summary = root["summary"]!!.jsonObject["coverage"]!!.jsonObject
        assertEquals(1, summary["screens_with_amount"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, summary["snapshots_no_accessible_text"]!!.jsonPrimitive.content.toInt())
        assertFalse(out.contains("משה"))
        assertFalse(out.contains("054"))
        assertTrue(out.contains("does NOT mean the offer was accepted"))
        // Additive fields; the format id stays the one the Optimizer importer expects.
        assertEquals("wolt-offer-collector/diagnostics-v1", root["export_format"]!!.jsonPrimitive.content)
        val ev = root["state_marker_events"]!!.jsonArray.single().jsonObject
        assertEquals("OBSERVED", ev["evidence"]!!.jsonPrimitive.content)
        assertEquals("POSTED incoming_task", ev["markers"]!!.jsonObject["notification_event"]!!.jsonPrimitive.content)
        assertEquals("null", root["test_sessions"]!!.jsonArray.single().jsonObject["ended_at_utc"].toString())
    }
}
