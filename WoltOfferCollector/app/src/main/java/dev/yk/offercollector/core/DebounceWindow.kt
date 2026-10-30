package dev.yk.offercollector.core

/**
 * Decides when to take an accessibility snapshot for a burst of window events:
 * wait for [quietMs] without new events, but never longer than [maxWaitMs] after the
 * first event of the burst — so a ~5 second offer window cannot be missed by a constant
 * stream of WINDOW_CONTENT_CHANGED events.
 */
class DebounceWindow(private val quietMs: Long = 250, private val maxWaitMs: Long = 900) {
    private var burstStartMs: Long? = null
    var eventsInBurst: Int = 0
        private set

    /** @return delay in ms before the snapshot should run, given an event at [nowMs]. */
    fun onEvent(nowMs: Long, isStateChange: Boolean): Long {
        val start = burstStartMs ?: nowMs.also { burstStartMs = it }
        eventsInBurst++
        val quiet = if (isStateChange) 150 else quietMs
        val remainingMax = maxWaitMs - (nowMs - start)
        return maxOf(0, minOf(quiet, remainingMax))
    }

    fun burstStartMs(): Long? = burstStartMs

    fun reset() {
        burstStartMs = null
        eventsInBurst = 0
    }
}
