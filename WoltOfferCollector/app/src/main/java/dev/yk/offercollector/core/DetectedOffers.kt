package dev.yk.offercollector.core

/** One stored screen snapshot, reduced to what the offer list needs. */
data class OfferScreen(val observedAtMs: Long, val fields: CandidateFields, val screenId: String? = null)

/**
 * An offer card seen on screen. This is what was DISPLAYED — the app never knows whether the
 * offer was accepted or declined.
 */
data class DetectedOffer(
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val amountAgorot: Long,
    val distanceKm: String?,
    val durationMin: Int?,
    val stopsCount: Int?,
    /** Orders in a multi-order offer ("заказы 2"); null = not shown on the card. */
    val orderCount: Int? = null,
    val pickupNames: List<String>,
    val packageSize: String? = null,
    val snapshotCount: Int,
    /** screen_events id of the first snapshot — the offer's stable id in exports. */
    val firstScreenId: String? = null,
    /** The business's address (only after the courier confirmed the layout). */
    val pickupAddress: String? = null,
)

object DetectedOffers {
    /** Words of an offer card's accept button. A price + one of these = an offer card. */
    val ACCEPT_WORDS = setOf("принять", "accept", "קבל", "אשר")

    /** Snapshots of the same card (same price) less than this apart belong to one offer. */
    const val SAME_OFFER_GAP_MS = 60_000L

    fun isOfferCard(f: CandidateFields) = f.amounts_agorot.isNotEmpty() && f.ui_keywords.any { it in ACCEPT_WORDS }

    fun build(screens: List<OfferScreen>): List<DetectedOffer> {
        val out = mutableListOf<DetectedOffer>()
        for (s in screens.sortedBy { it.observedAtMs }) {
            if (!isOfferCard(s.fields)) continue
            // The card's price is the first amount in tree order (the card sits above other widgets).
            val amount = s.fields.amounts_agorot.first()
            val last = out.lastOrNull()
            // Rows stored before the label filter may still hold a Wolt step label in the name slot.
            val pickups = s.fields.pickup_names.filterNot(PickupNames::isWoltLabel)
            // Same price, same distance (when both are shown), within the gap = the same card. The pickup
            // line may change while the card scrolls or a bundle lists its businesses one by one
            // (real shift 29.10.2026: ₪31.09 / 4.2 km showed "Tiv Ta'am", then "Moses Shop").
            val km = s.fields.distances_km.firstOrNull()
            val sameDistance = last == null || last.distanceKm == null || km == null || last.distanceKm == km
            if (last != null && last.amountAgorot == amount && sameDistance && s.observedAtMs - last.lastSeenMs <= SAME_OFFER_GAP_MS) {
                out[out.lastIndex] = last.copy(
                    lastSeenMs = s.observedAtMs,
                    distanceKm = last.distanceKm ?: s.fields.distances_km.firstOrNull(),
                    durationMin = last.durationMin ?: s.fields.durations_min.firstOrNull(),
                    stopsCount = last.stopsCount ?: s.fields.stops_counts.firstOrNull(),
                    orderCount = last.orderCount ?: s.fields.order_counts.firstOrNull(),
                    pickupNames = (last.pickupNames + pickups).distinct(),
                    packageSize = last.packageSize ?: s.fields.package_size,
                    pickupAddress = last.pickupAddress ?: s.fields.pickup_address,
                    snapshotCount = last.snapshotCount + 1,
                )
            } else {
                out += DetectedOffer(
                    firstSeenMs = s.observedAtMs,
                    lastSeenMs = s.observedAtMs,
                    amountAgorot = amount,
                    distanceKm = s.fields.distances_km.firstOrNull(),
                    durationMin = s.fields.durations_min.firstOrNull(),
                    stopsCount = s.fields.stops_counts.firstOrNull(),
                    orderCount = s.fields.order_counts.firstOrNull(),
                    pickupNames = pickups,
                    packageSize = s.fields.package_size,
                    snapshotCount = 1,
                    firstScreenId = s.screenId,
                    pickupAddress = s.fields.pickup_address,
                )
            }
        }
        return out
    }
}
