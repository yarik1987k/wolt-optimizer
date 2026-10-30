package dev.yk.offercollector.core

enum class NotificationEventType { POSTED, UPDATED, REMOVED }

/**
 * Android calls onNotificationPosted both for new notifications and for updates of an
 * existing one (same key). This classifies them so an update is never counted as a new
 * notification, and exact repeats within [repeatWindowMs] are dropped.
 */
class NotificationDeduplicator(private val repeatWindowMs: Long = 1_500) {
    private data class State(var active: Boolean, var signature: String, var lastAtMs: Long)

    private val states = HashMap<String, State>()

    /** Seeds knowledge from storage after a service restart. */
    fun seed(keyHash: String, active: Boolean, signature: String, atMs: Long) {
        states[keyHash] = State(active, signature, atMs)
    }

    fun isKnown(keyHash: String) = keyHash in states

    /** @return the event type to store, or null when the event is an exact repeat to drop. */
    fun onPosted(keyHash: String, contentSignature: String, nowMs: Long): NotificationEventType? {
        val s = states[keyHash]
        if (s == null || !s.active) {
            states[keyHash] = State(true, contentSignature, nowMs)
            return NotificationEventType.POSTED
        }
        val repeat = s.signature == contentSignature && nowMs - s.lastAtMs <= repeatWindowMs
        s.signature = contentSignature
        s.lastAtMs = nowMs
        return if (repeat) null else NotificationEventType.UPDATED
    }

    /** @return REMOVED, or null when the same removal was already reported moments ago (duplicate delivery). */
    fun onRemoved(keyHash: String, nowMs: Long): NotificationEventType? {
        val s = states[keyHash]
        if (s != null && !s.active && nowMs - s.lastAtMs <= repeatWindowMs) return null
        if (s == null) states[keyHash] = State(false, "", nowMs) else { s.active = false; s.lastAtMs = nowMs }
        return NotificationEventType.REMOVED
    }

    fun clear() = states.clear()
}
