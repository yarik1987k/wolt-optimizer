package dev.yk.offercollector.service

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import dev.yk.offercollector.app
import dev.yk.offercollector.core.StateMarkersExtractor

data class PermissionStatus(
    val notificationAccessGranted: Boolean,
    val notificationListenerConnected: Boolean,
    val accessibilityEnabledInSettings: Boolean,
    val accessibilityServiceConnected: Boolean,
    /** Exempt from battery optimization ("Unrestricted" on Samsung). */
    val batteryUnrestricted: Boolean,
)

object Monitoring {
    fun status(ctx: Context): PermissionStatus = PermissionStatus(
        notificationAccessGranted = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx),
        notificationListenerConnected = ServiceState.listenerConnected.value,
        accessibilityEnabledInSettings = isAccessibilityEnabled(ctx),
        accessibilityServiceConnected = ServiceState.accessibilityConnected.value,
        batteryUnrestricted = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName),
    )

    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, OfferScreenAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    suspend fun start(ctx: Context) {
        ctx.app.settings.setMonitoring(true)
        StateMarkerRecorder.record(ctx.app, StateMarkersExtractor.system("MONITORING_STARTED"))
        // Re-bind the listener if it was unbound by a previous Stop.
        NotificationListenerService.requestRebind(CollectorNotificationListener.component(ctx))
    }

    /** Stops collection immediately: services drop events and the listener unbinds itself. */
    suspend fun stop(ctx: Context) {
        StateMarkerRecorder.record(ctx.app, StateMarkersExtractor.system("MONITORING_STOPPED"))
        ctx.app.settings.setMonitoring(false)
    }
}
