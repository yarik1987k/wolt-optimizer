package dev.yk.offercollector.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val generalConsent: Boolean = false,
    val notificationConsent: Boolean = false,
    val accessibilityConsent: Boolean = false,
    val selectedPackage: String? = null,
    val monitoringEnabled: Boolean = false,
    val installSalt: String? = null,
    /** Separate, explicit consent for coarse location during shifts the courier starts. */
    val locationConsent: Boolean = false,
    val locationBaseIntervalS: Int = 45,
    val locationAdaptive: Boolean = true,
    /** Debug only: keep exact coordinates on the device for 24 h (never exported). */
    val locationDebugExact: Boolean = false,
    /** Names the courier gave to ~500 m areas, e.g. "home". Local only. */
    val areaNames: Map<String, String> = emptyMap(),
    /** Pickup-address gate: UNVERIFIED until the courier confirms on a real card (then CONFIRMED) or rejects. */
    val pickupAddressMode: String = "UNVERIFIED",
    /** Vehicle cost per km (agorot) for shadow mode; null = not set → no estimate (never a default). */
    val vehicleCostPerKmAgorot: Long? = null,
    /** Shadow-mode experimental threshold (percent). */
    val shadowMarginPct: Int = 15,
    /** Planned end hour (local, 0–23) for the next shift; null = not set. */
    val plannedEndHour: Int? = null,
    /** Precise shift track (~5 m, every few seconds) for the route map and distance; on by the courier's request. */
    val preciseTrack: Boolean = true,
    /** The courier's own offer rules (shown before a shift, checked against history; never enforced or alerted). */
    val ruleAcceptAddedOffers: Boolean? = null,
    val ruleMinPriceAgorot: Long? = null,
) {
    /** Cost per km used for net figures: the courier's value, else ₪1.10 (the courier's stated default). */
    val effectiveVehicleCostPerKmAgorot: Long get() = vehicleCostPerKmAgorot ?: DEFAULT_VEHICLE_COST_PER_KM_AGOROT

    companion object {
        const val DEFAULT_VEHICLE_COST_PER_KM_AGOROT = 110L
    }
}

/** Settings only (DataStore). Event data lives in Room. */
class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val general = booleanPreferencesKey("consent_general_v1")
        val notif = booleanPreferencesKey("consent_notifications_v1")
        val a11y = booleanPreferencesKey("consent_accessibility_v1")
        val pkg = stringPreferencesKey("selected_package")
        val monitoring = booleanPreferencesKey("monitoring_enabled")
        val salt = stringPreferencesKey("install_salt")
        val location = booleanPreferencesKey("consent_location_shift_v1")
        val locInterval = androidx.datastore.preferences.core.intPreferencesKey("location_base_interval_s")
        val locAdaptive = booleanPreferencesKey("location_adaptive")
        val locDebug = booleanPreferencesKey("location_debug_exact")
        val areaNames = stringPreferencesKey("area_names_v1")
        val pickupMode = stringPreferencesKey("pickup_address_mode_v1")
        val vehicleCost = androidx.datastore.preferences.core.longPreferencesKey("vehicle_cost_per_km_agorot")
        val shadowMargin = androidx.datastore.preferences.core.intPreferencesKey("shadow_margin_pct")
        val plannedEnd = androidx.datastore.preferences.core.intPreferencesKey("planned_end_hour")
        val preciseTrack = booleanPreferencesKey("precise_track_v1")
        val ruleAdded = stringPreferencesKey("rule_accept_added_v1") // ACCEPT | DECLINE (absent = no rule)
        val ruleMinPrice = androidx.datastore.preferences.core.longPreferencesKey("rule_min_price_agorot_v1")
    }

    val settings: StateFlow<AppSettings> = context.dataStore.data
        .map { p ->
            AppSettings(
                generalConsent = p[K.general] ?: false,
                notificationConsent = p[K.notif] ?: false,
                accessibilityConsent = p[K.a11y] ?: false,
                selectedPackage = p[K.pkg],
                monitoringEnabled = p[K.monitoring] ?: false,
                installSalt = p[K.salt],
                locationConsent = p[K.location] ?: false,
                locationBaseIntervalS = p[K.locInterval] ?: 45,
                locationAdaptive = p[K.locAdaptive] ?: true,
                locationDebugExact = p[K.locDebug] ?: false,
                areaNames = decodeNames(p[K.areaNames]),
                pickupAddressMode = p[K.pickupMode] ?: "UNVERIFIED",
                vehicleCostPerKmAgorot = p[K.vehicleCost],
                shadowMarginPct = p[K.shadowMargin] ?: 15,
                plannedEndHour = p[K.plannedEnd],
                preciseTrack = p[K.preciseTrack] ?: true,
                ruleAcceptAddedOffers = p[K.ruleAdded]?.let { it == "ACCEPT" },
                ruleMinPriceAgorot = p[K.ruleMinPrice],
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** Reads the persisted settings directly (not the possibly not-yet-loaded StateFlow). */
    suspend fun current(): AppSettings = readNow()

    private suspend fun readNow(): AppSettings {
        val p = context.dataStore.data.first()
        return AppSettings(p[K.general] ?: false, p[K.notif] ?: false, p[K.a11y] ?: false, p[K.pkg], p[K.monitoring] ?: false, p[K.salt], p[K.location] ?: false,
            p[K.locInterval] ?: 45, p[K.locAdaptive] ?: true, p[K.locDebug] ?: false, decodeNames(p[K.areaNames]), p[K.pickupMode] ?: "UNVERIFIED",
            p[K.vehicleCost], p[K.shadowMargin] ?: 15, p[K.plannedEnd], p[K.preciseTrack] ?: true,
            p[K.ruleAdded]?.let { it == "ACCEPT" }, p[K.ruleMinPrice])
    }

    suspend fun salt(): String {
        readNow().installSalt?.let { return it }
        val fresh = newSalt()
        context.dataStore.edit { it[K.salt] = fresh }
        return fresh
    }

    suspend fun rotateSalt() = context.dataStore.edit { it[K.salt] = newSalt() }

    suspend fun setGeneralConsent(v: Boolean) = context.dataStore.edit { it[K.general] = v }
    suspend fun setNotificationConsent(v: Boolean) = context.dataStore.edit { it[K.notif] = v }
    suspend fun setAccessibilityConsent(v: Boolean) = context.dataStore.edit { it[K.a11y] = v }
    suspend fun setSelectedPackage(pkg: String?) = context.dataStore.edit { if (pkg == null) it.remove(K.pkg) else it[K.pkg] = pkg }
    suspend fun setMonitoring(v: Boolean) = context.dataStore.edit { it[K.monitoring] = v }
    suspend fun setLocationConsent(v: Boolean) = context.dataStore.edit { it[K.location] = v }
    suspend fun setLocationInterval(s: Int) = context.dataStore.edit { it[K.locInterval] = s }
    suspend fun setLocationAdaptive(v: Boolean) = context.dataStore.edit { it[K.locAdaptive] = v }
    suspend fun setLocationDebugExact(v: Boolean) = context.dataStore.edit { it[K.locDebug] = v }
    suspend fun setPreciseTrack(v: Boolean) = context.dataStore.edit { it[K.preciseTrack] = v }
    suspend fun setRuleAcceptAdded(v: Boolean?) = context.dataStore.edit { if (v == null) it.remove(K.ruleAdded) else it[K.ruleAdded] = if (v) "ACCEPT" else "DECLINE" }
    suspend fun setRuleMinPrice(agorot: Long?) = context.dataStore.edit { if (agorot == null) it.remove(K.ruleMinPrice) else it[K.ruleMinPrice] = agorot }
    suspend fun setVehicleCost(agorot: Long?) = context.dataStore.edit { if (agorot == null) it.remove(K.vehicleCost) else it[K.vehicleCost] = agorot }
    suspend fun setShadowMargin(pct: Int) = context.dataStore.edit { it[K.shadowMargin] = pct }
    suspend fun setPlannedEndHour(h: Int?) = context.dataStore.edit { if (h == null) it.remove(K.plannedEnd) else it[K.plannedEnd] = h }
    suspend fun setPickupAddressMode(v: String) = context.dataStore.edit { it[K.pickupMode] = v }
    suspend fun setAreaName(cellId: String, name: String) = context.dataStore.edit { p ->
        val m = decodeNames(p[K.areaNames]).toMutableMap()
        if (name.isBlank()) m.remove(cellId) else m[cellId] = name
        p[K.areaNames] = kotlinx.serialization.json.Json.encodeToString(m)
    }

    private fun decodeNames(raw: String?): Map<String, String> =
        raw?.let { runCatching { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()

    /** Withdraws every consent and stops monitoring. */
    suspend fun withdrawAll() = context.dataStore.edit {
        it[K.general] = false
        it[K.notif] = false
        it[K.a11y] = false
        it[K.monitoring] = false
        it[K.location] = false
        it[K.locDebug] = false
    }

    private fun newSalt(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
