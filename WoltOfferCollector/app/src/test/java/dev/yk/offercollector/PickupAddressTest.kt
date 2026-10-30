package dev.yk.offercollector

import dev.yk.offercollector.core.CandidateFields
import dev.yk.offercollector.core.LocationGrid
import dev.yk.offercollector.core.PickupDemand
import dev.yk.offercollector.core.PickupDemand.Basis
import dev.yk.offercollector.core.RawNode
import dev.yk.offercollector.core.SnapshotAnalyzer
import dev.yk.offercollector.service.PickupAddresses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic content (test only). */
class PickupAddressTest {
    private fun tv(t: String) = RawNode("android.widget.TextView", null, t, null, false, 1)
    private val A = LocationGrid.cellOf(31.99, 34.76).id
    private val FAR = LocationGrid.cellOf(32.08, 34.78).id

    @Test fun gateKeepsAddressInMemoryUntilConfirmed() {
        val f = CandidateFields(pickup_names = listOf("Goomba | Rishon LeZion"), pickup_address = "Herzl 10, Rishon LeZion 7525001")
        val (stored, cand) = PickupAddresses.gate(PickupAddresses.UNVERIFIED, f, 1L)
        assertNull(stored.pickup_address)
        assertEquals("Herzl 10, Rishon LeZion 7525001", cand!!.address)
        assertEquals(f.pickup_address, PickupAddresses.gate(PickupAddresses.CONFIRMED, f, 1L).first.pickup_address)
        val rejected = PickupAddresses.gate(PickupAddresses.REJECTED, f, 1L)
        assertNull(rejected.first.pickup_address); assertNull(rejected.second)
    }

    @Test fun geocoderReceivesOnlyTheBusinessAddress() {
        val url = PickupAddresses.queryUrl("Herzl 10, Rishon LeZion 7525001")
        assertTrue(url.startsWith("https://nominatim.openstreetmap.org/search?"))
        val q = url.substringAfter("?").split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
        assertEquals(setOf("format", "limit", "countrycodes", "q"), q.keys) // no name, no location, no ids
        assertEquals("Herzl+10%2C+Rishon+LeZion", q["q"])
    }

    @Test fun geocodeQueryDropsWoltAreaAndPostalCode() {
        // Real addresses from the 29.10.2026 shift (all NOT_FOUND with the suffix).
        assertEquals("רוטשילד 72, ראשון לציון", PickupAddresses.geocodeQuery("רוטשילד 72, ראשון לציון, Rishon Lezion & Hashfela area, 7522308"))
        assertEquals("אריק איינשטיין 1 בת ים", PickupAddresses.geocodeQuery("אריק איינשטיין 1 בת ים, Rishon Lezion & Hashfela area, 5959262"))
        assertEquals("ז'בוטינסקי 53, ראשון לציון", PickupAddresses.geocodeQuery("ז'בוטינסקי 53 , ראשון לציון, Rishon Lezion & Hashfela area, 7526655"))
        assertEquals("אח\"י דקר 13, ראשון לציון", PickupAddresses.geocodeQuery("אח\"י דקר 13, ראשון לציון, Rishon Lezion & Hashfela area, 7565213"))
    }

    @Test fun abbreviatedCityIsExpanded() {
        assertEquals("רוטשילד 57, ראשון לציון", PickupAddresses.geocodeQuery("רוטשילד 57, ראשל\"צ, Rishon Lezion & Hashfela area, 7520139"))
    }

    @Test fun matchFarFromTheCourierIsRejected() {
        val near = PickupAddresses.decide(listOf(PickupAddresses.Hit(31.99, 34.76)))
        // Courier ~1 km away → kept; ~100 km away (real wrong match for a Bat Yam street) → rejected.
        assertEquals("OK", PickupAddresses.nearCourier(near, 31.995, 34.77).first)
        val far = PickupAddresses.nearCourier(PickupAddresses.decide(listOf(PickupAddresses.Hit(32.92, 34.98))), 32.02, 34.75)
        assertEquals("FAR_FROM_COURIER", far.first)
        assertNull(far.second)
        // No courier position → nothing to compare, the geocoder's answer stands.
        assertEquals("OK", PickupAddresses.nearCourier(near, null, null).first)
    }

    @Test fun geocodeDecision() {
        assertEquals("NOT_FOUND", PickupAddresses.decide(emptyList()).first)
        val one = PickupAddresses.decide(listOf(PickupAddresses.Hit(31.99, 34.76), PickupAddresses.Hit(31.9905, 34.7605)))
        assertEquals("OK", one.first); assertEquals(A, one.second!!.id)
        assertEquals("AMBIGUOUS", PickupAddresses.decide(listOf(PickupAddresses.Hit(31.99, 34.76), PickupAddresses.Hit(32.08, 34.78))).first)
        assertEquals(1, PickupAddresses.parse("""[{"lat":"31.99","lon":"34.76","class":"amenity"}]""").size)
    }

    private fun offer(t: Long, name: String = "Goomba | Rishon LeZion", addr: String? = "Herzl 10", trip: Int? = null) =
        PickupDemand.OfferIn(t, 3000, 5.0, listOf(name), trip, addr)

    @Test fun sourcesAgreeConflictOrUnknown() {
        val key = "Goomba | Rishon LeZion|Herzl 10"
        // Address only.
        assertEquals(Basis.ADDRESS, PickupDemand.assign(listOf(offer(1)), emptyList(), mapOf(key to A)).single().basis)
        // Address + GPS at a real pickup, same spot.
        val obsSame = listOf(PickupDemand.Observation(2, key, A, "OBSERVED"))
        assertEquals(Basis.ADDRESS_AND_GPS, PickupDemand.assign(listOf(offer(1)), obsSame, mapOf(key to A)).single().basis)
        // Address and GPS disagree → review, no area chosen.
        val conflict = PickupDemand.assign(listOf(offer(1)), listOf(PickupDemand.Observation(2, key, FAR, "OBSERVED")), mapOf(key to A)).single()
        assertEquals(Basis.NEEDS_REVIEW, conflict.basis); assertNull(conflict.pickupCell)
        // Same NAME, different address (another branch) → not matched.
        assertEquals(Basis.UNKNOWN, PickupDemand.assign(listOf(offer(1, addr = "Jabotinsky 5")), emptyList(), mapOf(key to A)).single().basis)
        // No address on the card → name alone is never enough.
        assertEquals(Basis.UNKNOWN, PickupDemand.assign(listOf(offer(1, addr = null)), emptyList(), mapOf(key to A)).single().basis)
    }

    @Test fun pickupCountIsASampleByAreaAndHour() {
        val key = "Goomba | Rishon LeZion|Herzl 10"
        val assigned = PickupDemand.assign(listOf(offer(1), offer(2), offer(3, addr = null)), emptyList(), mapOf(key to A))
        val r = PickupDemand.pickupDensity(assigned, dev.yk.offercollector.core.AreaDensity.Window(0, 24))
        assertEquals(2, r.known); assertEquals(1, r.unknown)
        assertEquals(2, r.areas.single().offers)
    }

    // ---- never take a customer / drop-off line as the pickup address ----

    @Test fun dropOffLinesAreNeverTheBusinessAddress() {
        fun addr(vararg lines: String) = SnapshotAnalyzer.analyze("FrameLayout", lines.map(::tv), false).fields.pickup_address
        // Real order: name, then its address → stored.
        assertEquals("Herzl 10, Rishon LeZion 7525001", addr("₪30.00", "5 км", "Goomba | Rishon LeZion", "Herzl 10, Rishon LeZion 7525001", "Принять"))
        // A drop-off line without a postal-code-like number right after the name → nothing.
        assertNull(addr("₪30.00", "5 км", "Goomba | Rishon LeZion", "Bat Yam, 2 км от вас", "Ben Gurion 5, Bat Yam 5951001", "Принять"))
        // Customer name between → nothing (even if an address follows).
        assertNull(addr("₪30.00", "5 км", "Goomba | Rishon LeZion", "Иван", "Ben Gurion 5, Bat Yam 5951001", "Принять"))
        // Address line BEFORE the name (e.g. a destination header) is never taken.
        assertNull(addr("Ben Gurion 5, Bat Yam 5951001", "₪30.00", "5 км", "Goomba | Rishon LeZion", "Принять"))
        // Size tag, then name, then address → stored (tag skipped, real layout).
        assertEquals("Herzl 10, Rishon LeZion 7525001", addr("₪30.00", "5 км", "Тяжелая (более 16 кг)", "Goomba | Rishon LeZion", "Herzl 10, Rishon LeZion 7525001", "Принять"))
        assertFalse(SnapshotAnalyzer.analyze("FrameLayout", listOf(tv("₪30.00"), tv("5 км"), tv("Goomba"), tv("Herzl 10, 7525001")), false).fields.toString().contains("Иван"))
    }
}
