package dev.yk.offercollector.core

import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * Courier location at each offer (spec §5, §7 OfferLocation) — DERIVED from offers + samples at
 * export/diagnostics time, so nothing is stored twice. The location is the courier's, not the restaurant's.
 *
 * match_confidence:
 *  HIGH   — OK sample triggered by the offer itself (≤ 90 s from the card) whose fix is ≤ 30 s from the sample moment;
 *  MEDIUM — same, fix 30–60 s away;
 *  LOW    — no offer-triggered OK sample, but an OK periodic/other sample ≤ 60 s from the card;
 *  NONE   — no valid location (cell null). Never filled from older samples.
 */
object OfferLocations {
    const val LOCATION_PARSER_VERSION = "loc-2"
    const val OFFER_TRIGGER_WINDOW_MS = 90_000L
    const val ANY_SAMPLE_WINDOW_MS = 60_000L

    data class Sample(
        val id: String, val sessionId: String, val atMs: Long, val trigger: String, val ok: Boolean,
        val cellId: String?, val accuracyM: Int?, val locationAgeMs: Long?,
    )

    data class Session(val id: String, val startMs: Long, val endMs: Long?)

    @Serializable
    data class OfferLocation(
        val offer_id: String,
        val offer_first_seen_at_utc: String,
        val offer_amount_agorot: Long,
        val session_id: String?,
        val location_sample_id: String?,
        val timestamp_utc: String?,
        val grid_cell_id: String?,
        val accuracy_meters: Int?,
        val location_age_ms: Long?,
        val match_confidence: String,
    )

    fun link(offers: List<DetectedOffer>, samples: List<Sample>, sessions: List<Session>, iso: (Long) -> String): List<OfferLocation> =
        offers.mapNotNull { o ->
            val session = sessions.firstOrNull { o.firstSeenMs >= it.startMs && o.firstSeenMs <= (it.endMs ?: Long.MAX_VALUE) }
                ?: return@mapNotNull null // only offers seen during a location session
            val inSession = samples.filter { it.sessionId == session.id && it.ok }
            val byOffer = inSession.filter { it.trigger == "OFFER_PRESENTED" && abs(it.atMs - o.firstSeenMs) <= OFFER_TRIGGER_WINDOW_MS }
                .minByOrNull { abs(it.atMs - o.firstSeenMs) }
            val any = inSession.filter { abs(it.atMs - o.firstSeenMs) <= ANY_SAMPLE_WINDOW_MS }.minByOrNull { abs(it.atMs - o.firstSeenMs) }
            val (s, conf) = when {
                byOffer != null && (byOffer.locationAgeMs ?: Long.MAX_VALUE) <= 30_000 -> byOffer to "HIGH"
                byOffer != null -> byOffer to "MEDIUM"
                any != null -> any to "LOW"
                else -> null to "NONE"
            }
            OfferLocation(
                offer_id = o.firstScreenId ?: "first_seen:${o.firstSeenMs}",
                offer_first_seen_at_utc = iso(o.firstSeenMs),
                offer_amount_agorot = o.amountAgorot,
                session_id = session.id,
                location_sample_id = s?.id,
                timestamp_utc = s?.let { iso(it.atMs) },
                grid_cell_id = s?.cellId,
                accuracy_meters = s?.accuracyM,
                location_age_ms = s?.locationAgeMs,
                match_confidence = conf,
            )
        }

    fun median(values: List<Long>): Long? = values.sorted().let { if (it.isEmpty()) null else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
}
