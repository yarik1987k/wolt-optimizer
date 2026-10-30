package dev.yk.offercollector.core

/**
 * What happened to each offer card, taken from the delivery detector's events (post-hoc, read-only).
 * An offer gets the events between its first appearance and the next offer (at most [MAX_AFTER_MS]
 * after the card was last seen — a card can stay up a minute with no new snapshot). Nothing here is new evidence: it only re-labels detector events.
 */
object OfferOutcomes {
    const val MAX_AFTER_MS = 5 * 60_000L

    enum class Kind { ACCEPTED, DECLINED, DECLINE_DIALOG, UNKNOWN }

    data class Outcome(val kind: Kind, val evidence: String, val atMs: Long)

    data class Window(val firstSeenMs: Long, val lastSeenMs: Long)

    /** Result is parallel to [offers] (any order); null = no detector event for that offer. */
    fun match(offers: List<Window>, events: List<DeliveryEventDetector.DeliveryEvent>): List<Outcome?> {
        val starts = offers.map { it.firstSeenMs }.sorted()
        val sorted = events.sortedBy { it.action_at_ms }
        return offers.map { o ->
            val next = starts.firstOrNull { it > o.firstSeenMs } ?: Long.MAX_VALUE
            val end = minOf(next, o.lastSeenMs + MAX_AFTER_MS)
            var out: Outcome? = null
            for (e in sorted) {
                if (e.action_at_ms < o.firstSeenMs || e.action_at_ms >= end) continue
                out = when (e.type) {
                    "DELIVERY_ACCEPTED" -> Outcome(Kind.ACCEPTED, e.evidence, e.action_at_ms)
                    "OFFER_DECLINED" -> Outcome(Kind.DECLINED, e.evidence, e.action_at_ms)
                    // The dialog alone is not a decline: the courier can still go back to the card.
                    "DECLINE_DIALOG_SHOWN" -> if (out?.kind == Kind.DECLINED) out else Outcome(Kind.DECLINE_DIALOG, e.evidence, e.action_at_ms)
                    "DECLINE_CANCELLED" -> null
                    "OFFER_ENDED_UNKNOWN" -> out ?: Outcome(Kind.UNKNOWN, e.evidence, e.action_at_ms)
                    else -> out
                }
            }
            out
        }
    }
}
