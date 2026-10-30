package dev.yk.offercollector

import dev.yk.offercollector.core.DebounceWindow
import dev.yk.offercollector.core.PackageValidator
import dev.yk.offercollector.core.RawNode
import dev.yk.offercollector.core.ScreenCategory
import dev.yk.offercollector.core.SnapshotAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotAnalyzerTest {
    private fun node(text: String?, id: String? = null, desc: String? = null) =
        RawNode("android.widget.TextView", id?.let { "com.example:id/$it" }, text, desc, false, 1)

    // A synthetic layout used only to exercise the analyzer; it is NOT a claim about Wolt's UI.
    private val offerLike = listOf(
        node("הצעה חדשה", "title"),
        node("₪24.90", "price"),
        node("איסוף: פיצה האט", "pickup"),
        node("3.4 ק\"מ", "distance"),
        node("2 עצירות", "stops"),
        node("דני לוי, הרצל 12", "customer"),
        node(null, "accept", desc = "קבל"),
    )

    @Test fun offerLikeScreenExtractsOnlySafeValues() {
        val a = SnapshotAnalyzer.analyze("OfferActivity", offerLike, recentSourceNotification = false)
        assertEquals(ScreenCategory.OFFER_CANDIDATE, a.category)
        assertEquals(listOf(2490L), a.fields.amounts_agorot)
        assertEquals(listOf("3.4"), a.fields.distances_km)
        assertEquals(listOf(2), a.fields.stops_counts)
        assertEquals(7, a.textNodeCount)
        val serialized = a.fields.toString()
        assertFalse(serialized.contains("דני"))
        assertFalse(serialized.contains("הרצל"))
        assertFalse(serialized.contains("האט"))
        assertTrue(a.fields.nodes.any { it.viewId == "customer" && it.redacted == "[TEXT×3] [#]" })
    }

    @Test fun missingPriceStaysEmptyNeverGuessed() {
        val a = SnapshotAnalyzer.analyze("OfferActivity", offerLike.filter { it.viewId?.endsWith("price") != true }, false)
        assertTrue(a.fields.amounts_agorot.isEmpty())
        assertEquals(ScreenCategory.OTHER, a.category)
    }

    @Test fun noTextMeansUnknownCategoryAndNoText() {
        val a = SnapshotAnalyzer.analyze("FlutterActivity", listOf(RawNode("android.view.View", null, null, null, false, 0)), false)
        assertFalse(a.hasText)
        assertEquals(ScreenCategory.UNKNOWN, a.category)
        assertEquals(1, a.accessibleNodeCount)
    }

    @Test fun fingerprintIgnoresPersonalTextButNotPrice() {
        val base = SnapshotAnalyzer.analyze("A", offerLike, false).fingerprint
        val otherCustomer = offerLike.map { if (it.viewId?.endsWith("customer") == true) it.copy(text = "רונית כהן, ביאליק 30") else it }
        assertEquals(base, SnapshotAnalyzer.analyze("A", otherCustomer, false).fingerprint)
        val otherPrice = offerLike.map { if (it.viewId?.endsWith("price") == true) it.copy(text = "₪31.00") else it }
        assertNotEquals(base, SnapshotAnalyzer.analyze("A", otherPrice, false).fingerprint)
    }

    @Test fun russianOfferCardIsAnOfferCandidate() {
        // Same shape as the real Wolt card seen on the phone (ru-RU): amount, distance, accept button.
        val card = listOf(node("2 доставки"), node("₪27.49"), node("4,5 км"), node("Принять"))
        val a = SnapshotAnalyzer.analyze("FrameLayout", card, recentSourceNotification = false)
        assertEquals(ScreenCategory.OFFER_CANDIDATE, a.category)
        assertEquals(listOf(2749L), a.fields.amounts_agorot)
        assertEquals(listOf("4.5"), a.fields.distances_km)
    }

    @Test fun fingerprintIgnoresEventSourceClass() {
        // Regression (real Wolt data): the event source alternated ViewGroup/FrameLayout on an unchanged screen.
        assertEquals(SnapshotAnalyzer.analyze("ViewGroup", offerLike, false).fingerprint, SnapshotAnalyzer.analyze("FrameLayout", offerLike, false).fingerprint)
    }

    @Test fun notificationProximityIsOnlySupportingEvidence() {
        val priceOnly = listOf(node("₪24.90"))
        assertEquals(ScreenCategory.OTHER, SnapshotAnalyzer.analyze("A", priceOnly, false).category)
        assertEquals(ScreenCategory.OFFER_CANDIDATE, SnapshotAnalyzer.analyze("A", priceOnly, true).category)
        assertEquals(ScreenCategory.OTHER, SnapshotAnalyzer.analyze("A", listOf(node("הצעה חדשה")), true).category)
    }

    @Test fun debounceNeverWaitsPastMaxWindow() {
        val d = DebounceWindow(quietMs = 250, maxWaitMs = 900)
        assertEquals(250, d.onEvent(0, false))
        assertEquals(250, d.onEvent(400, false))
        assertEquals(100, d.onEvent(800, false)) // only 100 ms left until the 900 ms cap
        assertEquals(0, d.onEvent(1000, false))
        assertEquals(4, d.eventsInBurst)
        d.reset()
        assertEquals(150, d.onEvent(5000, true))
    }

    @Test fun packageValidation() {
        val installed = setOf("com.wolt.courierapp", "com.whatsapp")
        val ok: (String) -> Boolean = { it in installed }
        assertEquals(PackageValidator.Result.Valid, PackageValidator.validate("com.whatsapp", "dev.yk.offercollector", ok))
        assertTrue(PackageValidator.validate(null, "dev.yk.offercollector", ok) is PackageValidator.Result.Invalid)
        assertTrue(PackageValidator.validate("not a package", "dev.yk.offercollector", ok) is PackageValidator.Result.Invalid)
        assertTrue(PackageValidator.validate("dev.yk.offercollector", "dev.yk.offercollector") { true } is PackageValidator.Result.Invalid)
        assertTrue(PackageValidator.validate("com.missing.app", "dev.yk.offercollector", ok) is PackageValidator.Result.Invalid)
        assertTrue(PackageValidator.looksLikeWolt("com.example.x", "Wolt Partner"))
        assertFalse(PackageValidator.looksLikeWolt("com.whatsapp", "WhatsApp"))
    }
}
