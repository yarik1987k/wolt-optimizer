package dev.yk.offercollector

import dev.yk.offercollector.core.DetectedOffer
import dev.yk.offercollector.core.OfferAnalytics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone must give the same numbers as the web engine. Fixture = the engine's 19 REAL offers
 * (29.10.2026) and its own TypeScript analyzeOffers() output for them.
 */
class OfferAnalyticsTest {
    @Serializable data class P(val firstSeenMs: Long, val amountAgorot: Long, val distanceMeters: Long? = null, val onScreenSeconds: Long)
    @Serializable data class S(val n: Int, val sum: Double, val mean: Double? = null, val median: Double? = null, val sd: Double? = null, val min: Double? = null, val max: Double? = null)
    @Serializable data class H(val hour: Int, val n: Int, val mean_amount_agorot: Double, val median_amount_agorot: Double, val sum_agorot: Double)
    @Serializable data class B(val from_agorot: Long, val to_agorot: Long, val n: Int)
    @Serializable data class T(val threshold_shekel: Int, val n_at_or_above: Int, val share: Double? = null)
    @Serializable data class E(
        val amount: S, val distance_m: S, val offers_with_distance: Int, val agorot_per_km_aggregate: Double? = null, val agorot_per_km: S,
        val by_hour: List<H>, val distribution: List<B>, val gaps_minutes: S, val thresholds: List<T>, val on_screen_seconds: S,
    )
    @Serializable data class F(val source: String, val points: List<P>, val expected: E)

    private val eps = 1e-9
    private fun same(e: S, a: OfferAnalytics.Summary, what: String) {
        assertEquals("$what n", e.n, a.n)
        assertEquals("$what sum", e.sum, a.sum, eps)
        listOf(e.mean to a.mean, e.median to a.median, e.sd to a.sd, e.min to a.min, e.max to a.max).forEachIndexed { i, (x, y) ->
            if (x == null) assertNull("$what #$i", y) else assertEquals("$what #$i", x, y!!, 1e-6)
        }
    }

    @Test fun matchesTheWebEngineOnRealOffers() {
        val f = Json { ignoreUnknownKeys = true }.decodeFromString<F>(javaClass.classLoader!!.getResource("engine_offer_analytics_2026-10-29.json")!!.readText())
        val r = OfferAnalytics.analyze(f.points.map { OfferAnalytics.Point(it.firstSeenMs, it.amountAgorot, it.distanceMeters, it.onScreenSeconds) })
        val e = f.expected
        assertEquals(19, r.count)
        same(e.amount, r.amount, "amount")
        same(e.distance_m, r.distanceM, "distance")
        assertEquals(e.offers_with_distance, r.offersWithDistance)
        assertEquals(e.agorot_per_km_aggregate!!, r.agorotPerKmAggregate!!, 1e-6)
        same(e.agorot_per_km, r.agorotPerKm, "₪/km")
        same(e.gaps_minutes, r.gapsMinutes, "gaps")
        same(e.on_screen_seconds, r.onScreenSeconds, "on screen")
        assertEquals(e.by_hour.map { listOf(it.hour, it.n) }, r.byHour.map { listOf(it.hour, it.n) })
        e.by_hour.zip(r.byHour).forEach { (x, y) ->
            assertEquals(x.mean_amount_agorot, y.meanAgorot, 1e-6); assertEquals(x.median_amount_agorot, y.medianAgorot, 1e-6); assertEquals(x.sum_agorot, y.sumAgorot, 1e-6)
        }
        assertEquals(e.distribution.map { Triple(it.from_agorot, it.to_agorot, it.n) }, r.distribution.map { Triple(it.fromAgorot, it.toAgorot, it.n) })
        assertEquals(e.thresholds.map { it.threshold_shekel to it.n_at_or_above }, r.thresholds.map { it.shekel to it.atOrAbove })
    }

    @Test fun emptyAndDistanceParsing() {
        val r = OfferAnalytics.analyze(emptyList())
        assertEquals(0, r.count); assertNull(r.amount.mean); assertNull(r.agorotPerKmAggregate)
        val o = DetectedOffer(1_000, 4_000, 3314, "7.4", null, null, pickupNames = emptyList(), snapshotCount = 3)
        val p = OfferAnalytics.point(o)
        assertEquals(7400L, p.distanceMeters)
        assertEquals(3L, p.onScreenSeconds)
        assertNull(OfferAnalytics.point(o.copy(distanceKm = null)).distanceMeters)
    }
}
