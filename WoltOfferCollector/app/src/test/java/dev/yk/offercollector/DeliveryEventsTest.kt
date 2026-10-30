package dev.yk.offercollector

import dev.yk.offercollector.core.DeliveryEventDetector
import dev.yk.offercollector.core.DeliveryEventDetector.Item
import dev.yk.offercollector.core.SliderMarker
import dev.yk.offercollector.core.StateMarkers
import dev.yk.offercollector.core.TapMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic timelines (minutes → ms). Screen content is invented; only the SHAPES matter. */
class DeliveryEventsTest {
    private fun min(m: Double) = (m * 60_000).toLong()
    private fun offer(at: Double, agorot: Long, count: Int? = null) =
        Item(min(at), "OFFER_PRESENTED", StateMarkers(source = "SCREEN", offer_card = true, offer_amount_agorot = agorot, offer_order_count = count))
    private fun tap(at: Double, kind: String, word: String?, gesture: String = "TAP") =
        Item(min(at), "X", StateMarkers(source = "ACTION", tap = TapMarker(kind = kind, gesture = gesture, word = word, label_redacted = "[UI:$word]")))
    private fun step(at: Double, slider: String) = Item(
        min(at), "DELIVERY_ACTIVE",
        StateMarkers(source = "SCREEN", sliders = listOf(SliderMarker("TaskSwipeButton", "View", "[UI:$slider]", listOf(slider))), delivery_step_words = listOf(slider)),
    )
    private fun home(at: Double) = Item(min(at), "UNKNOWN", StateMarkers(source = "SCREEN", home_screen = true, view_ids = listOf("StatusButtons.SwipeButton", "BottomSheet.PreviousTask")))
    private fun summary(at: Double) = Item(min(at), "UNKNOWN", StateMarkers(source = "SCREEN", ui_words = listOf("доход", "доставки")))
    private fun gap(at: Double) = Item(min(at), "UNKNOWN", StateMarkers(source = "SYSTEM", system_event = "LISTENER_DISCONNECTED"))

    @Test fun singleDeliveryHasObservedStartAndEnd() {
        val r = DeliveryEventDetector.detect(listOf(
            offer(0.0, 3502), tap(0.05, "ACCEPT", "принять"), step(0.1, "забрал"),
            tap(10.0, "DELIVERY_STEP", "забрал", "SLIDE"), step(10.1, "доставлено"),
            tap(25.0, "DELIVERY_STEP", "доставлено", "SLIDE"), home(25.1),
        ))
        assertEquals(listOf("DELIVERY_ACCEPTED", "PICKUP_DONE", "DROPOFF_DONE", "DELIVERY_COMPLETED"), r.events.map { it.type })
        assertTrue(r.events.all { it.evidence == "OBSERVED" && !it.rule_validated })
        val t = r.trips.single()
        assertEquals("OBSERVED" to "OBSERVED", t.start_evidence to t.end_evidence)
        assertEquals(min(24.95), t.duration_ms)
        assertEquals(listOf(3502L), t.offer_amounts_agorot)
        assertEquals(1 to 1, t.pickups to t.dropoffs)
    }

    @Test fun acceptTapWithoutConfirmingScreenIsUnknown() {
        // Tapped принять but the offer had expired: home comes back. No trip; the card's end is unknown.
        val r = DeliveryEventDetector.detect(listOf(offer(0.0, 2749), tap(0.05, "ACCEPT", "принять"), home(0.1)))
        assertEquals(listOf("ACTION_UNCONFIRMED" to "UNKNOWN", "OFFER_ENDED_UNKNOWN" to "UNKNOWN"), r.events.map { it.type to it.evidence })
        assertTrue(r.trips.isEmpty())
        val r2 = DeliveryEventDetector.detect(listOf(tap(0.0, "ACCEPT", "принять"), step(1.0, "забрал")))
        assertEquals("accept_tap_without_following_screen", r2.events.single().confirmation)
    }

    @Test fun cardDisappearingWithNothingRecognisableAfterItIsUnknown() {
        // The 09:40 / 09:49 / 10:24 cards on 29.10: card → home, no dialog, no delivery screen.
        val r = DeliveryEventDetector.detect(listOf(offer(0.0, 2749), home(0.15)))
        assertEquals("OFFER_ENDED_UNKNOWN" to "UNKNOWN", r.events.single().type to r.events.single().evidence)
        assertTrue(r.trips.isEmpty())
    }

    @Test fun screenSequenceWithoutActionEventsGivesOnlyEstimatedEvents() {
        // Wolt sends no tap events: card → delivery screen → next step → home, all by APPEARING screens.
        val r = DeliveryEventDetector.detect(listOf(offer(0.0, 3502), step(0.1, "забрал"), step(10.0, "доставлено"), home(25.0)))
        assertEquals(listOf("DELIVERY_ACCEPTED", "PICKUP_DONE", "DROPOFF_DONE", "DELIVERY_COMPLETED"), r.events.map { it.type })
        assertTrue(r.events.all { it.evidence == "ESTIMATED" && it.path == "SCREEN_SEQUENCE" })
        val t = r.trips.single()
        assertEquals("ESTIMATED" to "ESTIMATED", t.start_evidence to t.end_evidence)
        assertNull(t.duration_ms) // never presented as an observed duration
        assertEquals(min(25.0) - min(0.1), t.duration_estimated_ms)
    }

    @Test fun finishSlideWithoutConfirmationLeavesEndUnknown() {
        val r = DeliveryEventDetector.detect(listOf(
            tap(0.0, "ACCEPT", "принять"), step(0.1, "доставлено"),
            tap(20.0, "DELIVERY_STEP", "доставлено", "SLIDE"), step(20.1, "доставлено").copy(atMs = min(20.1)).let {
                // same delivery screen, still with the step slider, nothing changed = not confirmed as next stop either
                Item(it.atMs, it.candidateState, it.markers.copy(sliders = emptyList()))
            },
        ))
        assertEquals("ACTION_UNCONFIRMED", r.events.last().type)
        val t = r.trips.single()
        assertEquals("UNKNOWN", t.end_evidence)
        assertNull(t.duration_ms)
    }

    @Test fun bundleWithTwoOrdersAndTwoDropoffsIsOneTrip() {
        val r = DeliveryEventDetector.detect(listOf(
            offer(0.0, 2500), tap(0.05, "ACCEPT", "принять"), step(0.1, "забрал"),
            offer(3.0, 1800), tap(3.05, "ACCEPT", "принять"), step(3.1, "забрал"), // second order joins the trip
            tap(8.0, "DELIVERY_STEP", "забрал", "SLIDE"), step(8.1, "забрал"),
            tap(9.0, "DELIVERY_STEP", "забрал", "SLIDE"), step(9.1, "доставлено"),
            tap(20.0, "DELIVERY_STEP", "доставлено", "SLIDE"), step(20.1, "доставлено"), // first drop → next stop
            tap(31.0, "DELIVERY_STEP", "доставлено", "SLIDE"), summary(31.1), // last drop → summary
        ))
        val t = r.trips.single()
        assertEquals(2, t.orders)
        assertEquals(listOf(2500L, 1800L), t.offer_amounts_agorot)
        assertEquals(2 to 2, t.pickups to t.dropoffs)
        assertEquals("OBSERVED", t.end_evidence)
        assertEquals(min(30.95), t.duration_ms)
        assertEquals(listOf("screen.next_stop", "screen.completion_summary"), r.events.filter { it.type == "DROPOFF_DONE" }.map { it.confirmation })
        assertEquals(1, r.events.count { it.type == "DELIVERY_COMPLETED" })
    }

    @Test fun waitBetweenTripsOnlyFromObservedEndToObservedStart() {
        val r = DeliveryEventDetector.detect(listOf(
            tap(0.0, "ACCEPT", "принять"), step(0.1, "доставлено"),
            tap(20.0, "DELIVERY_STEP", "доставлено", "SLIDE"), home(20.1),
            tap(27.0, "ACCEPT", "принять"), step(27.1, "доставлено"),
            tap(45.0, "DELIVERY_STEP", "доставлено", "SLIDE"), offer(45.1, 3000), // ends straight into a new offer card
        ))
        assertEquals(2, r.trips.size)
        assertNull(r.trips[0].wait_before_ms)
        assertEquals(min(7.0), r.trips[1].wait_before_ms)
        assertEquals("screen.new_offer_card", r.events.last().confirmation)
    }

    @Test fun monitoringGapMakesTheOpenTripEndUnknownAndBreaksWaitChain() {
        val r = DeliveryEventDetector.detect(listOf(
            tap(0.0, "ACCEPT", "принять"), step(0.1, "доставлено"),
            gap(5.0),
            tap(20.0, "DELIVERY_STEP", "доставлено", "SLIDE"), home(20.1),
            tap(30.0, "ACCEPT", "принять"), step(30.1, "доставлено"),
        ))
        assertEquals("UNKNOWN", r.trips[0].end_evidence)
        assertNull(r.trips[0].duration_ms)
        // The completion after the gap is observed but has no observed start; next trip's wait counts from it.
        assertTrue(r.events.any { it.type == "DELIVERY_COMPLETED" && it.confirmation.endsWith("(start_unknown)") })
        assertEquals(min(10.0), r.trips[1].wait_before_ms)
        assertEquals("UNKNOWN", r.trips[1].end_evidence) // still running
    }

    @Test fun declineAndOtherTapsAreNotStartOrEndPoints() {
        val r = DeliveryEventDetector.detect(listOf(offer(0.0, 2000), tap(0.05, "DECLINE", "отклонить"), home(0.1), tap(1.0, "OTHER", null), home(1.1)))
        assertEquals(listOf("OFFER_ENDED_UNKNOWN"), r.events.map { it.type })
        assertFalse(r.trips.any())
    }

    // --- The REAL decline sequence from 29.10.2026 11:16 (shapes copied from the phone, content redacted) ---

    private fun realCard(at: Double) = Item(min(at), "OFFER_PRESENTED", StateMarkers(
        source = "SCREEN", offer_card = true, home_screen = true, offer_amount_agorot = 4126, offer_distance_km = "7.6",
        pickup_names = listOf("Shufersal | Bat Yam"), ui_words = listOf("доставки", "задание", "отказаться", "принять"),
    ))
    private fun realDeclineDialog(at: Double) = Item(min(at), "UNKNOWN", StateMarkers(source = "SCREEN", ui_words = listOf("доставки", "доход", "отказаться")))
    private fun realHome(at: Double) = Item(min(at), "AVAILABLE", StateMarkers(
        source = "SCREEN", home_screen = true, status_button = "[TEXT×2] [UI:оффлайн]", ui_words = listOf("задание", "оффлайн"),
        view_ids = listOf("BottomSheet.PreviousTask.PrimaryLabel", "StatusButtons.SwipeButton", "SwipeButton.Enabled"),
    ))

    @Test fun realDeclineIsDialogObservedThenDeclineEstimated() {
        val r = DeliveryEventDetector.detect(listOf(realCard(0.0), realCard(0.08), realDeclineDialog(0.11), realHome(0.135)))
        assertEquals(listOf("DECLINE_DIALOG_SHOWN" to "OBSERVED", "OFFER_DECLINED" to "ESTIMATED"), r.events.map { it.type to it.evidence })
        assertEquals("screen_sequence:decline_dialog→home_screen", r.events.last().confirmation)
        assertTrue(r.trips.isEmpty())
    }

    @Test fun declineDialogThenBackToTheCardIsACancelledDecline() {
        val r = DeliveryEventDetector.detect(listOf(realCard(0.0), realDeclineDialog(0.1), realCard(0.15)))
        assertEquals(listOf("DECLINE_DIALOG_SHOWN", "DECLINE_CANCELLED"), r.events.map { it.type })
    }

    @Test fun declineDialogIsNeverMistakenForACompletionSummary() {
        // The dialog contains "доход" (income) — it must not close a running trip.
        val r = DeliveryEventDetector.detect(listOf(
            offer(0.0, 3000), tap(0.05, "ACCEPT", "принять"), step(0.1, "доставлено"),
            tap(10.0, "DELIVERY_STEP", "доставлено", "SLIDE"), realDeclineDialog(10.1),
        ))
        assertEquals(0, r.events.count { it.type == "DELIVERY_COMPLETED" })
    }

    // --- The user's question: one drop-off done vs the whole trip done (3 orders together) ---

    private fun drop(at: Double) = tap(at, "DELIVERY_STEP", "доставлено", "SLIDE")

    @Test fun threeOrdersAcceptedSeparately_tripEndsOnlyAtTheThirdDropoff() {
        val r = DeliveryEventDetector.detect(listOf(
            offer(0.0, 2500), tap(0.05, "ACCEPT", "принять"), step(0.1, "забрал"),   // 1st accept = trip starts
            offer(1.0, 2200), tap(1.05, "ACCEPT", "принять"), step(1.1, "забрал"),
            offer(2.0, 3100), tap(2.05, "ACCEPT", "принять"), step(2.1, "забрал"),
            drop(15.0), step(15.1, "доставлено"),   // drop 1 → next stop: trip still active
            drop(22.0), step(22.1, "доставлено"),   // drop 2 → next stop: trip still active
            drop(30.0), home(30.1),                 // drop 3 → home: trip ends
        ))
        assertEquals(listOf("screen.next_stop", "screen.next_stop", "screen.home_screen"), r.events.filter { it.type == "DROPOFF_DONE" }.map { it.confirmation })
        assertEquals(1, r.events.count { it.type == "DELIVERY_COMPLETED" })
        assertEquals(min(30.0), r.events.single { it.type == "DELIVERY_COMPLETED" }.action_at_ms)
        val t = r.trips.single()
        assertEquals(3 to 3, t.orders to t.dropoffs)
        assertEquals(listOf(2500L, 2200L, 3100L), t.offer_amounts_agorot)
        assertEquals(min(30.0) - min(0.05), t.duration_ms) // from the FIRST accept to the LAST drop-off
    }

    @Test fun oneOfferWithThreeDeliveries_summaryBetweenStopsDoesNotEndTheTrip() {
        val r = DeliveryEventDetector.detect(listOf(
            offer(0.0, 7800, count = 3), tap(0.05, "ACCEPT", "принять"), step(0.1, "забрал"),
            drop(12.0), summary(12.1),   // Wolt shows a short "delivered, income" screen between stops
            drop(20.0), summary(20.1),
            drop(28.0), summary(28.1),   // the 3rd: now it really ends
        ))
        assertEquals(
            listOf("screen.summary_but_2_more_expected", "screen.summary_but_1_more_expected", "screen.completion_summary"),
            r.events.filter { it.type == "DROPOFF_DONE" }.map { it.confirmation },
        )
        val t = r.trips.single()
        assertEquals(3, t.expected_dropoffs)
        assertEquals("OBSERVED", t.end_evidence)
        assertEquals(min(28.0) - min(0.05), t.duration_ms)
    }

    @Test fun homeScreenBeforeAllExpectedDropoffsIsAConflict_notACompletion() {
        val r = DeliveryEventDetector.detect(listOf(
            offer(0.0, 7800, count = 3), tap(0.05, "ACCEPT", "принять"), step(0.1, "забрал"),
            drop(12.0), step(12.1, "доставлено"),
            drop(20.0), home(20.1), // home after 2 of 3: maybe an order was cancelled — we can't know
        ))
        assertEquals(0, r.events.count { it.type == "DELIVERY_COMPLETED" })
        val conflict = r.events.single { it.type == "TRIP_END_CONFLICT" }
        assertEquals("UNKNOWN", conflict.evidence)
        assertEquals("home_screen after 2 of 3 expected drop-offs", conflict.confirmation)
        val t = r.trips.single()
        assertEquals("UNKNOWN", t.end_evidence)
        assertNull(t.duration_ms)
    }

    @Test fun offerCardIsUsedOnlyOnce() {
        // A card seen earlier must not be attributed to a later acceptance that had no card of its own.
        val r = DeliveryEventDetector.detect(listOf(
            offer(0.0, 2500), tap(0.05, "ACCEPT", "принять"), step(0.1, "доставлено"), drop(10.0), home(10.1),
            tap(15.0, "ACCEPT", "принять"), step(15.1, "доставлено"),
        ))
        assertEquals(listOf(2500L), r.trips[0].offer_amounts_agorot)
        assertEquals(emptyList<Long>(), r.trips[1].offer_amounts_agorot)
    }
}
