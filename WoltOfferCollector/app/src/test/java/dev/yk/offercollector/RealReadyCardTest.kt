package dev.yk.offercollector

import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.StateMarkers
import dev.yk.offercollector.core.TextClassifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** REAL 29.10.2026 11:40: a "готово" (order ready at the restaurant) screen between two views of the same card is not an acceptance. */
class RealReadyCardTest {
    @Serializable private data class Row(val at_ms: Long, val candidate_state: String, val markers: StateMarkers)
    @Serializable private data class Fixture(val source: String, val rows: List<Row>)

    @Test fun readyWordIsNotADeliveryStep() {
        assertEquals("RESTAURANT_STATUS", TextClassifier.stateGroupOf("Готово"))
        assertEquals("DELIVERY_STEP", TextClassifier.stateGroupOf("доставлено"))
    }

    @Test fun declinedCardWithReadyFlashOpensNoTrip() {
        val f = Json { ignoreUnknownKeys = true }.decodeFromString<Fixture>(javaClass.classLoader!!.getResource("real_ready_card_2026-10-29_1140.json")!!.readText())
        val r = DeliveryEventDetector.detect(f.rows.map { DeliveryEventDetector.Item(it.at_ms, it.candidate_state, it.markers) })
        assertTrue(r.trips.isEmpty())
        assertTrue(r.events.none { it.type == "DELIVERY_ACCEPTED" })
        assertEquals(listOf("DECLINE_DIALOG_SHOWN", "OFFER_DECLINED"), r.events.map { it.type })
    }
}
