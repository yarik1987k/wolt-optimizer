package dev.yk.offercollector

import dev.yk.offercollector.core.CourierState
import dev.yk.offercollector.core.Evidence
import dev.yk.offercollector.core.RawNode
import dev.yk.offercollector.core.SnapshotAnalyzer
import dev.yk.offercollector.core.StateClassifier
import dev.yk.offercollector.core.StateMarkersExtractor
import dev.yk.offercollector.core.UserActions
import dev.yk.offercollector.data.StateMarkerEventEntity
import dev.yk.offercollector.service.StateMarkerRecorder
import dev.yk.offercollector.ui.shiftLogLines
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserActionsTest {
    private fun cls(labels: List<String?>, id: String? = null, c: String? = "android.widget.Button", g: String = "TAP") =
        StateClassifier.classify(StateMarkersExtractor.fromTap(UserActions.classify(labels, id, c, g)))

    @Test fun acceptTapIsAnObservedActionButNotAConfirmation() {
        val r = cls(listOf("Принять"))
        assertEquals(CourierState.ACCEPT_ACTION to Evidence.OBSERVED, r.state to r.evidence)
        assertTrue(r.rule.contains("not_confirmation"))
        assertEquals(CourierState.ACCEPT_ACTION, cls(listOf(null, "Accept order")).state)
        assertEquals(CourierState.ACCEPT_ACTION, cls(listOf("קבל")).state)
    }

    @Test fun declineAndStepsAndAvailability() {
        assertEquals(CourierState.DECLINE_ACTION, cls(listOf("Отклонить")).state)
        assertEquals(CourierState.DECLINE_ACTION, cls(listOf("דחה")).state)
        assertEquals(CourierState.DELIVERY_STEP_ACTION, cls(listOf("Прибыл в ресторан")).state)
        assertEquals(CourierState.DELIVERY_STEP_ACTION, cls(listOf("נמסר")).state)
        assertEquals(CourierState.AVAILABILITY_ACTION, cls(listOf("Выйти на линию")).state)
        val other = cls(listOf("Настройки"))
        assertEquals(CourierState.UNKNOWN to Evidence.UNKNOWN, other.state to other.evidence)
    }

    @Test fun finishBySlidingIsRecordedAsSlide() {
        // The user reported: accept = tap, finish = slide. A slide on a swipe control with a step word.
        val m = UserActions.classify(listOf("Доставлено, проведите вправо"), "com.wolt.courierapp:id/TaskSwipeButton", "android.view.View", "SLIDE")
        assertEquals("SLIDE", m.gesture)
        val r = StateClassifier.classify(StateMarkersExtractor.fromTap(m))
        assertEquals(CourierState.DELIVERY_STEP_ACTION to Evidence.OBSERVED, r.state to r.evidence)
        assertTrue(r.rule.startsWith("action.slide_step:доставлено"))
    }

    @Test fun sliderDetectionIsNarrow() {
        assertTrue(UserActions.isSlider("StatusButtons.SwipeButton", null))
        assertTrue(UserActions.isSlider(null, "SeekBar"))
        assertTrue(UserActions.isSlider("task_slide_to_confirm", "View"))
        assertFalse(UserActions.isSlider("map_view", "RecyclerView")) // scrolling a list/map is not a slide action
        assertFalse(UserActions.isSlider(null, null))
    }

    @Test fun sliderLabelOnScreenIsOnlyEstimated() {
        val nodes = listOf(RawNode("android.view.View", "com.wolt.courierapp:id/TaskSwipeButton", null, "Забрал заказ", false, 1))
        val m = StateMarkersExtractor.fromScreen(SnapshotAnalyzer.analyze("F", nodes, false).fields)
        assertEquals("[UI:забрал] [UI:заказ]", m.sliders.single().label_redacted) // both are fixed UI words
        val r = StateClassifier.classify(m)
        assertEquals(CourierState.DELIVERY_ACTIVE to Evidence.ESTIMATED, r.state to r.evidence)
        // A label change is a material change (recorded), the same label is not.
        val after = m.copy(sliders = listOf(m.sliders.single().copy(label_redacted = "[UI:доставлено]", words = listOf("доставлено"))))
        assertTrue(StateClassifier.materiallyDifferent(m, after))
        assertFalse(StateClassifier.materiallyDifferent(m, m))
    }

    @Test fun sliderPositionIsRecordedEvenWithoutText() {
        val before = listOf(RawNode("android.widget.SeekBar", "com.wolt.courierapp:id/TaskSwipeButton", null, null, false, 1, rangePct = 0))
        val after = listOf(before.single().copy(rangePct = 100))
        val a = StateMarkersExtractor.fromScreen(SnapshotAnalyzer.analyze("F", before, false).fields)
        val b = StateMarkersExtractor.fromScreen(SnapshotAnalyzer.analyze("F", after, false).fields)
        assertEquals(0, a.sliders.single().progress_pct)
        assertEquals(100, b.sliders.single().progress_pct)
        assertTrue(StateClassifier.materiallyDifferent(a, b))
        assertFalse(SnapshotAnalyzer.analyze("F", before, false).hasText) // a bare slider is not "text"
        assertTrue(SnapshotAnalyzer.analyze("F", before, false).fingerprint != SnapshotAnalyzer.analyze("F", after, false).fingerprint)
    }

    @Test fun duplicateEventsWithinOneSecondAreOneAction_laterRepeatIsNew() {
        val m = StateMarkersExtractor.fromTap(UserActions.classify(listOf("Доставлено"), "x", "y", "SLIDE"))
        assertTrue(StateMarkerRecorder.isDuplicateTap(m to 1000L, m, 1500L))
        assertFalse(StateMarkerRecorder.isDuplicateTap(m to 1000L, m, 1_000_000L)) // next order, 16 min later
        assertFalse(StateMarkerRecorder.isDuplicateTap(null, m, 0L))
        assertTrue(StateClassifier.materiallyDifferent(m, m)) // actions are always events
    }

    @Test fun tappedLabelsAreRedacted() {
        val m = UserActions.classify(listOf("Позвонить Иван Петров 050-1234567", "ул. Герцль 20"), "call_button", "Button")
        val json = Json.encodeToString(m)
        for (secret in listOf("Иван", "Петров", "1234567", "Герцль", "20")) assertFalse(secret, json.contains(secret))
        assertEquals("DELIVERY_STEP", m.kind) // "позвонить" is a fixed step word
    }

    @Test fun shiftLogReadsLikeAStory() {
        fun ev(t: Long, state: String, ev: String, rule: String, markersJson: String) =
            StateMarkerEventEntity("e$t", t, "X", state, ev, rule, markersJson, "p", null)
        val lines = shiftLogLines(
            listOf(
                ev(1, "OFFER_PRESENTED", "OBSERVED", "screen.offer_card",
                    """{"source":"SCREEN","offer_card":true,"offer_amount_agorot":3502,"offer_distance_km":"6.8","pickup_names":["Pizza Place"],"package_size":"Тяжелая (более 13 кг)"}"""),
                ev(2, "ACCEPT_ACTION", "OBSERVED", "action.tap_accept:принять", """{"source":"ACTION","tap":{"kind":"ACCEPT","gesture":"TAP","word":"принять","label_redacted":"[UI:принять]"}}"""),
                ev(3, "DELIVERY_STEP_ACTION", "OBSERVED", "action.slide_step:доставлено", """{"source":"ACTION","tap":{"kind":"DELIVERY_STEP","gesture":"SLIDE","word":"доставлено","label_redacted":"[UI:доставлено]"}}"""),
                ev(4, "UNKNOWN", "UNKNOWN", "notif.removed_no_inference", """{"source":"NOTIFICATION","notification_event":"REMOVED incoming_task"}"""),
            ),
        )
        assertEquals("הצעה על המסך · ₪35.02 · 6.8 ק״מ · Pizza Place · 📦 Тяжелая (более 13 кг)", lines[0].text)
        assertTrue(lines[1].text.startsWith("לחצת \"принять\" (קבלה)"))
        assertTrue(lines[1].text.contains("לא אישור"))
        assertEquals("החלקת \"доставлено\" (שלב משלוח)", lines[2].text)
        assertFalse(lines[3].important)
        assertTrue(lines[3].text.contains("ללא הסקה"))
    }
}
