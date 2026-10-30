package dev.yk.offercollector

import dev.yk.offercollector.core.AreaDensity
import dev.yk.offercollector.core.StateClassifier
import dev.yk.offercollector.core.StateMarkers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** REAL Test A (29.10.2026): the status-button rule must reproduce both Offline→Online cycles, as OBSERVED. */
class RealTestATest {
    @Serializable private data class Row(val at_ms: Long, val candidate_state: String, val markers: StateMarkers)
    @Serializable private data class Fixture(val source: String, val rows: List<Row>)

    private val f = Json { ignoreUnknownKeys = true }.decodeFromString<Fixture>(javaClass.classLoader!!.getResource("real_test_a_2026-10-29.json")!!.readText())

    @Test fun statusButtonFlipsBothWaysTwiceAndIsVerified() {
        val status = f.rows.filter { it.markers.source == "SCREEN" }
            .map { StateClassifier.classify(it.markers) }
            .filter { it.rule.startsWith("screen.status_button") }
        assertTrue(status.all { it.evidence.name == "OBSERVED" })
        // Collapse repeats: OFFLINE → AVAILABLE → OFFLINE → AVAILABLE → OFFLINE (two full cycles).
        val runs = status.map { it.state.name }.fold(listOf<String>()) { acc, s -> if (acc.lastOrNull() == s) acc else acc + s }
        assertEquals(listOf("OFFLINE", "AVAILABLE", "OFFLINE", "AVAILABLE", "OFFLINE"), runs)
    }

    @Test fun offersOnlyWhileOnline() {
        val screen = f.rows.filter { it.markers.source == "SCREEN" }.map { it.at_ms to StateClassifier.classify(it.markers) }
        for ((at, c) in screen.filter { it.second.state.name == "OFFER_PRESENTED" }) {
            val before = screen.lastOrNull { it.first < at && it.second.rule.startsWith("screen.status_button") }?.second?.state?.name
            val after = screen.firstOrNull { it.first > at && it.second.rule.startsWith("screen.status_button") }?.second?.state?.name
            assertTrue("offer at $at: before=$before after=$after", before == "AVAILABLE" || after == "AVAILABLE")
        }
    }

    @Test fun verifiedTimeNowCountsAsVerifiedAvailability() {
        val marks = f.rows.filter { it.markers.source == "SCREEN" || it.markers.source == "NOTIFICATION" }
            .map { StateClassifier.classify(it.markers).let { c -> AreaDensity.StateMark(it.at_ms, c.state.name, c.evidence.name) } }
        val start = f.rows.first().at_ms
        val end = f.rows.last().at_ms
        val p = AreaDensity.pieces(listOf(AreaDensity.Shift("t", start, end)), listOf(AreaDensity.Sample("t", start, "g500:1:1", null, null, 8)), marks, emptyList())
        val verified = p.filter { it.state == AreaDensity.TimeState.VERIFIED }.sumOf { it.endMs - it.startMs }
        val offline = p.filter { it.state == AreaDensity.TimeState.OFFLINE }.sumOf { it.endMs - it.startMs }
        // Real timing: online 13:46:37–13:47:07 and 13:47:19–13:47:52 (~63 s); offline only 7 s + 12 s + 1 s.
        assertTrue("verified=$verified", verified in 55_000..70_000)
        assertTrue("offline=$offline", offline in 15_000..25_000)
    }
}
