package dev.yk.offercollector

import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.StateMarkers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the detector on REAL marker rows from the phone (29.10.2026, 11:16) — courier confirmed: offer declined. */
class RealDeclineTest {
    @Serializable private data class Row(val at_ms: Long, val candidate_state: String, val markers: StateMarkers)
    @Serializable private data class Fixture(val source: String, val rows: List<Row>)

    @Test fun realDeclineIsDetectedAndNothingElseIsInvented() {
        val text = javaClass.classLoader!!.getResource("real_decline_2026-10-29.json")!!.readText()
        val f = Json { ignoreUnknownKeys = true }.decodeFromString<Fixture>(text)
        val r = DeliveryEventDetector.detect(f.rows.map { DeliveryEventDetector.Item(it.at_ms, it.candidate_state, it.markers) })
        val types = r.events.map { it.type to it.evidence }
        assertTrue(types.toString(), ("DECLINE_DIALOG_SHOWN" to "OBSERVED") in types)
        assertTrue(types.toString(), ("OFFER_DECLINED" to "ESTIMATED") in types)
        assertTrue(r.trips.isEmpty()) // no delivery was started
        assertEquals(0, r.events.count { it.type == "DELIVERY_ACCEPTED" || it.type == "DELIVERY_COMPLETED" })
    }
}
