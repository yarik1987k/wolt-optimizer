package dev.yk.offercollector

import dev.yk.offercollector.core.CandidateFields
import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.DetectedOffers
import dev.yk.offercollector.core.OfferScreen
import dev.yk.offercollector.core.StateMarkers
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.yk.offercollector.core.PickupNames
import dev.yk.offercollector.core.AreaDensity
import dev.yk.offercollector.core.ChainFacts
import dev.yk.offercollector.core.Insights
import dev.yk.offercollector.core.ShiftFacts
import dev.yk.offercollector.core.ShiftReview
import dev.yk.offercollector.core.StateClassifier
import dev.yk.offercollector.core.DeliveryChains
import dev.yk.offercollector.core.EtaValidation
import dev.yk.offercollector.core.TripOrders
import dev.yk.offercollector.export.ShadowHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** REAL shift (29.10.2026 20:09–23:14, Test B): 13 offers, 6 delivered, 7 declined at the end. */
class RealShiftTest {
    @Serializable private data class Row(val at_ms: Long, val candidate_state: String, val markers: StateMarkers)
    @Serializable private data class Card(val at_ms: Long, val fields: CandidateFields)
    @Serializable private data class Gps(val at_ms: Long, val lat: Double, val lon: Double, val speed_mps: Float? = null, val cell: String)
    @Serializable private data class Fixture(val source: String, val rows: List<Row>, val cards: List<Card>, val route_screens: List<Card> = emptyList(), val gps: List<Gps> = emptyList(), val task_screens: List<Card> = emptyList())

    private val f = Json { ignoreUnknownKeys = true }.decodeFromString<Fixture>(javaClass.classLoader!!.getResource("real_shift_2026-10-29_2009.json")!!.readText())
    private val r = DeliveryEventDetector.detect(f.rows.map { DeliveryEventDetector.Item(it.at_ms, it.candidate_state, it.markers) })
    private val offers = DetectedOffers.build(f.cards.map { OfferScreen(it.at_ms, it.fields) })
    private fun t(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Jerusalem")).toLocalTime().withNano(0).toString() } ?: "-"

    private fun hm(ms: Long?) = t(ms).take(5)

    @Test fun thirteenOffersOneCardEach() {
        assertEquals(13, offers.size)
        val bundle = offers.single { it.amountAgorot == 3109L }
        assertEquals(listOf("Tiv Ta'am | Rishon LeZion West", "Moses Shop Kosher | Bat Yam"), bundle.pickupNames)
        assertTrue(offers.flatMap { it.pickupNames }.none { PickupNames.isWoltLabel(it) })
    }

    @Test fun sixDeliveriesFromWoltTaskScreens() {
        // Accepted = card closed → pickup screen; picked up = pickup → drop-off screen.
        val accepted = r.events.filter { it.type == "DELIVERY_ACCEPTED" }
        assertEquals(listOf("20:10", "20:38", "21:21", "21:48", "22:12", "22:47"), accepted.map { hm(it.action_at_ms) })
        assertTrue(accepted.all { it.evidence == "ESTIMATED" && it.earliest_at_ms != null })
        val pickups = r.events.filter { it.type == "PICKUP_DONE" }
        assertEquals(listOf("20:17", "20:53", "21:34", "21:57", "22:25", "22:54"), pickups.map { hm(it.action_at_ms) })
        assertTrue(pickups.all { it.earliest_at_ms!! <= it.action_at_ms })
        assertEquals(6, r.trips.size)
        assertEquals(listOf(3086L, 4216L, 3109L, 7219L, 2595L, 1725L), r.trips.flatMap { it.offer_amounts_agorot })
    }

    @Test fun singlesCompleteBundlesStayUnknown() {
        val singles = r.trips.filter { it.orders == 1 }
        assertEquals(listOf("20:35", "21:21", "22:11", "23:09"), singles.map { hm(it.completed_at_ms) })
        assertTrue(singles.all { it.end_evidence == "ESTIMATED" && it.pickups == 1 })
        // "заказы 2" cards: only one drop-off step is visible → end UNKNOWN, never guessed.
        val bundles = r.trips.filter { it.orders == 2 }
        assertEquals(listOf(3109L, 2595L), bundles.flatMap { it.offer_amounts_agorot })
        assertTrue(bundles.all { it.end_evidence == "UNKNOWN" && it.completed_at_ms == null })
    }

    @Test fun sideMenuIsNotACompletion() {
        // The courier opened Wolt's side menu (earnings) mid-delivery at 21:42 and 22:05.
        assertTrue(r.events.none { it.type == "DELIVERY_COMPLETED" && hm(it.action_at_ms) in setOf("21:42", "22:05", "22:29") })
    }

    /**
     * Wolt's own statistics for the day: 10 completed deliveries, ₪219.50 without tips. The 6 trips'
     * offer amounts sum to exactly ₪219.50 — so no accepted offer was missed; the gap is orders INSIDE trips.
     */
    @Test fun tripsAreNotOrders() {
        assertEquals(21950L, r.trips.flatMap { it.offer_amounts_agorot }.sum())
        val ev = TripOrders.forTrips(r.trips, f.route_screens.map { OfferScreen(it.at_ms, it.fields) }, offers)
        assertEquals(listOf("SINGLE_ORDER", "MULTI_ORDER", "CHANGED", "MULTI_ORDER", "MULTI_ORDER", "MULTI_ORDER"), ev.map { it.status })
        assertEquals(listOf(1, 2, 1, 2, 3, 2), ev.map { it.orders_low })
        assertEquals(listOf(1, 2, 2, 2, 3, 2), ev.map { it.orders_high })
        // The route shows 11–12 drop-offs; Wolt counted 10. Unresolved — nothing is invented to close the gap.
        assertEquals(11, ev.sumOf { it.orders_low!! })
        assertEquals(12, ev.sumOf { it.orders_high!! })
        // "заказы 2" on a card is the count at one pickup, not the trip total (trip 5 routed 3 drop-offs).
        assertEquals(2, ev[4].card_order_count)
    }

    /**
     * The courier's account: offer A+B accepted, A delivered, C accepted while B on board, C delivered,
     * D accepted while B still on board, D delivered, then B. In the data: ₪72.19 (2 pickups) → ₪25.95
     * and ₪17.25 accepted while the ₪72.19 offer's second pickup stayed listed in the route.
     */
    @Test fun overlappingOffersFormOneChain() {
        val c = DeliveryChains.build(r.trips, (f.route_screens).map { OfferScreen(it.at_ms, it.fields) }, offers)
        assertEquals(listOf("IDLE", "IDLE", "IDLE", "IDLE", "ADDED_TO_ACTIVE_ROUTE", "ADDED_TO_ACTIVE_ROUTE"), c.offers.map { it.accept_context })
        assertEquals(listOf(0, 0, 0, 0, 1, 1), c.offers.map { it.carried_orders })
        assertEquals(listOf("NONE", "NONE", "NONE", "NONE", "SHAPE_MATCH", "SHAPE_MATCH"), c.offers.map { it.carried_link })
        // Chains: [20:10] [20:38] [21:21] [21:48 + 22:12 + 22:47]
        assertEquals(listOf(listOf(1), listOf(2), listOf(3), listOf(4, 5, 6)), c.chains.map { it.trip_indexes })
        assertEquals(listOf(true, true, true, false, false, false), c.offers.map { it.trip_boundary_valid })
        // New orders per offer: 1, 2, 1–2 (route changed 2→1 during pickup), 2, 2, 1 → 9–10. Wolt: 10.
        assertEquals(listOf(1, 2, 1, 2, 2, 1), c.offers.map { it.new_orders_low })
        assertEquals(listOf(1, 2, 2, 2, 2, 1), c.offers.map { it.new_orders_high })
        assertEquals(9 to 10, c.chains.sumOf { it.orders_low!! } to c.chains.sumOf { it.orders_high!! })
        // Revenue: each accepted offer counted once.
        assertEquals(21950L, c.chains.sumOf { it.revenue_agorot })
        assertEquals(listOf(3086L, 4216L, 3109L, 7219L + 2595L + 1725L), c.chains.map { it.revenue_agorot })
        // Only trip 1 — proven single order, alone in its chain, accepted idle — may train durations.
        val ev = TripOrders.forTrips(r.trips, f.route_screens.map { OfferScreen(it.at_ms, it.fields) }, offers)
        assertEquals(listOf(true, false, false, false, false, false), ShadowHistory.completed(r.trips, offers, ev, c).map { it.qualifies })
    }

    private fun facts(): ChainFacts.Result {
        val shots = f.task_screens.map { OfferScreen(it.at_ms, it.fields) }
        val chains = DeliveryChains.build(r.trips, shots, offers)
        return ChainFacts.build(r, chains, shots, offers, f.gps.map { ChainFacts.GpsPoint(it.at_ms, it.lat, it.lon, it.speed_mps, it.cell) })
    }

    /** Sliding "заказ доставлен" needs Wolt on screen: 10 slides = Wolt's official 10 completed deliveries. */
    @Test fun completionSlidesMatchWoltsCount() {
        val slides = ChainFacts.slides(f.task_screens.map { OfferScreen(it.at_ms, it.fields) })
        assertEquals(
            listOf("20:35:50", "21:05:37", "21:21:23", "21:47:18", "21:47:26", "22:11:27", "22:33:23", "22:46:38", "23:01:59", "23:09:15"),
            slides.map { t(it) },
        )
        val c = facts().chains
        assertEquals(listOf(1, 2, 2, 5), c.map { it.completion_slides_ms.size })
        assertTrue(c.all { it.end_evidence == "OBSERVED_SLIDE" })
        // Chain 3 (₪31.09) now has an observed end; chain 4 ends with B at 23:09:15.
        assertEquals("21:47:26", t(c[2].end_earliest_ms)); assertEquals("23:09:15", t(c[3].end_earliest_ms))
        assertTrue(c.all { it.end_latest_ms!! - it.end_earliest_ms!! <= 10_000 })
        // ₪31.09: route showed 1–2 orders, 2 slides 8 s apart → two orders at one merged stop.
        assertTrue(c[2].missing.none { it.startsWith("observed") })
    }

    @Test fun chainFactsKeepObservedDisplayAndEstimatesApart() {
        val x = facts()
        assertEquals(listOf(3086L, 4216L, 3109L, 11539L), x.chains.map { it.revenue_agorot })
        assertTrue(x.chains.all { it.duration_min_ms != null && it.duration_min_ms!! <= it.duration_max_ms!! })
        val c4 = x.chains[3]
        assertTrue(c4.gps_distance_reliable)
        // Route log: every change of the active route with Wolt's displayed minutes.
        assertTrue(c4.route_changes.size >= 3)
        assertTrue(c4.route_changes.any { it.added_stops > 0 && it.active_dropoffs == 3 })
        assertTrue(c4.marker_minutes_shown.isNotEmpty())
        // Case B offers: ordered before/after routes with per-stop targets, ages, and no counterfactual figure.
        val o5 = x.offers.single { it.trip_index == 5 }
        assertEquals(1, o5.active_orders_before)
        assertEquals(listOf(0, 1, 2, 3), o5.route_before!!.stops.map { it.position })
        assertTrue(o5.route_after!!.stops.any { it.target_at_ms != null })
        assertTrue(o5.route_before_age_ms!! > 0 && o5.route_after_delay_ms!! >= 0)
        assertTrue(o5.drop_screen_before != null && o5.drop_screen_before_age_ms!! < 60_000)
        // A completed (22:11:27) between the before-route (21:5x) and acceptance: the route changed for another reason too.
        assertEquals(1, o5.completions_between_before_and_accept)
        assertEquals(true, o5.route_changed_for_other_reasons)
        // B's target did not move when C was added.
        assertEquals(0.0, o5.carried_target_shift_min ?: 0.0, 1.5)
        assertTrue(x.offers.all { it.counterfactual_duration == "UNKNOWN" })
        assertEquals(2595L, o5.offer_amount_agorot); assertEquals(1.6, o5.card_distance_km!!, 1e-9); assertEquals(6, o5.card_minutes)
        assertTrue(x.offers.filter { it.accept_context == "IDLE" }.all { it.route_before == null && it.drop_screen_before == null })
    }

    @Test fun shiftSplitsChainTimeAndIdleTime() {
        val x = facts()
        val marks = f.rows.filter { it.markers.source == "SCREEN" || it.markers.source == "NOTIFICATION" }
            .map { StateClassifier.classify(it.markers).let { c -> AreaDensity.StateMark(it.at_ms, c.state.name, c.evidence.name) } }
        val start = f.gps.first().at_ms
        val end = f.gps.last().at_ms
        val s = ShiftFacts.build(
            listOf(AreaDensity.Shift("s", start, end)),
            f.gps.map { AreaDensity.Sample("s", it.at_ms, it.cell, it.lat, it.lon, 8) },
            marks, x.chains, f.gps.map { ChainFacts.GpsPoint(it.at_ms, it.lat, it.lon, it.speed_mps, it.cell) }, end,
        ).single()
        assertEquals(21950L, s.revenue_agorot)
        assertEquals(10, s.completion_slides)
        assertEquals(0, s.chains_with_unknown_end)
        val total = end - start
        assertTrue(s.chain_ms_high in 1..total)
        // Every millisecond of the shift lands in exactly one bucket (chain time = DELIVERY pieces).
        val outside = s.verified_idle_ms + s.online_unverified_ms + s.offline_ms + s.unknown_ms
        assertTrue("outside=$outside chain=${s.chain_ms_high} total=$total", outside in (total - s.chain_ms_high - 60_000)..(total - s.chain_ms_low + 60_000))
        assertTrue(s.verified_idle_ms > 0)
        // Repositioning: the next offer came within minutes each time — little time and almost no distance between chains.
        assertTrue(s.between_chains_ms!! in 5 * 60_000L..15 * 60_000L)
        assertTrue(s.between_chains_km!! < 1.5) // ≈1.1 km: 0.6 before the first offer, 0.5 after the last
        // Inside chains a large share of time was stationary by GPS speed (waiting / handover / traffic — not separable yet).
        val still = x.chains.sumOf { it.gps_stationary_ms!! }
        val moving = x.chains.sumOf { it.gps_moving_ms!! }
        assertTrue("stationary=$still moving=$moving", still in 60 * 60_000L..80 * 60_000L && moving > still)
        assertTrue(x.chains.all { it.first_pickup_done_ms != null && it.pickup_phase_stationary_ms != null && it.delivery_phase_stationary_ms != null })
    }

    /** What Wolt's route minutes mean, checked against OBSERVED completions (eta-validation-1). */
    @Test fun woltRouteMinutesAreTargetsNotLiveEtas() {
        val r = EtaValidation.build(f.task_screens.map { OfferScreen(it.at_ms, it.fields) })
        // Snapshot time + minutes is mostly constant over time (40 of 45 frame pairs ≥30 s apart) → a target clock per stop.
        assertEquals(45, r.semantics.target_pairs_compared)
        assertEquals(40, r.semantics.target_pairs_stable)
        assertEquals(5, r.semantics.stale_frames)
        // Drop-off marker = |now − target| on most screens.
        assertTrue(r.semantics.marker_matches * 10 >= r.semantics.marker_checks * 8)
        // Every completion gets an identity from the drop-off screen or the route — none guessed.
        assertEquals(10, r.checks.size)
        assertTrue(r.checks.none { it.identity == "UNKNOWN" })
        // Completion vs the route target (identity matched on the route): always later, +2.9 … +20.5 min.
        val vsRoute = r.checks.mapNotNull { it.error_vs_first_route_target_min }
        assertEquals(8, vsRoute.size)
        assertTrue(vsRoute.all { it > 0 })
        assertEquals(2.86, vsRoute.min(), 0.05); assertEquals(20.47, vsRoute.max(), 0.05)
        // B: target 23:00 from 21:48 on (81 min ahead), unchanged by the two added offers; completed 23:09:15.
        val b = r.checks.last()
        assertEquals("23:09:15", t(b.completed_at_ms))
        assertEquals(9.25, b.error_vs_drop_screen_min!!, 0.05)
        assertEquals(81.1, b.lead_min!!, 0.1)
        // Two drop-screen times never appeared as a route target — reported, not forced onto a stop.
        assertEquals(2, r.checks.count { it.notes.any { n -> n.startsWith("target not seen") } })
    }

    private fun shiftFacts(x: ChainFacts.Result): ShiftFacts.Shift {
        val marks = f.rows.filter { it.markers.source == "SCREEN" || it.markers.source == "NOTIFICATION" }
            .map { StateClassifier.classify(it.markers).let { c -> AreaDensity.StateMark(it.at_ms, c.state.name, c.evidence.name) } }
        val start = f.gps.first().at_ms
        val end = f.gps.last().at_ms
        return ShiftFacts.build(
            listOf(AreaDensity.Shift("s", start, end)),
            f.gps.map { AreaDensity.Sample("s", it.at_ms, it.cell, it.lat, it.lon, 8) },
            marks, x.chains, f.gps.map { ChainFacts.GpsPoint(it.at_ms, it.lat, it.lon, it.speed_mps, it.cell) }, end,
        ).single()
    }

    /** After-shift review of the real evening, at ₪1.10/km. */
    @Test fun reviewOfTheRealShift() {
        val x = facts()
        val r = ShiftReview.build(shiftFacts(x), x.chains, 13, 6, 7, 110, precise = false)
        assertEquals(21950L, r.revenueAgorot)
        // Net per full shift hour ≈ ₪58 (GPS distance range → a range).
        assertTrue(r.netPerShiftHourAgorot!!.low in 5700..5800 && r.netPerShiftHourAgorot!!.high in 5800..5900)
        assertEquals(10, r.deliveries)
        assertEquals(ShiftReview.Evidence.ESTIMATE, r.vehicleCostEvidence) // the per-km cost is an assumption
        // Time: mostly in deliveries; little between them.
        val t = r.time!!
        assertTrue(t.driving + t.standingInDeliveries > 0.85 && t.betweenDeliveries < 0.1)
        // Worst = the ₪42.16 / 43 min chain; best = the chain with the two added offers.
        assertEquals(4216L, r.worst!!.revenueAgorot)
        assertEquals(11539L, r.best!!.revenueAgorot)
        // No cost per km → no net figure, and it says why.
        val noCost = ShiftReview.build(shiftFacts(x), x.chains, 13, 6, 7, null, precise = false)
        assertEquals(null, noCost.netPerShiftHourAgorot)
        assertTrue(noCost.missing.any { it.contains("עלות רכב") })
    }

    /** Plan and "what works" from one shift: everything is a hypothesis or not enough data — nothing ranked. */
    @Test fun planFromOneShiftIsNeverAVerdict() {
        val x = facts()
        val sf = shiftFacts(x)
        val review = ShiftReview.build(sf, x.chains, 13, 6, 7, 110, precise = false)
        val decisions = offers.map { o ->
            val accepted = r.trips.any { o.amountAgorot in it.offer_amounts_agorot && it.started_at_ms - o.firstSeenMs in 0..300_000 }
            Insights.OfferDecision(o.firstSeenMs, o.amountAgorot, o.distanceKm?.toDoubleOrNull(), if (o.amountAgorot in setOf(2595L, 1725L)) "ACTIVE_ROUTE" else "IDLE", if (accepted) "ACCEPTED" else "DECLINED")
        }
        val h = Insights.History(listOf(sf), x.chains, decisions, f.gps.map { ChainFacts.GpsPoint(it.at_ms, it.lat, it.lon, it.speed_mps, it.cell) }, EtaValidation.build(f.task_screens.map { OfferScreen(it.at_ms, it.fields) }), listOf(review))
        val w = Insights.timeWindows(h).single()
        assertEquals("ה׳ \u206618–21\u2069", w.label) // Thursday 29.10.2026, started 20:09
        // Hour 3 of the shift has the two offers added to the active route (22:11, 22:46) — not ₪0.
        assertEquals(2595L + 1725L, Insights.byShiftHour(h)[2].revenuePerHourAgorot.single())
        assertEquals(Insights.Label.HYPOTHESIS, w.evidence)
        assertTrue(Insights.waitSpots(h).all { it.evidence == Insights.Label.HYPOTHESIS })
        assertEquals(listOf(1, 2, 3), Insights.byShiftHour(h).map { it.index })
        // Rule "decline offers added while carrying": would have declined the 2 accepted add-ons.
        val rule = Insights.ruleCheck(h, acceptAddedOffers = false, minPriceAgorot = null)!!
        assertEquals(13, rule.offers)
        assertEquals(2, rule.wouldDeclineAccepted)
        val fs = Insights.findings(h)
        assertTrue(fs.none { it.label == Insights.Label.REPEATED })
        assertEquals(Insights.Label.TESTED, fs.single { it.title.contains("Wolt") }.label)
        assertEquals(Insights.Label.MEASURED, fs.single { it.title.contains("נטו") }.label)
    }

    @Test fun decisionTimeContext() {
        val items = f.rows.map { DeliveryEventDetector.Item(it.at_ms, it.candidate_state, it.markers) }
        // 20:10 offer came from the home screen; 22:11 and 22:46 offers arrived on a drop-off screen.
        assertEquals("IDLE", DeliveryChains.contextAt(items, offers[0].firstSeenMs))
        assertEquals("ACTIVE_ROUTE", DeliveryChains.contextAt(items, offers.first { it.amountAgorot == 2595L }.firstSeenMs))
        assertEquals("ACTIVE_ROUTE", DeliveryChains.contextAt(items, offers.first { it.amountAgorot == 1725L }.firstSeenMs))
    }

    @Test fun sevenDeclinesAtTheEnd() {
        assertEquals(7, r.events.count { it.type == "OFFER_DECLINED" })
        assertEquals(0, r.events.count { it.type == "OFFER_ENDED_UNKNOWN" })
    }
}
