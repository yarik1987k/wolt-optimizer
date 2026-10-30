package dev.yk.offercollector.service

import dev.yk.offercollector.App
import dev.yk.offercollector.core.CandidateFields
import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.DetectedOffers
import dev.yk.offercollector.core.OfferScreen
import dev.yk.offercollector.core.ShadowModel
import dev.yk.offercollector.core.StateMarkers
import dev.yk.offercollector.core.DeliveryChains
import dev.yk.offercollector.core.TripOrders
import dev.yk.offercollector.data.ShadowPredictionEntity
import dev.yk.offercollector.export.ExportService
import dev.yk.offercollector.export.ShadowHistory
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shadow mode: silently stores what shadow-v1 would classify when a NEW offer card appears.
 * Passive — no notification, no sound, no screen. Runs off the capture path.
 */
object ShadowRecorder {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var last: Pair<Long, Long>? = null // amount, seen at

    fun onScreen(app: App, f: CandidateFields, atMs: Long) {
        if (!DetectedOffers.isOfferCard(f)) return
        val amount = f.amounts_agorot.first()
        val prev = last
        last = amount to atMs
        if (prev != null && prev.first == amount && atMs - prev.second <= DetectedOffers.SAME_OFFER_GAP_MS) return // same card
        app.appScope.launch(Dispatchers.Default) { runCatching { record(app, f, amount, atMs) } }
    }

    private suspend fun record(app: App, f: CandidateFields, amount: Long, atMs: Long) {
        val db = app.db
        val s = app.settings.current()
        val screens = db.screenEvents().all().mapNotNull { e -> ExportService.parseFields(e.redactedCandidateFieldsJson)?.let { OfferScreen(e.observedAtUtc, it, e.id) } }
            .filter { it.observedAtMs < atMs }
        val offers = DetectedOffers.build(screens)
        val items = db.stateMarkerEvents().all().mapNotNull { e ->
            runCatching { json.decodeFromString<StateMarkers>(e.markersJson) }.getOrNull()?.let { DeliveryEventDetector.Item(e.observedAtUtc, e.candidateState, it) }
        }
        // History = everything BEFORE this offer (a prediction never sees the future).
        val trips = DeliveryEventDetector.detect(items.filter { it.atMs < atMs }).trips
        val shifts = db.shiftSessions().all()
        val chains = DeliveryChains.build(trips, screens, offers)
        val history = ShadowModel.History(ShadowHistory.completed(trips, offers, TripOrders.forTrips(trips, screens, offers), chains), ShadowHistory.shiftMsByHour(shifts.filter { it.startedAtUtc < atMs }, atMs))
        val open = shifts.lastOrNull { it.endedAtUtc == null && it.startedAtUtc <= atMs }
        val courier = db.locationSamples().all().lastOrNull { it.status == "OK" && it.observedAtUtc in (atMs - 120_000)..atMs }?.cellId
        val key = f.pickup_names.singleOrNull()?.let { n -> f.pickup_address?.let { PickupAddresses.key(n, it) } }
        val place = key?.let { k -> db.pickupPlaces().all().firstOrNull { it.key == k && it.geoStatus == "OK" } }
        val input = ShadowModel.Input(
            offerAtMs = atMs,
            amountAgorot = amount,
            distanceKm = f.distances_km.firstOrNull()?.toDoubleOrNull(),
            courierCell = courier,
            pickupCell = place?.cellId,
            pickupBasis = if (place != null) "ADDRESS" else "UNKNOWN",
            shiftId = open?.id,
            plannedShiftEndMs = open?.plannedEndUtc,
            vehicleCostPerKmAgorot = s.vehicleCostPerKmAgorot,
            marginPct = s.shadowMarginPct,
            routeContext = DeliveryChains.contextAt(items, atMs),
        )
        val p = ShadowModel.predict(input, history)
        val inputs = buildJsonObject {
            put("duration_trip_ids", JsonArray(p.duration?.usedTripIds.orEmpty().map(::JsonPrimitive)))
            put("hourly_rate_trip_ids", JsonArray(p.historicalShiftHourlyRate?.usedTripIds.orEmpty().map(::JsonPrimitive)))
            p.historicalShiftHourlyRate?.let { put("hourly_rate_shift_hours", it.shiftHours) }
            put("history_completed_total", history.completed.size)
            put("history_qualifying_single_orders", history.completed.count { it.qualifies })
            put("route_context", input.routeContext)
            put("chains_version", DeliveryChains.VERSION)
        }
        db.shadowPredictions().insert(
            ShadowPredictionEntity(
                id = UUID.randomUUID().toString(), createdAtUtc = System.currentTimeMillis(), offerAtUtc = atMs,
                ruleVersion = ShadowModel.RULE_VERSION, marginPct = input.marginPct, amountAgorot = amount, distanceKm = input.distanceKm,
                courierCell = input.courierCell, pickupCell = input.pickupCell, pickupBasis = input.pickupBasis, shiftId = input.shiftId,
                plannedShiftEndUtc = input.plannedShiftEndMs, remainingShiftMin = p.remainingShiftMin,
                durationMedianMin = p.duration?.medianMin, durationLowMin = p.duration?.lowMin, durationHighMin = p.duration?.highMin,
                durationConfidence = p.duration?.confidence, durationDistanceMatched = p.duration?.distanceMatched,
                vehicleCostPerKmAgorot = input.vehicleCostPerKmAgorot, vehicleCostAgorot = p.vehicleCostAgorot,
                netPerHourMedianAgorot = p.netPerHourMedian, netPerHourSlowAgorot = p.netPerHourAtSlow, netPerHourFastAgorot = p.netPerHourAtFast,
                historicalShiftHourlyRateAgorot = p.historicalShiftHourlyRate?.agorotPerHour, baselineTimeValueAgorot = p.baselineTimeValueAgorot,
                decision = p.decision, reasonsJson = Json.encodeToString(p.reasons), inputsJson = inputs.toString(),
            ),
        )
    }
}
