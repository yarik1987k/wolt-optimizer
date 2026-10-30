package dev.yk.offercollector

import dev.yk.offercollector.core.DeliveryChains
import dev.yk.offercollector.core.TripOrders

import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.DetectedOffer
import dev.yk.offercollector.data.ShadowPredictionEntity
import dev.yk.offercollector.data.ShiftSessionEntity
import dev.yk.offercollector.export.ShadowEvaluation
import dev.yk.offercollector.export.ShadowHistory
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic inputs (test only). */
class ShadowHistoryTest {
    private val MIN = 60_000L
    private val t = ZonedDateTime.of(2026, 11, 2, 18, 30, 0, 0, ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()
    private fun trip(start: Long, end: Long?, amounts: List<Long>, orders: Int = 1, endEv: String = "OBSERVED") =
        DeliveryEventDetector.Trip(1, start, "ESTIMATED", end, endEv, orders, 1, 1, amounts)
    private fun offer(at: Long, amount: Long, km: String?) = DetectedOffer(at, at + 5_000, amount, km, null, null, pickupNames = emptyList(), snapshotCount = 1)

    @Test fun distanceOnlyForSingleOrderTripsAndTheMatchingCard() {
        val offers = listOf(offer(t, 3000, "5"), offer(t + 20 * MIN, 4500, "7"))
        val single = ShadowHistory.completed(listOf(trip(t + MIN, t + 26 * MIN, listOf(3000))), offers).single()
        assertEquals(5.0, single.distanceKm!!, 1e-9)
        val bundle = ShadowHistory.completed(listOf(trip(t + 21 * MIN, t + 60 * MIN, listOf(4500, 2000), orders = 2)), offers).single()
        assertNull(bundle.distanceKm) // bundles never feed the single-order duration model
        val noCard = ShadowHistory.completed(listOf(trip(t + 40 * MIN, t + 60 * MIN, listOf(9999))), offers).single()
        assertNull(noCard.distanceKm)
    }

    @Test fun shiftTimeIsSplitByLocalHour() {
        val s = ShiftSessionEntity("s", t, t + 90 * MIN, "USER", true, t + 90 * MIN) // 18:30–20:00
        val h = ShadowHistory.shiftMsByHour(listOf(s), t + 200 * MIN)
        assertEquals(30 * MIN, h[18]); assertEquals(60 * MIN, h[19]); assertEquals(0L, h[20])
    }

    private fun pred(at: Long, amount: Long) = ShadowPredictionEntity(
        "p$at", at, at, "shadow-v1", 15, amount, 5.0, null, null, "UNKNOWN", null, null, null,
        25.0, 20.0, 30.0, "LOW", true, null, null, null, null, null, null, null, "ACCEPT", "[]", "{}",
    )

    @Test fun evaluationUsesOnlyQualifyingCompletedTripsForAccuracy() {
        val r = DeliveryEventDetector.Result(
            events = listOf(DeliveryEventDetector.DeliveryEvent("OFFER_DECLINED", "ESTIMATED", t + 61 * MIN, "x", null, "x")),
            trips = listOf(
                trip(t + MIN, t + 28 * MIN, listOf(3000)), // single, complete → accuracy
                trip(t + 31 * MIN, t + 80 * MIN, listOf(4000, 1000), orders = 2), // bundle → accepted, no accuracy
                trip(t + 91 * MIN, null, listOf(2500), endEv = "UNKNOWN"), // incomplete → accepted, no accuracy
            ),
        )
        val proven = listOf(TripOrders.Evidence(r.trips[0].index, 1, 1, 1, 1, 1, status = "SINGLE_ORDER", orders_low = 1, orders_high = 1))
        val alone = DeliveryChains.Result(listOf(DeliveryChains.AcceptedOffer(r.trips[0].index, r.trips[0].started_at_ms, 3000, 1, accept_context = "IDLE", trip_boundary_valid = true)), emptyList())
        val ev = ShadowEvaluation.evaluate(listOf(pred(t, 3000), pred(t + 30 * MIN, 4000), pred(t + 60 * MIN, 2200), pred(t + 90 * MIN, 2500)), r, proven, alone)
        assertEquals(listOf("ACCEPTED", "ACCEPTED", "DECLINED", "ACCEPTED"), ev.map { it.actualDecision })
        assertEquals(27.0, ev[0].actualMin!!, 1e-9)
        assertNull(ev[1].actualMin); assertNull(ev[3].actualMin)
        // Without route evidence the same trip is not a proven single order → no accuracy sample.
        assertNull(ShadowEvaluation.evaluate(listOf(pred(t, 3000)), r).single().actualMin)
        // Proven single order but sharing a chain with another offer → its trip boundary is not trusted.
        val shared = DeliveryChains.Result(listOf(alone.offers[0].copy(trip_boundary_valid = false)), emptyList())
        assertNull(ShadowEvaluation.evaluate(listOf(pred(t, 3000)), r, proven, shared).single().actualMin)
    }
}
