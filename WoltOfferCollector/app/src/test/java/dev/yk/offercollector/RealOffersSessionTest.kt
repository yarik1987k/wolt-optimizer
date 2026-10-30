package dev.yk.offercollector

import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.OfferOutcomes
import dev.yk.offercollector.core.StateMarkers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** REAL marker rows 29.10.2026 10:49–11:24: three declines (one with card → home → dialog) and one card that just ended. */
class RealOffersSessionTest {
    @Serializable private data class Row(val at_ms: Long, val candidate_state: String, val markers: StateMarkers)
    @Serializable private data class Fixture(val source: String, val rows: List<Row>)

    private val hms = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Jerusalem"))

    @Test fun declinesAndTheUnknownCardAreSeparated() {
        val f = Json { ignoreUnknownKeys = true }.decodeFromString<Fixture>(javaClass.classLoader!!.getResource("real_offers_2026-10-29_1116-1124.json")!!.readText())
        val r = DeliveryEventDetector.detect(f.rows.map { DeliveryEventDetector.Item(it.at_ms, it.candidate_state, it.markers) })
        fun at(type: String) = r.events.filter { it.type == type }.map { hms.format(Instant.ofEpochMilli(it.action_at_ms)) }
        assertEquals(listOf("11:16", "11:19", "11:23"), at("DECLINE_DIALOG_SHOWN"))
        assertEquals(listOf("11:16", "11:19", "11:23"), at("OFFER_DECLINED"))
        // ₪18.55 at 11:21: no dialog, no delivery screen → UNKNOWN. Courier confirmed it EXPIRED,
        // so "unknown" (not "declined") was the right call. Not auto-labelled "expired": one case only.
        assertEquals(listOf("11:22"), at("OFFER_ENDED_UNKNOWN"))
        assertTrue(r.trips.isEmpty())
        assertTrue(r.events.none { it.type == "DELIVERY_ACCEPTED" || it.type == "DELIVERY_COMPLETED" })
    }

    /** The Offers tab badge: each real card gets the outcome the courier confirmed (3 declined, 1 unknown/expired). */
    @Test fun offerCardsGetTheirOutcome() {
        val f = Json { ignoreUnknownKeys = true }.decodeFromString<Fixture>(javaClass.classLoader!!.getResource("real_offers_2026-10-29_1116-1124.json")!!.readText())
        val r = DeliveryEventDetector.detect(f.rows.map { DeliveryEventDetector.Item(it.at_ms, it.candidate_state, it.markers) })
        // One window per run of card rows with the same amount (as the Offers tab groups snapshots).
        val windows = mutableListOf<Pair<Long, OfferOutcomes.Window>>()
        var prevAmount: Long? = null
        for (row in f.rows.sortedBy { it.at_ms }) {
            val a = row.markers.offer_amount_agorot.takeIf { row.markers.offer_card }
            if (a != null && a == prevAmount) windows[windows.lastIndex] = a to windows.last().second.copy(lastSeenMs = row.at_ms)
            else if (a != null) windows += a to OfferOutcomes.Window(row.at_ms, row.at_ms)
            prevAmount = a ?: prevAmount.takeIf { false }
        }
        val out = OfferOutcomes.match(windows.map { it.second }, r.events)
        val summary = windows.indices.map { "${hms.format(Instant.ofEpochMilli(windows[it].second.firstSeenMs))} ${out[it]?.kind}" }
        assertEquals(listOf("11:16 DECLINED", "11:18 DECLINED", "11:21 UNKNOWN", "11:23 DECLINED"), summary)
    }
}
