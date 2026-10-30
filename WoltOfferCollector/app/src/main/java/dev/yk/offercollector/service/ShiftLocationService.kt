package dev.yk.offercollector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.yk.offercollector.app
import dev.yk.offercollector.core.LocationSamplingPolicy
import dev.yk.offercollector.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service of type "location" that exists only while a shift runs.
 * Started from the app's own screen (user tap), which is what Android 14+ requires for a
 * location FGS with while-in-use permission — no background-location permission is requested.
 */
class ShiftLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    @Volatile private var endedCleanly = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    client.removeLocationUpdates(callback) // spec §4: stop GPS updates first
                    ShiftLocation.end(app, intent.getStringExtra(EXTRA_REASON) ?: "USER")
                    endedCleanly = true
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                if (!ShiftLocation.hasPermission(this)) { stopSelf(); return START_NOT_STICKY }
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification(),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
                )
                if (loop == null) loop = scope.launch { run() }
            }
        }
        // Not sticky: after a crash the shift is closed as SERVICE_STOPPED, never silently resumed.
        return START_NOT_STICKY
    }

    private var baseMs = LocationSamplingPolicy.DEFAULT_BASE_INTERVAL_S * 1000L
    private var stationaryMs = LocationSamplingPolicy.DEFAULT_STATIONARY_INTERVAL_S * 1000L
    private var adaptive = true
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fixes = result.locations.map(ShiftLocation::toFix)
            scope.launch {
                var changed = false
                fixes.forEach { changed = ShiftLocation.onFix(app, it, adaptive, baseMs, stationaryMs) || changed }
                if (changed) request(ShiftLocation.intervalMs)
            }
        }
    }

    private val trackCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locs = result.locations.toList()
            scope.launch { locs.forEach { ShiftLocation.onTrackFix(app, it) } }
        }
    }

    /** The precise track: a fix every few seconds while the shift runs (stored only when it moved or 30 s passed). */
    @SuppressLint("MissingPermission")
    private fun requestTrack() {
        if (!ShiftLocation.hasPermission(this)) return
        client.removeLocationUpdates(trackCallback)
        client.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TRACK_INTERVAL_MS)
                .setMinUpdateIntervalMillis(TRACK_INTERVAL_MS / 2)
                .setWaitForAccurateLocation(false)
                .build(),
            trackCallback, Looper.getMainLooper(),
        )
    }

    /** (Re)subscribes to fixes at [intervalMs]. Stops the old request first — one stream at a time. */
    @SuppressLint("MissingPermission")
    private fun request(intervalMs: Long) {
        if (!ShiftLocation.hasPermission(this)) return
        client.removeLocationUpdates(callback)
        client.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .setWaitForAccurateLocation(false)
                .build(),
            callback, Looper.getMainLooper(),
        )
    }

    private suspend fun run() {
        val app = app
        val s = app.settings.current()
        // Acceptance #1: no consent → no collection, even if the service was somehow started.
        if (!s.locationConsent) { endedCleanly = true; stopSelf(); return }
        baseMs = s.locationBaseIntervalS.coerceIn(15, 300) * 1000L
        adaptive = s.locationAdaptive
        stationaryMs = maxOf(baseMs, LocationSamplingPolicy.DEFAULT_STATIONARY_INTERVAL_S * 1000L)
        val started = System.currentTimeMillis()
        if (ShiftLocation.shiftId.value == null) ShiftLocation.begin(app, (baseMs / 1000).toInt(), if (adaptive) (stationaryMs / 1000).toInt() else null)
        ShiftLocation.sample(app, "SHIFT_START", "UNKNOWN", "UNKNOWN", "SESSION", started)
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            request(baseMs)
            if (s.preciseTrack) requestTrack()
        }
        while (scope.isActive && ShiftLocation.shiftId.value != null) {
            delay(10_000)
            ShiftLocation.tick(app)
            if (!app.settings.current().locationConsent) { ShiftLocation.end(app, "CONSENT_REVOKED"); break }
            if (LocationSamplingPolicy.shiftExpired(started, System.currentTimeMillis())) { ShiftLocation.end(app, "MAX_DURATION"); break }
        }
        client.removeLocationUpdates(callback)
        client.removeLocationUpdates(trackCallback)
        endedCleanly = true
        stopSelf()
    }

    override fun onDestroy() {
        // Killed without a clean end (e.g. the user swiped the notification's app away): close the shift.
        client.removeLocationUpdates(callback)
        if (!endedCleanly && ShiftLocation.shiftId.value != null) runBlocking { ShiftLocation.end(app, "SERVICE_STOPPED") }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "משמרת פעילה", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("איסוף מיקום פעיל — משמרת")
            .setContentText("נשמר אזור של כ-500 מ׳ בלבד. לסיום המשמרת: Offer Collector ← סיום משמרת.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** Precise track request interval (the 5 m / 30 s filter decides what is stored). */
        private const val TRACK_INTERVAL_MS = 5_000L
        private const val CHANNEL = "shift_location"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "dev.yk.offercollector.STOP_SHIFT"
        private const val EXTRA_REASON = "reason"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, ShiftLocationService::class.java))

        fun stop(ctx: Context, reason: String = "USER") {
            ctx.startService(Intent(ctx, ShiftLocationService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_REASON, reason))
        }
    }
}
