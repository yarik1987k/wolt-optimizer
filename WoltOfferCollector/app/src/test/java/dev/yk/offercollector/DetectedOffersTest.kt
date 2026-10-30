package dev.yk.offercollector

import dev.yk.offercollector.core.CandidateFields
import dev.yk.offercollector.core.DetectedOffers
import dev.yk.offercollector.core.OfferScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectedOffersTest {
    private fun card(at: Long, agorot: Long, km: String? = null, words: List<String> = listOf("принять")) =
        OfferScreen(at, CandidateFields(amounts_agorot = listOf(agorot), distances_km = listOfNotNull(km), ui_keywords = words))

    @Test fun snapshotsOfOneCardBecomeOneOffer() {
        // Mirrors the real 09:40 offer: ₪27.49 / 4.5 km seen in 4 snapshots over ~7 s.
        val offers = DetectedOffers.build(listOf(card(0, 2749, "4.5"), card(1000, 2749), card(2000, 2749, "4.5"), card(6500, 2749)))
        assertEquals(1, offers.size)
        assertEquals(2749L, offers[0].amountAgorot)
        assertEquals("4.5", offers[0].distanceKm)
        assertEquals(6500L, offers[0].lastSeenMs - offers[0].firstSeenMs)
        assertEquals(4, offers[0].snapshotCount)
    }

    @Test fun differentPriceOrLongGapIsANewOffer() {
        val offers = DetectedOffers.build(listOf(card(0, 2749), card(5000, 3382), card(200_000, 3382)))
        assertEquals(listOf(2749L, 3382L, 3382L), offers.map { it.amountAgorot })
    }

    @Test fun aScrollingOrBundleCardStaysOneOffer() {
        // Real 21:21 bundle (₪31.09 / 4.2 km): the pickup line changed between snapshots.
        fun c(at: Long, km: String?, name: String) = OfferScreen(at, CandidateFields(amounts_agorot = listOf(3109), distances_km = listOfNotNull(km), ui_keywords = listOf("принять"), pickup_names = listOf(name)))
        val o = DetectedOffers.build(listOf(c(0, "4.2", "Tiv Ta'am"), c(5_000, "4.2", "Tiv Ta'am"), c(14_000, "4.2", "Moses Shop"), c(19_000, null, "Moses Shop"))).single()
        assertEquals(listOf("Tiv Ta'am", "Moses Shop"), o.pickupNames)
        assertEquals(4, o.snapshotCount)
        // Same price, different distance → two offers.
        assertEquals(2, DetectedOffers.build(listOf(card(0, 3109, "4.2"), card(5_000, 3109, "2.0"))).size)
    }

    @Test fun priceWithoutAcceptButtonIsNotAnOffer() {
        // e.g. bonus banner amounts (₪200/₪250) on the home screen.
        assertEquals(0, DetectedOffers.build(listOf(card(0, 20000, words = listOf("бонус")))).size)
    }

    @Test fun missingFieldsStayNull() {
        val o = DetectedOffers.build(listOf(card(0, 2523))).single()
        assertNull(o.distanceKm)
        assertNull(o.durationMin)
        assertNull(o.stopsCount)
    }
}
