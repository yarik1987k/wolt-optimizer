package dev.yk.offercollector.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live connection state reported by the services themselves (not only "enabled in Settings"). */
object ServiceState {
    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected
    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected

    internal fun setListener(v: Boolean) { _listenerConnected.value = v }
    internal fun setAccessibility(v: Boolean) { _accessibilityConnected.value = v }
}
