package dev.yk.offercollector.export

import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.DetectedOffer
import dev.yk.offercollector.core.OfferAnalytics
import dev.yk.offercollector.core.ShadowModel
import dev.yk.offercollector.core.DeliveryChains
import dev.yk.offercollector.core.TripOrders
import dev.yk.offercollector.data.ShiftSessionEntity
import java.time.Instant

/** Builds the shadow model's history from the app's own data. Pure. */
object ShadowHistory {
    /** The accepted offer of a trip: the latest card ≤ 5 min before the trip start whose amount is in the trip. */
    fun offerFor(trip: DeliveryEventDetector.Trip, offers: List<DetectedOffer>): DetectedOffer? =
        offers.filter { it.firstSeenMs <= trip.started_at_ms && trip.started_at_ms - it.firstSeenMs <= 5 * 60_000 && it.amountAgorot in trip.offer_amounts_agorot }
            .maxByOrNull { it.firstSeenMs }

    /** [orders]: route-timeline evidence per trip (TripOrders.forTrips); a trip without it is never a proven single order. */
    fun completed(
        trips: List<DeliveryEventDetector.Trip>,
        offers: List<DetectedOffer>,
        orders: List<TripOrders.Evidence> = emptyList(),
        chains: DeliveryChains.Result? = null,
    ): List<ShadowModel.Completed> = trips.map { t ->
        val ev = orders.firstOrNull { it.trip_index == t.index }
        val n = maxOf(t.orders, ev?.orders_low ?: 0)
        ShadowModel.Completed(
            startMs = t.started_at_ms,
            endMs = t.completed_at_ms,
            distanceKm = offerFor(t, offers)?.distanceKm?.toDoubleOrNull()?.takeIf { n == 1 },
            amountAgorot = t.offer_amounts_agorot.sum(),
            orders = n,
            startEvidence = t.start_evidence,
            endEvidence = t.end_evidence,
            // Proven single order AND alone in its chain: overlapping offers have no trustworthy per-offer boundaries.
            singleOrderVerified = ev?.singleOrderVerified == true && chains?.isolated(t.index) == true,
        )
    }

    /** Measured shift time per local hour of day (ms). Running shifts count up to [nowMs]. */
    fun shiftMsByHour(shifts: List<ShiftSessionEntity>, nowMs: Long): LongArray {
        val out = LongArray(24)
        for (s in shifts) {
            val end = s.endedAtUtc ?: maxOf(s.lastAliveUtc, nowMs)
            var t = s.startedAtUtc
            while (t < end) {
                val z = Instant.ofEpochMilli(t).atZone(OfferAnalytics.ZONE)
                val nextHour = z.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                val stop = minOf(end, nextHour)
                out[z.hour] += stop - t
                t = stop
            }
        }
        return out
    }
}

/** Joins immutable predictions with what actually happened (computed each time; predictions are never edited). */
object ShadowEvaluation {
    fun evaluate(
        predictions: List<dev.yk.offercollector.data.ShadowPredictionEntity>,
        detected: DeliveryEventDetector.Result,
        orders: List<TripOrders.Evidence> = emptyList(),
        chains: DeliveryChains.Result? = null,
    ): List<ShadowModel.Evaluated> = predictions.map { p ->
        // The trip this offer became: started within 5 min after the offer, with this amount.
        val trip = detected.trips.firstOrNull { it.started_at_ms in p.offerAtUtc..(p.offerAtUtc + 5 * 60_000) && p.amountAgorot in it.offer_amounts_agorot }
        val declined = detected.events.any { it.type == "OFFER_DECLINED" && it.action_at_ms in p.offerAtUtc..(p.offerAtUtc + 5 * 60_000) }
        val asCompleted = trip?.let {
            val ev = orders.firstOrNull { o -> o.trip_index == it.index }
            ShadowModel.Completed(
                it.started_at_ms, it.completed_at_ms, p.distanceKm, p.amountAgorot, maxOf(it.orders, ev?.orders_low ?: 0), it.start_evidence, it.end_evidence,
                singleOrderVerified = ev?.singleOrderVerified == true && chains?.isolated(it.index) == true,
            )
        }
        ShadowModel.Evaluated(
            decision = p.decision,
            predictedMin = p.durationMedianMin,
            predictedLow = p.durationLowMin,
            predictedHigh = p.durationHighMin,
            actualMin = asCompleted?.takeIf { it.qualifies }?.minutes,
            actualDecision = when {
                trip != null -> "ACCEPTED"
                declined -> "DECLINED"
                else -> "UNKNOWN"
            },
        )
    }
}
