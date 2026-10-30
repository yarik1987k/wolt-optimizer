package dev.yk.offercollector.export

import dev.yk.offercollector.core.DetectedOffer
import dev.yk.offercollector.core.LocationSamplingPolicy
import dev.yk.offercollector.core.OfferLocations
import dev.yk.offercollector.data.LocationSampleEntity
import dev.yk.offercollector.data.ShiftSessionEntity
import kotlinx.serialization.Serializable

@Serializable
data class ExportLocationSession(
    val id: String,
    val started_at_utc: String,
    val ended_at_utc: String?,
    val status: String, // ACTIVE | STOPPED
    val end_reason: String?,
    val sample_count: Int,
    val missing_location_count: Int,
    val monitoring_gap_count: Int,
    val base_interval_s: Int?,
    val stationary_interval_s: Int?,
    val battery_start_pct: Int?,
    val battery_end_pct: Int?,
    val charging_seen: Boolean,
)

@Serializable
data class ExportMonitoringGap(val session_id: String, val from_utc: String, val to_utc: String, val duration_s: Long, val kind: String)

@Serializable
data class LocationQualitySummary(
    val sessions: Int,
    val samples: Int,
    val samples_ok: Int,
    val samples_by_capture_status: Map<String, Int>,
    val samples_by_trigger: Map<String, Int>,
    val offers_in_sessions: Int,
    val offers_with_location: Int,
    val offers_without_location: Int,
    val offers_by_match_confidence: Map<String, Int>,
    val median_accuracy_m: Int?,
    val median_location_age_at_offer_ms: Long?,
    val distinct_cells: Int,
    val monitoring_gaps: List<ExportMonitoringGap>,
    /** Whole-device battery change per hour over sessions without charging; null when not measurable. */
    val battery_drop_pct_per_hour: Double?,
    val battery_note: String = "Device-wide battery level at session start/end; includes screen, Wolt, navigation and every other app. Not this app's own consumption.",
)

/** Everything location-related for export and the diagnostics card. Pure (entities in, values out). */
object LocationReport {
    data class Result(
        val sessions: List<ExportLocationSession>,
        val offerLocations: List<OfferLocations.OfferLocation>,
        val summary: LocationQualitySummary,
    )

    fun captureStatus(s: LocationSampleEntity) = if (s.status == "OK") "OK" else (s.unknownReason ?: "UNKNOWN")

    fun build(shifts: List<ShiftSessionEntity>, samples: List<LocationSampleEntity>, offers: List<DetectedOffer>, iso: (Long) -> String): Result {
        val gaps = shifts.flatMap { sh ->
            val stationaryMs = (sh.stationaryIntervalS ?: sh.baseIntervalS ?: (LocationSamplingPolicy.DEFAULT_STATIONARY_INTERVAL_S))
                .toLong() * 1000
            val times = samples.filter { it.shiftId == sh.id }.map { it.observedAtUtc }
            // 0.3.0 sessions sampled every 5 min by design — judge them against that, not 45 s.
            val expected = if (sh.baseIntervalS == null) 5 * 60_000L else stationaryMs
            LocationSamplingPolicy.gaps(sh.startedAtUtc, sh.endedAtUtc, sh.endReason, times, expected)
                .map { ExportMonitoringGap(sh.id, iso(it.fromMs), iso(it.toMs), (it.toMs - it.fromMs) / 1000, it.kind) }
        }
        val sessions = shifts.map { sh ->
            val own = samples.filter { it.shiftId == sh.id }
            ExportLocationSession(
                id = sh.id, started_at_utc = iso(sh.startedAtUtc), ended_at_utc = sh.endedAtUtc?.let(iso),
                status = if (sh.endedAtUtc == null) "ACTIVE" else "STOPPED", end_reason = sh.endReason,
                sample_count = own.size, missing_location_count = own.count { it.status != "OK" },
                monitoring_gap_count = gaps.count { it.session_id == sh.id },
                base_interval_s = sh.baseIntervalS, stationary_interval_s = sh.stationaryIntervalS,
                battery_start_pct = sh.batteryStartPct, battery_end_pct = sh.batteryEndPct, charging_seen = sh.chargingSeen,
            )
        }
        val links = OfferLocations.link(
            offers,
            samples.map { OfferLocations.Sample(it.id, it.shiftId, it.observedAtUtc, it.trigger, it.status == "OK", it.cellId, it.accuracyM, it.locationAgeMs) },
            shifts.map { OfferLocations.Session(it.id, it.startedAtUtc, it.endedAtUtc ?: it.lastAliveUtc) },
            iso,
        )
        val ok = samples.filter { it.status == "OK" }
        val drops = shifts.filter { !it.chargingSeen && it.batteryStartPct != null && it.batteryEndPct != null && it.endedAtUtc != null && it.endedAtUtc - it.startedAtUtc >= 30 * 60_000 }
        val dropPerHour = if (drops.isEmpty()) null else {
            val pct = drops.sumOf { (it.batteryStartPct!! - it.batteryEndPct!!).toDouble() }
            val hours = drops.sumOf { (it.endedAtUtc!! - it.startedAtUtc) / 3_600_000.0 }
            Math.round(pct / hours * 10) / 10.0
        }
        val summary = LocationQualitySummary(
            sessions = shifts.size,
            samples = samples.size,
            samples_ok = ok.size,
            samples_by_capture_status = samples.groupingBy(::captureStatus).eachCount().toSortedMap(),
            samples_by_trigger = samples.groupingBy { it.trigger }.eachCount().toSortedMap(),
            offers_in_sessions = links.size,
            offers_with_location = links.count { it.match_confidence != "NONE" },
            offers_without_location = links.count { it.match_confidence == "NONE" },
            offers_by_match_confidence = links.groupingBy { it.match_confidence }.eachCount().toSortedMap(),
            median_accuracy_m = OfferLocations.median(ok.mapNotNull { it.accuracyM?.toLong() })?.toInt(),
            median_location_age_at_offer_ms = OfferLocations.median(links.filter { it.match_confidence != "NONE" }.mapNotNull { it.location_age_ms }),
            distinct_cells = ok.mapNotNull { it.cellId }.distinct().size,
            monitoring_gaps = gaps,
            battery_drop_pct_per_hour = dropPerHour,
        )
        return Result(sessions, links, summary)
    }
}
