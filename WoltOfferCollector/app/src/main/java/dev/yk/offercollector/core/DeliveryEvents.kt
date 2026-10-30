package dev.yk.offercollector.core

import kotlinx.serialization.Serializable

/**
 * Derives delivery events from the marker timeline (feasibility, post-hoc, pure). Two paths:
 *
 * 1. ACTION path — the courier's tap/slide was reported by Android AND a confirming screen
 *    appeared within CONFIRM_WINDOW_MS → OBSERVED.
 * 2. SCREEN-SEQUENCE path — used when no action event exists (the real Wolt app sends NO tap
 *    events, seen 29.10.2026): a specific screen APPEARED right after another one
 *    (offer card → delivery screen, delivery screen → home/summary, card → decline dialog).
 *    → ESTIMATED. A screen merely disappearing never produces an event; a card that ends with no
 *    recognisable follow-up screen is reported as UNKNOWN.
 *
 * Real Wolt task screens (shift 29.10.2026) carry stable view ids: the pickup step has
 * `PickupTask.CompletePickup`, the drop-off step `CompleteDropoff`. Card closed → pickup screen =
 * accepted; pickup screen → drop-off screen = picked up; drop-off screen → home / new card = delivered.
 * Wolt is often in the background (navigation), so each event also carries the earliest moment it
 * can have happened (the last time the previous step was seen).
 *
 * Bundles / multiple drop-offs: a Trip starts at the first acceptance; further acceptances add
 * orders; each drop-off is an event; the trip completes only when a drop-off is followed by a
 * non-delivery screen. If the offer cards showed how many deliveries to expect, a summary screen
 * before the last one does not end the trip, and home/new offer before it is a conflict (UNKNOWN).
 */
object DeliveryEventDetector {
    const val VERSION = "delivery-detect-5"
    const val CONFIRM_WINDOW_MS = 20_000L
    /** Card closed → the pickup screen may appear later (real: up to 42 s, the courier opened the menu first). */
    const val ACCEPT_WINDOW_MS = 90_000L

    val PICKUP_SCREEN_IDS = setOf("PickupTask.CompletePickup", "PickupTaskCard.CollectButton")
    val DROPOFF_SCREEN_IDS = setOf("CompleteDropoff", "DropoffBottomSheet.RestaurantName")

    val PICKUP_WORDS = setOf("забрал", "забрала", "получил", "получила", "picked", "collected", "נאסף", "נאספה", "אספתי")
    val DROPOFF_WORDS = setOf("доставлено", "доставлен", "доставлена", "вручено", "вручить", "доставить", "delivered", "dropped", "deliver", "נמסר", "נמסרה", "מסרתי")
    val FINISH_WORDS = setOf("завершить", "завершено", "complete", "completed", "finish", "finished", "סיים", "סיום", "הושלם")
    val SUMMARY_WORDS = setOf("доход", "заработок", "earnings", "earning", "רווח", "תשלום", "payout")

    data class Item(val atMs: Long, val candidateState: String, val markers: StateMarkers)

    @Serializable
    data class DeliveryEvent(
        /**
         * DELIVERY_ACCEPTED | PICKUP_DONE | DROPOFF_DONE | DELIVERY_COMPLETED | TRIP_END_CONFLICT |
         * DECLINE_DIALOG_SHOWN | OFFER_DECLINED | DECLINE_CANCELLED | OFFER_ENDED_UNKNOWN | ACTION_UNCONFIRMED
         */
        val type: String,
        val evidence: String, // OBSERVED | ESTIMATED | UNKNOWN
        val action_at_ms: Long, // action time (action path) or time the deciding screen appeared (sequence path)
        val action: String, // e.g. "TAP принять", "SLIDE доставлено", "SCREEN offer_card→delivery_screen"
        val confirmed_at_ms: Long? = null,
        val confirmation: String,
        val trip_index: Int? = null,
        /** False until a field test confirms this rule on real Wolt screens. */
        val rule_validated: Boolean = false,
        val path: String = "ACTION", // ACTION | SCREEN_SEQUENCE
        /** Sequence path: the event happened after this (the previous step was last seen then) and by action_at_ms. */
        val earliest_at_ms: Long? = null,
    )

    @Serializable
    data class Trip(
        val index: Int,
        val started_at_ms: Long,
        val start_evidence: String, // OBSERVED (action+screen) | ESTIMATED (screen sequence)
        val completed_at_ms: Long? = null,
        val end_evidence: String, // OBSERVED | ESTIMATED | UNKNOWN
        /** Orders in the trip; a card showing "заказы 2" counts 2. */
        val orders: Int,
        val pickups: Int,
        val dropoffs: Int,
        val offer_amounts_agorot: List<Long>,
        val expected_dropoffs: Int? = null,
        val end_note: String? = null,
        /** Only when start AND end are OBSERVED. */
        val duration_ms: Long? = null,
        /** When both ends are known but at least one only from a screen sequence. */
        val duration_estimated_ms: Long? = null,
        /** From the previous trip's known completion to this trip's start. */
        val wait_before_ms: Long? = null,
        val wait_evidence: String? = null,
    )

    data class Result(val events: List<DeliveryEvent>, val trips: List<Trip>)

    // ----- screen classification -----

    private fun StateMarkers.stepWords(): Set<String> = (delivery_step_words + sliders.flatMap { it.words }).map { it.lowercase() }.toSet()
    private fun StateMarkers.hasStepSlider() = sliders.any { s -> s.words.any { TextClassifier.stateGroupOf(it) == "DELIVERY_STEP" } }

    fun isPickupScreen(m: StateMarkers) = m.source == "SCREEN" && !m.offer_card && m.view_ids.any { it in PICKUP_SCREEN_IDS }
    fun isDropoffScreen(m: StateMarkers) = m.source == "SCREEN" && !m.offer_card && !isPickupScreen(m) && m.view_ids.any { it in DROPOFF_SCREEN_IDS }

    private fun isDeliveryScreen(m: StateMarkers) =
        m.source == "SCREEN" && !m.offer_card &&
            (isPickupScreen(m) || isDropoffScreen(m) || m.stepWords().any { TextClassifier.stateGroupOf(it) == "DELIVERY_STEP" })

    /** Real Wolt (ru-RU): title "Отказаться …", body with "доход … доставки", button "Отказаться". */
    fun isDeclineDialog(m: StateMarkers) =
        m.source == "SCREEN" && !m.offer_card && !m.home_screen && m.ui_words.any { it in UserActions.DECLINE_WORDS }

    /** Wolt's side menu (earnings/stats) — opened by the courier at any time, says nothing about the task. */
    fun isMenu(m: StateMarkers) = m.view_ids.any { it.startsWith("NavigationDrawer.") }

    /** A screen showing the courier is no longer inside a delivery (a remaining step slider = another stop). */
    private fun afterDeliveryKind(m: StateMarkers): String? = when {
        m.source != "SCREEN" -> null
        m.offer_card -> "new_offer_card"
        m.home_screen && !m.hasStepSlider() -> "home_screen"
        !m.hasStepSlider() && !isDeclineDialog(m) && !isMenu(m) && m.ui_words.any { it in SUMMARY_WORDS } -> "completion_summary"
        else -> null
    }

    private fun nextScreen(items: List<Item>, from: Int, actionAt: Long): Item? {
        for (j in from + 1 until items.size) {
            val it = items[j]
            if (it.atMs - actionAt > CONFIRM_WINDOW_MS) return null
            if (it.markers.source == "SCREEN") return it
        }
        return null
    }

    fun describe(m: StateMarkers) = when {
        m.offer_card -> "offer_card"
        isDeclineDialog(m) -> "decline_dialog"
        isPickupScreen(m) -> "pickup_screen"
        isDropoffScreen(m) -> "dropoff_screen"
        isDeliveryScreen(m) -> "delivery_screen"
        m.home_screen -> "home_screen"
        isMenu(m) -> "other_screen"
        m.ui_words.any { it in SUMMARY_WORDS } -> "summary"
        else -> "other_screen"
    }

    // ----- detection -----

    fun detect(itemsIn: List<Item>): Result = Run(itemsIn.sortedBy { it.atMs }).go()

    private class Run(val items: List<Item>) {
        val events = mutableListOf<DeliveryEvent>()
        val trips = mutableListOf<Trip>()
        var open: Trip? = null
        var lastOffer: StateMarkers? = null // consumed by the next acceptance
        var lastCompletedAt: Long? = null
        var lastCompletedEvidence: String? = null
        val handledScreens = HashSet<Long>() // screens already used as confirmation by the action path
        var prevScreen: Item? = null
        /** A card just closed; what follows within CONFIRM_WINDOW_MS (dialog / delivery screen) decides. */
        var pendingCardEnd: Item? = null

        fun flushPendingCardEnd() {
            val p = pendingCardEnd ?: return
            events += DeliveryEvent(
                "OFFER_ENDED_UNKNOWN", "UNKNOWN", p.atMs, "SCREEN offer_card→${describe(p.markers)}", null,
                "card_gone_without_dialog_or_delivery_screen(expired_or_declined?)", open?.index, path = "SCREEN_SEQUENCE",
            )
            pendingCardEnd = null
        }

        fun closeTrip(t: Trip) {
            trips += t
            if (t.end_evidence != "UNKNOWN") {
                lastCompletedAt = t.completed_at_ms
                lastCompletedEvidence = t.end_evidence
            }
        }

        fun startOrJoin(at: Long, evidence: String): Int {
            val card = lastOffer
            lastOffer = null
            val count = card?.offer_order_count
            val t = open
            return if (t == null) {
                val idx = trips.size + 1
                open = Trip(
                    index = idx, started_at_ms = at, start_evidence = evidence, end_evidence = "UNKNOWN",
                    orders = maxOf(1, count ?: 1), pickups = 0, dropoffs = 0, offer_amounts_agorot = listOfNotNull(card?.offer_amount_agorot),
                    expected_dropoffs = count,
                    wait_before_ms = lastCompletedAt?.let { at - it },
                    wait_evidence = lastCompletedAt?.let { if (evidence == "OBSERVED" && lastCompletedEvidence == "OBSERVED") "OBSERVED" else "ESTIMATED" },
                )
                idx
            } else {
                open = t.copy(
                    orders = t.orders + maxOf(1, count ?: 1),
                    offer_amounts_agorot = t.offer_amounts_agorot + listOfNotNull(card?.offer_amount_agorot),
                    expected_dropoffs = if (t.expected_dropoffs != null && count != null) t.expected_dropoffs + count else null,
                )
                t.index
            }
        }

        /** A confirmed drop-off; decides whether the whole trip ended. */
        fun dropoff(at: Long, label: String, confirmAt: Long, kind: String, evidence: String, path: String, earliest: Long? = null) {
            val t = open
            if (t == null) {
                events += DeliveryEvent("DROPOFF_DONE", evidence, at, label, confirmAt, "screen.$kind", null, path = path, earliest_at_ms = earliest)
                events += DeliveryEvent("DELIVERY_COMPLETED", evidence, at, label, confirmAt, "screen.$kind(start_unknown)", null, path = path, earliest_at_ms = earliest)
                lastCompletedAt = at
                lastCompletedEvidence = evidence
                return
            }
            val remaining = t.expected_dropoffs?.let { it - (t.dropoffs + 1) }
            when {
                remaining != null && remaining > 0 && kind == "completion_summary" -> {
                    events += DeliveryEvent("DROPOFF_DONE", evidence, at, label, confirmAt, "screen.summary_but_${remaining}_more_expected", t.index, path = path)
                    open = t.copy(dropoffs = t.dropoffs + 1)
                }
                remaining != null && remaining > 0 -> {
                    events += DeliveryEvent("DROPOFF_DONE", evidence, at, label, confirmAt, "screen.$kind", t.index, path = path, earliest_at_ms = earliest)
                    val note = "$kind after ${t.dropoffs + 1} of ${t.expected_dropoffs} expected drop-offs"
                    events += DeliveryEvent("TRIP_END_CONFLICT", "UNKNOWN", at, label, confirmAt, note, t.index, path = path)
                    closeTrip(t.copy(dropoffs = t.dropoffs + 1, end_evidence = "UNKNOWN", end_note = note))
                    open = null
                }
                else -> {
                    events += DeliveryEvent("DROPOFF_DONE", evidence, at, label, confirmAt, "screen.$kind", t.index, path = path, earliest_at_ms = earliest)
                    val both = if (t.start_evidence == "OBSERVED" && evidence == "OBSERVED") "OBSERVED" else "ESTIMATED"
                    events += DeliveryEvent("DELIVERY_COMPLETED", evidence, at, label, confirmAt, "screen.$kind", t.index, path = path, earliest_at_ms = earliest)
                    closeTrip(
                        t.copy(
                            dropoffs = t.dropoffs + 1, completed_at_ms = at, end_evidence = evidence,
                            duration_ms = if (both == "OBSERVED") at - t.started_at_ms else null,
                            duration_estimated_ms = if (both == "ESTIMATED") at - t.started_at_ms else null,
                        ),
                    )
                    open = null
                }
            }
        }

        fun go(): Result {
            for ((i, item) in items.withIndex()) {
                val m = item.markers
                when (m.source) {
                    "SYSTEM" -> {
                        flushPendingCardEnd()
                        open?.let { closeTrip(it.copy(end_evidence = "UNKNOWN", end_note = "monitoring gap: ${m.system_event}")) }
                        open = null
                        lastCompletedAt = null
                        prevScreen = null
                    }
                    "ACTION" -> action(i, item)
                    "SCREEN" -> {
                        if (item.atMs !in handledScreens) sequence(prevScreen, item)
                        if (m.offer_card) {
                            // Snapshots of one card may show "заказы 2" only on some of them: keep it.
                            val prev = lastOffer
                            lastOffer = if (prev != null && prev.offer_amount_agorot == m.offer_amount_agorot && m.offer_order_count == null) m.copy(offer_order_count = prev.offer_order_count) else m
                        }
                        // A menu, notes pop-up or blank frame over a task screen does not end the step:
                        // keep the task screen as `prev` so the next real screen is compared with it.
                        val keepTask = prevScreen?.markers?.let(::isDeliveryScreen) == true && describe(m) == "other_screen" && !isDeclineDialog(m)
                        if (!keepTask) prevScreen = item
                    }
                }
            }
            flushPendingCardEnd()
            open?.let { closeTrip(it.copy(end_note = it.end_note ?: "no confirmed final drop-off yet")) }
            return Result(events.sortedBy { it.action_at_ms }, trips.sortedBy { it.index })
        }

        // ----- path 1: an action reported by Android -----
        fun action(i: Int, item: Item) {
            val tap = item.markers.tap ?: return
            val word = tap.word?.lowercase()
            val label = "${tap.gesture} ${tap.word ?: tap.label_redacted}"
            val next = nextScreen(items, i, item.atMs)
            val nm = next?.markers
            when {
                tap.kind == "ACCEPT" -> if (next != null && nm != null && isDeliveryScreen(nm)) {
                    handledScreens += next.atMs
                    val idx = startOrJoin(item.atMs, "OBSERVED")
                    events += DeliveryEvent("DELIVERY_ACCEPTED", "OBSERVED", item.atMs, label, next.atMs, "screen.delivery_steps_after_accept", idx)
                } else {
                    events += DeliveryEvent(
                        "ACTION_UNCONFIRMED", "UNKNOWN", item.atMs, label, null,
                        if (nm == null) "accept_tap_without_following_screen" else "accept_tap_but_next_screen_is_not_a_delivery_screen", open?.index,
                    )
                }
                tap.kind == "DELIVERY_STEP" && word in PICKUP_WORDS -> if (next != null && nm != null && isDeliveryScreen(nm)) {
                    handledScreens += next.atMs
                    events += DeliveryEvent("PICKUP_DONE", "OBSERVED", item.atMs, label, next.atMs, "screen.next_delivery_step", open?.index)
                    open = open?.let { it.copy(pickups = it.pickups + 1) }
                } else {
                    events += DeliveryEvent("ACTION_UNCONFIRMED", "UNKNOWN", item.atMs, label, null, "pickup_action_without_confirming_screen", open?.index)
                }
                tap.kind == "DELIVERY_STEP" && (word in DROPOFF_WORDS || word in FINISH_WORDS) -> {
                    val after = nm?.let { afterDeliveryKind(it) }
                    when {
                        next == null || nm == null ->
                            events += DeliveryEvent("ACTION_UNCONFIRMED", "UNKNOWN", item.atMs, label, null, "finish_action_without_following_screen", open?.index)
                        after != null -> {
                            handledScreens += next.atMs
                            dropoff(item.atMs, label, next.atMs, after, "OBSERVED", "ACTION")
                        }
                        isDeliveryScreen(nm) && nm.hasStepSlider() -> {
                            handledScreens += next.atMs
                            events += DeliveryEvent("DROPOFF_DONE", "OBSERVED", item.atMs, label, next.atMs, "screen.next_stop", open?.index)
                            open = open?.let { it.copy(dropoffs = it.dropoffs + 1) }
                        }
                        else -> events += DeliveryEvent("ACTION_UNCONFIRMED", "UNKNOWN", item.atMs, label, null, "finish_action_but_screen_did_not_confirm", open?.index)
                    }
                }
                tap.kind == "DECLINE" -> if (next != null && nm != null && isDeclineDialog(nm)) {
                    handledScreens += next.atMs
                    events += DeliveryEvent("DECLINE_DIALOG_SHOWN", "OBSERVED", item.atMs, label, next.atMs, "screen.decline_dialog", open?.index)
                }
                else -> Unit
            }
        }

        // ----- path 2: a screen that appeared right after another (no action event) -----
        fun sequence(prev: Item?, cur: Item) {
            val c = cur.markers
            // Real Wolt (11:19, 29.10): card → home flashes for ~1 s → decline dialog → home.
            pendingCardEnd?.let { pend ->
                val age = cur.atMs - pend.atMs
                if (age <= ACCEPT_WINDOW_MS) {
                    when {
                        isDeclineDialog(c) -> {
                            pendingCardEnd = null
                            events += DeliveryEvent("DECLINE_DIALOG_SHOWN", "OBSERVED", cur.atMs, "SCREEN offer_card→${describe(pend.markers)}→decline_dialog", cur.atMs,
                                "screen.decline_dialog_appeared_after_card_closed", open?.index, path = "SCREEN_SEQUENCE")
                            return
                        }
                        isPickupScreen(c) || (age <= CONFIRM_WINDOW_MS && isDeliveryScreen(c)) -> {
                            pendingCardEnd = null
                            val idx = startOrJoin(cur.atMs, "ESTIMATED")
                            events += DeliveryEvent("DELIVERY_ACCEPTED", "ESTIMATED", cur.atMs, "SCREEN offer_card→${describe(pend.markers)}→${describe(c)}", cur.atMs,
                                "screen_sequence:offer_card→delivery_screen(no_action_event)", idx, path = "SCREEN_SEQUENCE", earliest_at_ms = pend.atMs)
                            return
                        }
                        c.offer_card && c.offer_amount_agorot == lastOffer?.offer_amount_agorot ->
                            pendingCardEnd = null // the SAME card came back (redraw) — nothing ended
                        c.offer_card -> flushPendingCardEnd() // a DIFFERENT card: the previous one ended unexplained
                        age > CONFIRM_WINDOW_MS && isDeliveryScreen(c) -> flushPendingCardEnd() // back on the current task: not this card
                        else -> return // keep waiting within the window
                    }
                } else {
                    flushPendingCardEnd()
                }
            }
            val p = prev?.markers ?: return
            // No age limit on `prev`: markers are written only on change, so an unchanged delivery screen
            // can be minutes old. The event time is when `cur` APPEARED. Monitoring gaps reset `prev`.
            val from = when {
                p.offer_card -> "offer_card"
                isDeclineDialog(p) -> "decline_dialog"
                isDeliveryScreen(p) -> "delivery_screen"
                else -> return
            }
            val label = "SCREEN $from→${describe(c)}"
            val seq = "SCREEN_SEQUENCE"
            when (from) {
                "offer_card" -> when {
                    isDeclineDialog(c) -> events += DeliveryEvent("DECLINE_DIALOG_SHOWN", "OBSERVED", cur.atMs, label, cur.atMs, "screen.decline_dialog_appeared", open?.index, path = seq)
                    isDeliveryScreen(c) -> {
                        val idx = startOrJoin(cur.atMs, "ESTIMATED")
                        events += DeliveryEvent("DELIVERY_ACCEPTED", "ESTIMATED", cur.atMs, label, cur.atMs, "screen_sequence:offer_card→delivery_screen(no_action_event)", idx, path = seq, earliest_at_ms = prev.atMs)
                    }
                    c.offer_card -> Unit // the same card updating, or the next card
                    else -> pendingCardEnd = cur // decide after the window: a dialog or delivery screen may still follow
                }
                "decline_dialog" -> when {
                    isDeclineDialog(c) -> Unit
                    c.offer_card -> events += DeliveryEvent("DECLINE_CANCELLED", "ESTIMATED", cur.atMs, label, cur.atMs, "screen_sequence:decline_dialog→offer_card", open?.index, path = seq)
                    else -> events += DeliveryEvent("OFFER_DECLINED", "ESTIMATED", cur.atMs, label, cur.atMs, "screen_sequence:decline_dialog→${describe(c)}", open?.index, path = seq)
                }
                "delivery_screen" -> {
                    val stepWord = (p.sliders.flatMap { it.words } + p.delivery_step_words).firstOrNull { it in DROPOFF_WORDS || it in FINISH_WORDS }
                    val after = afterDeliveryKind(c)
                    when {
                        (stepWord != null || isDropoffScreen(p)) && after != null -> dropoff(cur.atMs, label, cur.atMs, after, "ESTIMATED", seq, earliest = prev.atMs)
                        isPickupScreen(p) && isDropoffScreen(c) -> {
                            events += DeliveryEvent("PICKUP_DONE", "ESTIMATED", cur.atMs, label, cur.atMs, "screen_sequence:pickup_screen→dropoff_screen", open?.index, path = seq, earliest_at_ms = prev.atMs)
                            open = open?.let { it.copy(pickups = it.pickups + 1) }
                        }
                        stepWord != null && isDeliveryScreen(c) && c.hasStepSlider() && c.sliders.map { it.label_redacted } != p.sliders.map { it.label_redacted } -> {
                            events += DeliveryEvent("DROPOFF_DONE", "ESTIMATED", cur.atMs, label, cur.atMs, "screen_sequence:next_stop", open?.index, path = seq)
                            open = open?.let { it.copy(dropoffs = it.dropoffs + 1) }
                        }
                        p.stepWords().any { it in PICKUP_WORDS } && isDeliveryScreen(c) && c.stepWords().none { it in PICKUP_WORDS } -> {
                            events += DeliveryEvent("PICKUP_DONE", "ESTIMATED", cur.atMs, label, cur.atMs, "screen_sequence:pickup_step_advanced", open?.index, path = seq)
                            open = open?.let { it.copy(pickups = it.pickups + 1) }
                        }
                    }
                }
            }
        }
    }
}
