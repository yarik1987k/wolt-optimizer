package dev.yk.offercollector.core

import kotlinx.serialization.Serializable

/**
 * Courier-state FEASIBILITY markers (tests A/B). Markers contain only structure and fixed
 * vocabulary — developer view ids, redacted shapes, words from TextClassifier's fixed lists and
 * notification metadata. Never names, addresses or free text.
 */
enum class CourierState {
    OFFER_PRESENTED, AVAILABLE, OFFLINE, DELIVERY_ACTIVE, UNKNOWN,
    // The courier's OWN actions in Wolt — taps or slides — observed passively; the app never acts.
    ACCEPT_ACTION, DECLINE_ACTION, DELIVERY_STEP_ACTION, AVAILABILITY_ACTION,
}

/** A tap or slide the courier made in Wolt, as reported by Android. Label redacted; only fixed vocabulary kept. */
@Serializable
data class TapMarker(
    val kind: String, // ACCEPT | DECLINE | DELIVERY_STEP | AVAILABILITY | OTHER
    val gesture: String = "TAP", // TAP | LONG_TAP | SLIDE
    /** Android event type that reported it, e.g. TYPE_VIEW_CLICKED — to learn which events Wolt sends. */
    val event_type: String? = null,
    val word: String? = null, // the fixed-vocabulary word that decided the kind
    val view_id: String? = null,
    val cls: String? = null,
    val label_redacted: String,
)

object UserActions {
    val DECLINE_WORDS = setOf("отклонить", "пропустить", "отказаться", "сбросить", "decline", "reject", "skip", "דחה", "דלג", "סרב")

    private val SLIDER_ID = Regex("swipe|slide|slider", RegexOption.IGNORE_CASE)
    private val SLIDER_CLASS = Regex("SeekBar|Slider|Swipe", RegexOption.IGNORE_CASE)

    /** A swipe-to-confirm control (Wolt's status button, delivery-step sliders). */
    fun isSlider(viewId: String?, cls: String?) =
        (viewId != null && SLIDER_ID.containsMatchIn(viewId)) || (cls != null && SLIDER_CLASS.containsMatchIn(cls))

    /** Classifies a tap/slide by the words on the element. Raw labels never leave this function. */
    fun classify(labels: List<String?>, viewId: String?, cls: String?, gesture: String = "TAP", eventType: String? = null): TapMarker {
        val red = labels.filterNotNull().filter { it.isNotBlank() }.distinct().map { TextClassifier.classify(it) }
        val words = red.flatMap { r -> r.tokens.filter { it.kind == TokenKind.UI_KEYWORD }.mapNotNull { it.value } }
        val accept = words.firstOrNull { it in DetectedOffers.ACCEPT_WORDS }
        val decline = words.firstOrNull { it in DECLINE_WORDS }
        val step = words.firstOrNull { TextClassifier.stateGroupOf(it) == "DELIVERY_STEP" }
        val availability = words.firstOrNull { TextClassifier.stateGroupOf(it) == "AVAILABILITY" }
        val (kind, word) = when {
            accept != null -> "ACCEPT" to accept
            decline != null -> "DECLINE" to decline
            step != null -> "DELIVERY_STEP" to step
            availability != null -> "AVAILABILITY" to availability
            else -> "OTHER" to null
        }
        return TapMarker(
            kind = kind,
            gesture = gesture,
            event_type = eventType,
            word = word,
            view_id = viewId?.substringAfter(":id/"),
            cls = cls?.substringAfterLast('.'),
            label_redacted = red.joinToString(" | ") { it.display() }.ifEmpty { "[EMPTY]" },
        )
    }
}

/**
 * OBSERVED  = a direct marker already proven on real data (only offer cards / offer notifications so far).
 * ESTIMATED = an indirect or not-yet-proven marker (everything else until tests A/B confirm it).
 * UNKNOWN   = no usable signal, or a disappearance (never interpreted).
 */
enum class Evidence { OBSERVED, ESTIMATED, UNKNOWN }

@Serializable
data class ActiveNotification(
    val channel: String?,
    val ongoing: Boolean,
    val foreground_service: Boolean,
    val title_redacted: String?,
    val text_redacted: String?,
)

/** A swipe-to-confirm control on screen and its (redacted) label. */
@Serializable
data class SliderMarker(
    val view_id: String?,
    val cls: String?,
    val label_redacted: String,
    val words: List<String>,
    /** Position 0–100 when Android exposes it. A move to 100 is a hint of a completed slide (ESTIMATED). */
    val progress_pct: Int? = null,
)

@Serializable
data class StateMarkers(
    val source: String, // SCREEN | NOTIFICATION | SYSTEM
    val offer_card: Boolean = false,
    val home_screen: Boolean = false,
    /** Redacted text of Wolt's status swipe button, e.g. "[UI:онлайн] [TEXT×2]". */
    val status_button: String? = null,
    val availability_words: List<String> = emptyList(),
    val delivery_step_words: List<String> = emptyList(),
    val view_ids: List<String> = emptyList(),
    /** NOTIFICATION source: the selected app's notifications showing right now (metadata + redacted text). */
    val active_notifications: List<ActiveNotification> = emptyList(),
    /** NOTIFICATION source: the event that triggered this snapshot, e.g. "POSTED incoming_task". */
    val notification_event: String? = null,
    /** SYSTEM source: e.g. "LISTENER_DISCONNECTED", "MONITORING_STOPPED". */
    val system_event: String? = null,
    /** ACTION source: the courier's own tap. */
    val tap: TapMarker? = null,
    /** SCREEN source, offer card only: non-personal offer details (as displayed). */
    val offer_amount_agorot: Long? = null,
    val offer_distance_km: String? = null,
    /** Number of deliveries shown on the offer card ("3 доставки"), when it is shown. */
    val offer_order_count: Int? = null,
    val pickup_names: List<String> = emptyList(),
    val package_size: String? = null,
    /** SCREEN source: swipe-to-confirm controls and their labels (a label change = the step changed). */
    val sliders: List<SliderMarker> = emptyList(),
    /** SCREEN source: all fixed-vocabulary words on screen (never free text). */
    val ui_words: List<String> = emptyList(),
)

data class StateCandidate(val state: CourierState, val evidence: Evidence, val rule: String)

object StateMarkersExtractor {
    /** Wolt home-screen widgets seen on the real device (ru-RU, 29.10.2026). */
    val HOME_VIEW_IDS = setOf("StatusButtons.SwipeButton", "FleetDemandWidgetWithoutNumber.Title", "BottomSheet.PreviousTask")
    const val STATUS_BUTTON_ID = "StatusButtons.SwipeButton"

    fun fromScreen(fields: CandidateFields): StateMarkers {
        val ids = fields.nodes.mapNotNull { it.viewId }.toSortedSet()
        val words = fields.ui_keywords.map { it.lowercase() }
        val offer = fields.amounts_agorot.isNotEmpty() && words.any { it in DetectedOffers.ACCEPT_WORDS }
        return StateMarkers(
            source = "SCREEN",
            offer_card = offer,
            offer_amount_agorot = if (offer) fields.amounts_agorot.first() else null,
            offer_distance_km = if (offer) fields.distances_km.firstOrNull() else null,
            offer_order_count = if (offer) fields.order_counts.firstOrNull() else null,
            pickup_names = if (offer) fields.pickup_names else emptyList(),
            package_size = if (offer) fields.package_size else null,
            home_screen = ids.count { it in HOME_VIEW_IDS } >= 2,
            status_button = fields.nodes.firstOrNull { it.viewId == STATUS_BUTTON_ID }?.redacted,
            availability_words = words.filter { TextClassifier.stateGroupOf(it) == "AVAILABILITY" }.distinct().sorted(),
            delivery_step_words = words.filter { TextClassifier.stateGroupOf(it) == "DELIVERY_STEP" }.distinct().sorted(),
            view_ids = ids.toList(),
            ui_words = words.distinct().sorted(),
            sliders = fields.nodes.filter { UserActions.isSlider(it.viewId, it.cls) || it.range_pct != null }.map { n ->
                SliderMarker(n.viewId, n.cls, n.redacted, Regex("""\[UI:([^\]]+)]""").findAll(n.redacted).map { it.groupValues[1] }.toList(), n.range_pct)
            },
        )
    }

    fun fromNotifications(active: List<ActiveNotification>, event: String?): StateMarkers {
        val text = active.flatMap { listOfNotNull(it.title_redacted, it.text_redacted) }.joinToString(" ")
        val words = Regex("""\[UI:([^\]]+)]""").findAll(text).map { it.groupValues[1].lowercase() }.toList()
        return StateMarkers(
            source = "NOTIFICATION",
            availability_words = words.filter { TextClassifier.stateGroupOf(it) == "AVAILABILITY" }.distinct().sorted(),
            delivery_step_words = words.filter { TextClassifier.stateGroupOf(it) == "DELIVERY_STEP" }.distinct().sorted(),
            active_notifications = active.sortedWith(compareBy({ it.channel }, { it.title_redacted })),
            notification_event = event,
        )
    }

    fun system(event: String) = StateMarkers(source = "SYSTEM", system_event = event)

    fun fromTap(tap: TapMarker) = StateMarkers(source = "ACTION", tap = tap)
}

object StateClassifier {
    private val OFFLINE_WORDS = setOf("офлайн", "оффлайн", "недоступен", "недоступна", "offline", "unavailable", "מנותק", "מנותקת")
    private val ONLINE_WORDS = setOf("онлайн", "линии", "доступен", "доступна", "online", "available", "מחובר", "מחוברת", "זמין", "זמינה")
    private val NEW_ORDER_WORDS = setOf("новый", "заказ", "задание", "order", "new", "offer", "הזמנה", "חדשה", "הצעה")

    const val OFFER_CHANNEL = "incoming_task"

    private fun statusWords(m: StateMarkers): Set<String> =
        Regex("""\[UI:([^\]]+)]""").findAll(m.status_button.orEmpty()).map { it.groupValues[1].lowercase() }.toSet()

    fun classify(m: StateMarkers): StateCandidate = when (m.source) {
        "SCREEN" -> screen(m)
        "NOTIFICATION" -> notification(m)
        "ACTION" -> action(m)
        else -> StateCandidate(CourierState.UNKNOWN, Evidence.UNKNOWN, "system.${m.system_event ?: "event"}")
    }

    /**
     * The TAP itself is observed. It is not proof the action succeeded (e.g. the offer may already
     * have expired) — confirmation comes from what follows, which the timeline shows separately.
     */
    private fun action(m: StateMarkers): StateCandidate {
        val t = m.tap ?: return StateCandidate(CourierState.UNKNOWN, Evidence.UNKNOWN, "action.none")
        val g = t.gesture.lowercase()
        return when (t.kind) {
            "ACCEPT" -> StateCandidate(CourierState.ACCEPT_ACTION, Evidence.OBSERVED, "action.${g}_accept:${t.word}(action_only_not_confirmation)")
            "DECLINE" -> StateCandidate(CourierState.DECLINE_ACTION, Evidence.OBSERVED, "action.${g}_decline:${t.word}")
            "DELIVERY_STEP" -> StateCandidate(CourierState.DELIVERY_STEP_ACTION, Evidence.OBSERVED, "action.${g}_step:${t.word}")
            "AVAILABILITY" -> StateCandidate(CourierState.AVAILABILITY_ACTION, Evidence.OBSERVED, "action.${g}_availability:${t.word}")
            else -> StateCandidate(CourierState.UNKNOWN, Evidence.UNKNOWN, "action.${g}_other")
        }
    }

    private fun screen(m: StateMarkers): StateCandidate = when {
        m.offer_card -> StateCandidate(CourierState.OFFER_PRESENTED, Evidence.OBSERVED, "screen.offer_card")
        m.sliders.any { s -> s.words.any { TextClassifier.stateGroupOf(it) == "DELIVERY_STEP" } } ->
            StateCandidate(CourierState.DELIVERY_ACTIVE, Evidence.ESTIMATED, "screen.slider_step_label(unproven)")
        m.delivery_step_words.isNotEmpty() ->
            StateCandidate(CourierState.DELIVERY_ACTIVE, Evidence.ESTIMATED, "screen.delivery_step_words(unproven)")
        // Wolt's status button is an ACTION label ("go offline" while online). VERIFIED by Test A on the
        // real device, 29.10.2026 13:46–13:48: two Offline→Online cycles, the label flipped every time in
        // both directions, and offers arrived only while it read "оффлайн". Only that button is read —
        // other menu items ("schedule offline") are ignored.
        m.home_screen && statusWords(m).any { it in OFFLINE_WORDS } ->
            StateCandidate(CourierState.AVAILABLE, Evidence.OBSERVED, "screen.status_button_offers_offline(verified_test_A)")
        m.home_screen && statusWords(m).any { it in ONLINE_WORDS } ->
            StateCandidate(CourierState.OFFLINE, Evidence.OBSERVED, "screen.status_button_offers_online(verified_test_A)")
        m.home_screen -> StateCandidate(CourierState.UNKNOWN, Evidence.UNKNOWN, "screen.home_without_status_word")
        else -> StateCandidate(CourierState.UNKNOWN, Evidence.UNKNOWN, "screen.unrecognized")
    }

    private fun notification(m: StateMarkers): StateCandidate {
        val event = m.notification_event.orEmpty()
        // A notification going away is never acceptance, decline or completion.
        if (event.startsWith("REMOVED")) return StateCandidate(CourierState.UNKNOWN, Evidence.UNKNOWN, "notif.removed_no_inference")
        if (event == "POSTED $OFFER_CHANNEL" || event == "UPDATED $OFFER_CHANNEL") {
            val offer = m.active_notifications.firstOrNull { it.channel == OFFER_CHANNEL }
            val words = Regex("""\[UI:([^\]]+)]""").findAll("${offer?.title_redacted} ${offer?.text_redacted}").map { it.groupValues[1] }.toSet()
            if (words.any { it in NEW_ORDER_WORDS }) return StateCandidate(CourierState.OFFER_PRESENTED, Evidence.OBSERVED, "notif.incoming_task_new_order")
        }
        if (m.delivery_step_words.isNotEmpty())
            return StateCandidate(CourierState.DELIVERY_ACTIVE, Evidence.ESTIMATED, "notif.delivery_step_words(unproven)")
        if (m.availability_words.any { it in OFFLINE_WORDS })
            return StateCandidate(CourierState.OFFLINE, Evidence.ESTIMATED, "notif.offline_word(unproven)")
        if (m.availability_words.any { it in ONLINE_WORDS })
            return StateCandidate(CourierState.AVAILABLE, Evidence.ESTIMATED, "notif.online_word(unproven)")
        if (m.active_notifications.any { it.foreground_service })
            // Seen continuously while online on 29.10; whether it also shows while delivering or offline is untested.
            return StateCandidate(CourierState.AVAILABLE, Evidence.ESTIMATED, "notif.foreground_service_active(online_or_delivering?)")
        return StateCandidate(CourierState.UNKNOWN, Evidence.UNKNOWN, "notif.no_state_signal")
    }

    /**
     * Only a change in what was observed produces a new row. Screen view-id churn alone (widgets
     * appearing/disappearing in the same state) does not.
     */
    fun materiallyDifferent(a: StateMarkers?, b: StateMarkers): Boolean {
        if (b.source == "ACTION") return true // every tap is an event (duplicates are filtered by time in the recorder)
        if (a == null || a.source != b.source) return true
        return a.copy(view_ids = emptyList(), ui_words = emptyList()) != b.copy(view_ids = emptyList(), ui_words = emptyList()) ||
            a.ui_words.toSet() != b.ui_words.toSet() ||
            (a.view_ids.toSet() - b.view_ids.toSet()).size + (b.view_ids.toSet() - a.view_ids.toSet()).size >= 3
    }
}
