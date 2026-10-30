package dev.yk.offercollector

import dev.yk.offercollector.core.DetectedOffer
import dev.yk.offercollector.core.OfferOutcomes
import dev.yk.offercollector.ui.Hours
import dev.yk.offercollector.ui.Period
import dev.yk.offercollector.ui.filterOffers
import dev.yk.offercollector.ui.outcomeCounts
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisFilterTest {
    private val z = ZoneId.of("Asia/Jerusalem")
    private fun at(d: String, h: Int) = ZonedDateTime.of(LocalDate.parse(d).atTime(h, 10), z).toInstant().toEpochMilli()
    private fun o(ms: Long) = DetectedOffer(ms, ms + 3000, 3000, "5", null, null, pickupNames = emptyList(), snapshotCount = 1)
    private val today = LocalDate.parse("2026-10-29")
    private val offers = listOf(o(at("2026-10-29", 19)), o(at("2026-10-29", 12)), o(at("2026-10-25", 20)), o(at("2026-10-01", 18)))

    @Test fun periodAndHours() {
        assertEquals(2, filterOffers(offers, Period.TODAY, Hours.ALL, today).size)
        assertEquals(3, filterOffers(offers, Period.WEEK, Hours.ALL, today).size)
        assertEquals(4, filterOffers(offers, Period.ALL, Hours.ALL, today).size)
        assertEquals(3, filterOffers(offers, Period.ALL, Hours.EVENING, today).size) // 18:10, 19:10, 20:10
        assertEquals(1, filterOffers(offers, Period.TODAY, Hours.MIDDAY, today).size)
    }

    @Test fun outcomes() {
        val m = mapOf(
            offers[0].firstSeenMs to OfferOutcomes.Outcome(OfferOutcomes.Kind.DECLINED, "ESTIMATED", 0),
            offers[1].firstSeenMs to OfferOutcomes.Outcome(OfferOutcomes.Kind.DECLINE_DIALOG, "OBSERVED", 0),
            offers[2].firstSeenMs to OfferOutcomes.Outcome(OfferOutcomes.Kind.UNKNOWN, "UNKNOWN", 0),
        )
        val c = outcomeCounts(offers, m)
        assertEquals(listOf(2, 0, 1, 1), listOf(c.declined, c.accepted, c.unknown, c.noData))
    }
}
