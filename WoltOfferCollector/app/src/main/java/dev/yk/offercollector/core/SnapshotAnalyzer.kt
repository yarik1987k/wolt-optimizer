package dev.yk.offercollector.core

import kotlinx.serialization.Serializable

/** One accessible node, as read from the tree. Text fields are raw and must not be persisted. */
data class RawNode(
    val className: String?,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val depth: Int,
    /** Slider/progress position 0–100 from AccessibilityNodeInfo.rangeInfo, when the element has one. */
    val rangePct: Int? = null,
)

@Serializable
data class NodeSummary(
    val viewId: String? = null, // developer resource id without package, e.g. "offer_price"
    val cls: String? = null, // short class name, e.g. "TextView"
    val source: String, // TEXT | CONTENT_DESCRIPTION
    val redacted: String, // RedactedText.display()
    val kinds: List<TokenKind>,
    /** Slider position 0–100, when Android exposes one (non-personal). */
    val range_pct: Int? = null,
)

@Serializable
data class CandidateFields(
    val amounts_agorot: List<Long> = emptyList(),
    val distances_km: List<String> = emptyList(),
    val durations_min: List<Int> = emptyList(),
    val clock_times: List<String> = emptyList(),
    val stops_counts: List<Int> = emptyList(),
    val order_counts: List<Int> = emptyList(),
    val ui_keywords: List<String> = emptyList(),
    val phone_like_count: Int = 0,
    val other_text_token_count: Int = 0,
    val nodes: List<NodeSummary> = emptyList(),
    val nodes_truncated: Boolean = false,
    /** Restaurant names from an offer card (business names only; never the destination). */
    val pickup_names: List<String> = emptyList(),
    val pickup_rejected_reason: String? = null,
    /** Wolt package-size tag from the offer card, e.g. "Тяжелая (более 13 кг)". */
    val package_size: String? = null,
    val card_lines_between_distance_and_address: Int? = null,
    /** Business (pickup) address — single-business cards only; never a customer address. */
    val pickup_address: String? = null,
)

enum class ScreenCategory { OFFER_CANDIDATE, OTHER, UNKNOWN }

data class SnapshotAnalysis(
    val accessibleNodeCount: Int,
    val textNodeCount: Int,
    val fields: CandidateFields,
    val category: ScreenCategory,
    val fingerprint: String,
    /** True when at least one node carries a non-blank text or content description. */
    val hasText: Boolean,
)

object SnapshotAnalyzer {
    const val PARSE_VERSION = "p1-pickup-addr-1"
    private const val MAX_STORED_NODES = 120

    private val offerWords = setOf(
        "הצעה", "הזמנה", "משלוח", "איסוף", "מסירה", "קבל", "אשר", "דחה", "עצירות",
        "offer", "order", "delivery", "pickup", "pick-up", "dropoff", "drop-off", "accept", "decline", "reject", "stops", "task",
        "принять", "отклонить", "задание", "заказ", "доставка", "доставки", "забрать",
    )

    /**
     * @param recentSourceNotification true when the selected app posted a notification shortly
     * before (only used as supporting evidence, never on its own).
     */
    fun analyze(windowClass: String?, nodes: List<RawNode>, recentSourceNotification: Boolean): SnapshotAnalysis {
        val summaries = mutableListOf<NodeSummary>()
        val amounts = mutableListOf<Long>()
        val distances = mutableListOf<String>()
        val durations = mutableListOf<Int>()
        val clocks = mutableListOf<String>()
        val stops = mutableListOf<Int>()
        val orders = mutableListOf<Int>()
        val keywords = linkedSetOf<String>()
        var phones = 0
        var other = 0
        var textNodes = 0
        val cardLines = mutableListOf<PickupNames.Line>() // raw text kept in memory only for this call
        for (n in nodes) {
            for ((source, raw) in listOf("TEXT" to n.text, "CONTENT_DESCRIPTION" to n.contentDescription)) {
                if (raw.isNullOrBlank()) continue
                // Content descriptions often duplicate the text; count each node once.
                if (source == "CONTENT_DESCRIPTION" && raw == n.text) continue
                textNodes++
                val r = TextClassifier.classify(raw)
                if (source == "TEXT") cardLines += PickupNames.Line(raw, r)
                for (t in r.tokens) when (t.kind) {
                    TokenKind.CURRENCY_AMOUNT -> amounts += t.value!!.toLong()
                    TokenKind.DISTANCE_KM -> distances += t.value!!
                    TokenKind.DURATION_MIN -> durations += t.value!!.toInt()
                    TokenKind.CLOCK_TIME -> clocks += t.value!!
                    TokenKind.STOPS_COUNT -> stops += t.value!!.toInt()
                    TokenKind.ORDER_COUNT -> orders += t.value!!.toInt()
                    TokenKind.UI_KEYWORD -> keywords += t.value!!
                    TokenKind.PHONE_LIKE -> phones++
                    TokenKind.OTHER_TEXT -> other++
                    else -> Unit
                }
                summaries += NodeSummary(
                    viewId = n.viewId?.substringAfter(":id/"),
                    cls = n.className?.substringAfterLast('.'),
                    source = source,
                    redacted = r.display(),
                    kinds = r.tokens.map { it.kind }.distinct(),
                    range_pct = n.rangePct,
                )
            }
            // A slider with no text still matters (its position is the signal); it is not a text node.
            if (n.text.isNullOrBlank() && n.contentDescription.isNullOrBlank() &&
                (n.rangePct != null || UserActions.isSlider(n.viewId?.substringAfter(":id/"), n.className?.substringAfterLast('.')))
            ) {
                summaries += NodeSummary(
                    viewId = n.viewId?.substringAfter(":id/"),
                    cls = n.className?.substringAfterLast('.'),
                    source = "RANGE",
                    redacted = "[EMPTY]",
                    kinds = emptyList(),
                    range_pct = n.rangePct,
                )
            }
        }
        val isOfferCard = amounts.isNotEmpty() && keywords.any { it in DetectedOffers.ACCEPT_WORDS }
        val pickup = if (isOfferCard) PickupNames.extract(cardLines) else null
        cardLines.clear()
        val fields = CandidateFields(
            amounts_agorot = amounts,
            distances_km = distances,
            durations_min = durations,
            clock_times = clocks,
            stops_counts = stops,
            order_counts = orders,
            ui_keywords = keywords.toList(),
            phone_like_count = phones,
            other_text_token_count = other,
            nodes = summaries.take(MAX_STORED_NODES),
            nodes_truncated = summaries.size > MAX_STORED_NODES,
            pickup_names = pickup?.names.orEmpty(),
            pickup_rejected_reason = pickup?.rejectedReason,
            package_size = pickup?.packageSize,
            card_lines_between_distance_and_address = pickup?.linesBetweenDistanceAndAddress,
            pickup_address = pickup?.pickupAddress,
        )
        val hasOfferWord = keywords.any { it in offerWords }
        val category = when {
            textNodes == 0 -> ScreenCategory.UNKNOWN
            amounts.isNotEmpty() && (hasOfferWord || recentSourceNotification) -> ScreenCategory.OFFER_CANDIDATE
            else -> ScreenCategory.OTHER
        }
        // Structure + non-personal values only: a different price yields a different fingerprint,
        // a different customer name does not. The event's source class is deliberately left out:
        // it alternates (ViewGroup/FrameLayout) while the screen itself is unchanged.
        val signature = buildString {
            summaries.forEach { append(it.viewId).append(':').append(it.cls).append(':').append(it.redacted).append(':').append(it.range_pct).append(';') }
            append(fields.pickup_names.joinToString("|"))
        }
        return SnapshotAnalysis(
            accessibleNodeCount = nodes.size,
            textNodeCount = textNodes,
            fields = fields,
            category = category,
            fingerprint = Hashing.sha256(signature),
            hasText = textNodes > 0,
        )
    }
}
