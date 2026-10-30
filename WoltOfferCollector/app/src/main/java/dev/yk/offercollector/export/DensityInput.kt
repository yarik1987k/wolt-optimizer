package dev.yk.offercollector.export

import dev.yk.offercollector.core.AreaDensity
import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.DetectedOffer
import dev.yk.offercollector.core.OfferLocations
import dev.yk.offercollector.core.OfferOutcomes
import dev.yk.offercollector.core.PickupDemand
import dev.yk.offercollector.core.StateClassifier
import dev.yk.offercollector.core.StateMarkers
import dev.yk.offercollector.data.LocationSampleEntity
import dev.yk.offercollector.data.ShiftSessionEntity
import dev.yk.offercollector.data.StateMarkerEventEntity
import kotlinx.serialization.json.Json

/** Everything the density screen needs, built from the app's own tables. Pure. */
data class DensityInput(
    val shifts: List<AreaDensity.Shift>,
    val samples: List<AreaDensity.Sample>,
    val marks: List<AreaDensity.StateMark>,
    val trips: List<AreaDensity.Trip>,
    val offers: List<AreaDensity.Offer>,
    /** Offers with distance, businesses and the trip they became (for pickup / accessible metrics). */
    val offerDetails: List<PickupDemand.OfferIn> = emptyList(),
    /** Real pickups: business → courier's area at the detected "picked up" step. */
    val pickups: List<PickupDemand.Observation> = emptyList(),
    /** Courier's area at detected drop-off steps — aggregated only, never linked to a customer. */
    val dropoffCells: List<Pair<Long, String>> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun build(
            shifts: List<ShiftSessionEntity>,
            samples: List<LocationSampleEntity>,
            stateEvents: List<StateMarkerEventEntity>,
            offers: List<DetectedOffer>,
            nowMs: Long,
        ): DensityInput {
            val markers = stateEvents.mapNotNull { e -> runCatching { json.decodeFromString<StateMarkers>(e.markersJson) }.getOrNull()?.let { e to it } }
            // Re-classified with the CURRENT rules, so a rule verified later (e.g. by Test A) upgrades old data too.
            val marks = markers.filter { (_, m) -> m.source == "SCREEN" || m.source == "NOTIFICATION" }.map { (e, m) ->
                val c = StateClassifier.classify(m)
                AreaDensity.StateMark(e.observedAtUtc, c.state.name, c.evidence.name)
            }
            val detected = DeliveryEventDetector.detect(markers.map { (e, m) -> DeliveryEventDetector.Item(e.observedAtUtc, e.candidateState, m) })
            val trips = detected.trips.map { AreaDensity.Trip(it.started_at_ms, it.completed_at_ms) }
            // Accepted offer → its trip: the ACCEPTED outcome's time is the DELIVERY_ACCEPTED event's time.
            val outcomes = OfferOutcomes.match(offers.map { OfferOutcomes.Window(it.firstSeenMs, it.lastSeenMs) }, detected.events)
            val acceptedTrip = offers.indices.associate { i ->
                offers[i].firstSeenMs to outcomes[i]?.takeIf { it.kind == OfferOutcomes.Kind.ACCEPTED }?.let { oc ->
                    detected.events.firstOrNull { it.type == "DELIVERY_ACCEPTED" && it.action_at_ms == oc.atMs }?.trip_index
                }
            }
            val details = offers.map { o ->
                PickupDemand.OfferIn(o.firstSeenMs, o.amountAgorot, o.distanceKm?.toDoubleOrNull(), o.pickupNames, acceptedTrip[o.firstSeenMs], o.pickupAddress)
            }
            val fixes = samples.map { PickupDemand.Fix(it.observedAtUtc, if (it.status == "OK") it.cellId else null, it.eventKey) }
            val pickups = PickupDemand.observations(
                details,
                detected.events.map { PickupDemand.Event(it.type, it.evidence, it.action_at_ms, it.trip_index) },
                detected.trips.map { PickupDemand.TripInfo(it.index, it.orders) },
                fixes,
            )
            val dropoffs = samples.filter { it.status == "OK" && it.cellId != null && it.trigger in setOf("DROPOFF_DONE", "DELIVERY_COMPLETED") }
                .map { (it.eventAtUtc ?: it.observedAtUtc) to it.cellId!! }
            val links = OfferLocations.link(
                offers,
                samples.map { OfferLocations.Sample(it.id, it.shiftId, it.observedAtUtc, it.trigger, it.status == "OK", it.cellId, it.accuracyM, it.locationAgeMs) },
                shifts.map { OfferLocations.Session(it.id, it.startedAtUtc, it.endedAtUtc ?: it.lastAliveUtc) },
                ExportService::iso,
            ).associateBy { it.offer_first_seen_at_utc }
            return DensityInput(
                shifts = shifts.map { AreaDensity.Shift(it.id, it.startedAtUtc, it.endedAtUtc ?: maxOf(it.lastAliveUtc, if (it.endedAtUtc == null) nowMs else 0)) },
                samples = samples.map { AreaDensity.Sample(it.shiftId, it.observedAtUtc, if (it.status == "OK") it.cellId else null, it.cellCenterLat, it.cellCenterLon, it.accuracyM) },
                marks = marks,
                trips = trips,
                offers = offers.map { o ->
                    val l = links[ExportService.iso(o.firstSeenMs)]
                    AreaDensity.Offer(o.firstSeenMs, o.amountAgorot, l?.grid_cell_id, o.pickupNames)
                },
                offerDetails = details,
                pickups = pickups,
                dropoffCells = dropoffs,
            )
        }
    }
}
