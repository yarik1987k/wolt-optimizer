package dev.yk.offercollector

import dev.yk.offercollector.core.ActiveNotification
import dev.yk.offercollector.core.CourierState
import dev.yk.offercollector.core.Evidence
import dev.yk.offercollector.core.RawNode
import dev.yk.offercollector.core.SnapshotAnalyzer
import dev.yk.offercollector.core.StateCandidate
import dev.yk.offercollector.core.StateClassifier
import dev.yk.offercollector.core.StateMarkers
import dev.yk.offercollector.core.StateMarkersExtractor
import dev.yk.offercollector.core.TextClassifier
import dev.yk.offercollector.core.TokenKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMarkersTest {
    private fun node(text: String?, id: String? = null, desc: String? = null) =
        RawNode("android.widget.TextView", id?.let { "com.wolt.courierapp:id/$it" }, text, desc, false, 1)

    /** Home screen with the same widgets as the real Wolt app; button text is invented. */
    private fun home(button: String) = listOf(
        node(null, "StatusButtons.SwipeButton", desc = button),
        node("Спрос высокий", "FleetDemandWidgetWithoutNumber.Title"),
        node("Предыдущее задание", "BottomSheet.PreviousTask.PrimaryLabel"),
        node(null, "BottomSheet.PreviousTask", desc = "Предыдущее задание"),
    )

    private fun screen(nodes: List<RawNode>) =
        StateClassifier.classify(StateMarkersExtractor.fromScreen(SnapshotAnalyzer.analyze("F", nodes, false).fields))

    @Test fun stateWordsAreKeptInAllThreeLanguages() {
        for (w in listOf("онлайн", "офлайн", "линии", "прибыл", "доставлено", "online", "offline", "delivered", "picked", "מחובר", "זמין", "נמסר", "בדרך")) {
            val t = TextClassifier.classify(w).tokens.single()
            assertEquals(w, TokenKind.UI_KEYWORD, t.kind)
        }
        assertEquals("AVAILABILITY", TextClassifier.stateGroupOf("Онлайн"))
        assertEquals("DELIVERY_STEP", TextClassifier.stateGroupOf("נמסר"))
    }

    @Test fun personalDataIsStillRedactedNextToStateWords() {
        val r = TextClassifier.classify("Иван Петров на линии, звонок 050-1234567, ул. Герцль 20")
        val shown = r.display()
        assertTrue(shown.contains("[UI:линии]"))
        for (secret in listOf("Иван", "Петров", "1234567", "Герцль", "20")) assertFalse(secret, shown.contains(secret))
        val he = TextClassifier.classify("יוסי כהן בדרך לרחוב הרצל 12").display()
        assertTrue(he.contains("[UI:בדרך]"))
        assertFalse(he.contains("יוסי") || he.contains("הרצל") || he.contains("12"))
    }

    @Test fun offerCardIsObserved() {
        val c = screen(listOf(node("₪27.49"), node("4,5 км"), node("Принять")))
        assertEquals(StateCandidate(CourierState.OFFER_PRESENTED, Evidence.OBSERVED, "screen.offer_card"), c)
    }

    @Test fun homeScreenStatusButtonIsVerifiedByTestA() {
        // The status button is an ACTION label: "go offline" is shown while ONLINE. Verified by Test A (RealTestATest).
        assertEquals(CourierState.AVAILABLE to Evidence.OBSERVED, screen(home("Уйти в оффлайн")).let { it.state to it.evidence })
        assertEquals(CourierState.OFFLINE to Evidence.OBSERVED, screen(home("Выйти онлайн")).let { it.state to it.evidence })
        // Other menu items with "offline" (e.g. schedule offline) do not decide anything.
        val scheduleOnly = home("Свайп") + RawNode("TextView", "com.wolt.courierapp:id/BottomSheet.ScheduleOffline.PrimaryLabel", "Запланировать оффлайн", null, false, 1)
        assertEquals(CourierState.UNKNOWN, screen(scheduleOnly).state)
        // Home screen without a status word: unknown, not "available".
        assertEquals(CourierState.UNKNOWN to Evidence.UNKNOWN, screen(home("Свайп")).let { it.state to it.evidence })
    }

    @Test fun deliveryActiveIsNeverObservedInThisVersion() {
        val c = screen(listOf(node("Прибыл в ресторан"), node("Маршрут")))
        assertEquals(CourierState.DELIVERY_ACTIVE, c.state)
        assertEquals(Evidence.ESTIMATED, c.evidence)
        // Exhaustive-ish: no screen or notification combination yields DELIVERY_ACTIVE + OBSERVED.
        val samples = listOf(
            StateMarkers(source = "SCREEN", delivery_step_words = listOf("доставлено"), home_screen = true),
            StateMarkers(source = "NOTIFICATION", delivery_step_words = listOf("delivered"), notification_event = "POSTED wolt_background"),
            StateMarkers(source = "NOTIFICATION", delivery_step_words = listOf("נמסר"), notification_event = "UPDATED x",
                active_notifications = listOf(ActiveNotification("x", true, true, "[UI:נמסר]", null))),
        )
        samples.forEach { assertFalse(StateClassifier.classify(it).let { r -> r.state == CourierState.DELIVERY_ACTIVE && r.evidence == Evidence.OBSERVED }) }
    }

    private val fg = ActiveNotification("wolt_background", ongoing = false, foreground_service = true, title_redacted = "[TEXT×2]", text_redacted = "[TEXT×2]")
    private val offer = ActiveNotification("incoming_task", false, false, "[TEXT×3] [UI:новый] [UI:заказ]", "[UI:принять] [TEXT×4]")

    @Test fun offerNotificationIsObserved() {
        val c = StateClassifier.classify(StateMarkersExtractor.fromNotifications(listOf(fg, offer), "POSTED incoming_task"))
        assertEquals(CourierState.OFFER_PRESENTED, c.state)
        assertEquals(Evidence.OBSERVED, c.evidence)
    }

    @Test fun disappearanceIsNeverAcceptanceCompletionOrOffline() {
        // The offer notification goes away (as at 09:49 via REASON_CLICK): no inference at all.
        val removedOffer = StateClassifier.classify(StateMarkersExtractor.fromNotifications(listOf(fg), "REMOVED incoming_task"))
        assertEquals(CourierState.UNKNOWN, removedOffer.state)
        assertEquals("notif.removed_no_inference", removedOffer.rule)
        // The persistent notification goes away: still UNKNOWN, not OFFLINE and not "delivery finished".
        val removedFg = StateClassifier.classify(StateMarkersExtractor.fromNotifications(emptyList(), "REMOVED wolt_background"))
        assertEquals(CourierState.UNKNOWN to Evidence.UNKNOWN, removedFg.state to removedFg.evidence)
        // Listener disconnected = unknown.
        assertEquals(CourierState.UNKNOWN, StateClassifier.classify(StateMarkersExtractor.system("LISTENER_DISCONNECTED")).state)
    }

    @Test fun foregroundServiceAloneIsEstimatedAndSaysItIsAmbiguous() {
        val c = StateClassifier.classify(StateMarkersExtractor.fromNotifications(listOf(fg), "POSTED wolt_background"))
        assertEquals(CourierState.AVAILABLE to Evidence.ESTIMATED, c.state to c.evidence)
        assertTrue(c.rule.contains("online_or_delivering?"))
    }

    @Test fun onlyMaterialChangesAreRecorded() {
        val a = StateMarkersExtractor.fromScreen(SnapshotAnalyzer.analyze("F", home("Вы онлайн"), false).fields)
        // One widget appears (as seen on the real home screen): not a change.
        val b = a.copy(view_ids = a.view_ids + "BottomSheet.ScheduleOffline")
        assertFalse(StateClassifier.materiallyDifferent(a, b))
        // Status button text changes: a change.
        assertTrue(StateClassifier.materiallyDifferent(a, a.copy(status_button = "[UI:офлайн]")))
        assertTrue(StateClassifier.materiallyDifferent(null, a))
        assertTrue(StateClassifier.materiallyDifferent(a, StateMarkersExtractor.system("X")))
    }

    @Test fun markersNeverContainRawText() {
        val nodes = home("Вы онлайн") + listOf(node("Иван Петров 050-1234567"), node("ул. Герцль 20, Ришон 7520001"))
        val m = StateMarkersExtractor.fromScreen(SnapshotAnalyzer.analyze("F", nodes, false).fields)
        val json = Json.encodeToString(m)
        for (secret in listOf("Иван", "Петров", "1234567", "Герцль", "7520001", "Спрос", "Предыдущее")) assertFalse(secret, json.contains(secret))
        assertNotEquals(null, m.status_button)
    }
}
