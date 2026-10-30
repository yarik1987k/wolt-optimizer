package dev.yk.offercollector.core

/**
 * Restaurant (pickup) names — and, with the courier's consent, the business's address — from a Wolt
 * offer card. These are the ONLY raw texts the app stores: businesses, not people. The destination
 * (customer) is never stored.
 *
 * Location on the card (real Wolt cards, ru-RU UI): below the distance line there may be a
 * package-size tag ("Тяжелая (более 13 кг)"); the restaurant is the first line after the distance
 * that is NOT such a tag. Several pickups in one line are split on ", ".
 */
object PickupNames {
    const val MAX_CHARS = 60
    const val MAX_WORDS = 8
    const val MAX_ADDRESS_CHARS = 120

    /** One text line of the card, in tree order: raw text (memory only) + its classification. */
    data class Line(val raw: String, val redacted: RedactedText)

    data class Result(
        val names: List<String>,
        /** Wolt's package-size tag, e.g. "Тяжелая (более 13 кг)" — a fixed UI label, not personal. */
        val packageSize: String?,
        /** Lines between the distance line and the first address-like line (structure only; to learn multi-pickup layouts). */
        val linesBetweenDistanceAndAddress: Int?,
        /** Why nothing was stored, when the pickup line was found but rejected. */
        val rejectedReason: String?,
        /**
         * The BUSINESS's address: the first address-like line (has a long number, e.g. a postal code)
         * within two lines after the business name. Stored with the courier's consent (29.10.2026) —
         * a business address, not a person's. Only for single-business cards; never the drop-off.
         */
        val pickupAddress: String? = null,
    )

    private fun RedactedText.has(k: TokenKind) = tokens.any { it.kind == k }

    // A tag needs a weight unit ("13 кг"), or a size word followed by a bracket ("Тяжелая (…)").
    // A bare word like "Light" is NOT enough — it could be a restaurant ("Light Burger").
    private val weightUnit = Regex("""\d+\s*(кг|kg|ק"ג|קג|ק'ג)(?![\p{L}\d])""", RegexOption.IGNORE_CASE)
    private val sizeWordWithBracket = Regex("""^(тяжел\p{L}*|средн\p{L}*|л[её]гк\p{L}*|heavy|medium|light|small|large|כבד\p{L}*|בינוני\p{L}*|קל\p{L}?)\s*\(""", RegexOption.IGNORE_CASE)

    /** Package-size / weight tag line (not a restaurant). */
    fun isWeightTag(raw: String): Boolean {
        val t = TextNormalizer.normalize(raw)
        return weightUnit.containsMatchIn(t) || sizeWordWithBracket.containsMatchIn(t)
    }

    private fun looksLikeAddress(l: Line) =
        l.redacted.has(TokenKind.LONG_NUMBER) || l.redacted.has(TokenKind.PHONE_LIKE)

    fun extract(lines: List<Line>): Result {
        val amountAt = lines.indexOfFirst { it.redacted.has(TokenKind.CURRENCY_AMOUNT) }
        if (amountAt < 0) return Result(emptyList(), null, null, null)
        val distanceAt = (amountAt until lines.size).firstOrNull { lines[it].redacted.has(TokenKind.DISTANCE_KM) }
            ?: return Result(emptyList(), null, null, "NO_DISTANCE_LINE")
        val after = lines.drop(distanceAt + 1)
        val addressIdx = after.indexOfFirst(::looksLikeAddress).takeIf { it >= 0 }
        val tag = after.firstOrNull()?.takeIf { isWeightTag(it.raw) }
        val packageSize = tag?.let { TextNormalizer.normalize(it.raw).take(MAX_CHARS) }
        val pickup = after.getOrNull(if (tag != null) 1 else 0)
            ?: return Result(emptyList(), packageSize, addressIdx, "NO_LINE_AFTER_DISTANCE")
        if (isWeightTag(pickup.raw)) return Result(emptyList(), packageSize, addressIdx, "WEIGHT_TAG_NOT_NAME")
        val reason = safetyRejection(pickup)
        if (reason != null) return Result(emptyList(), packageSize, addressIdx, reason)
        val names = TextNormalizer.normalize(pickup.raw).split(Regex(""",\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val nameAt = after.indexOf(pickup)
        val address = if (names.size != 1) null else after.getOrNull(nameAt + 1)?.takeIf(::looksLikeAddress)
            ?.takeIf { !it.redacted.has(TokenKind.PHONE_LIKE) && !it.redacted.has(TokenKind.CURRENCY_AMOUNT) }
            ?.let { TextNormalizer.normalize(it.raw).take(MAX_ADDRESS_CHARS) }
        return Result(names, packageSize, addressIdx, null, address)
    }

    /**
     * Wolt's own step labels that can sit where the business name usually is (real shift 29.10.2026):
     * "Заберите заказы: 2" (bundle header), "Выдача покупателю", "Проверка документов". Not a business —
     * and the line after them is not known to be one either, so nothing is stored.
     */
    private val woltLabel = Regex("""^(заберите заказ|выдача покупател|проверка документ)""", RegexOption.IGNORE_CASE)

    fun isWoltLabel(raw: String) = woltLabel.containsMatchIn(TextNormalizer.normalize(raw).trim())

    /** Null when the line is safe to store as a business name. */
    fun safetyRejection(line: Line): String? {
        val text = TextNormalizer.normalize(line.raw)
        val words = line.redacted.tokens.count { it.kind == TokenKind.OTHER_TEXT || it.kind == TokenKind.UI_KEYWORD }
        return when {
            isWoltLabel(line.raw) -> "WOLT_STEP_LABEL"
            line.redacted.has(TokenKind.PHONE_LIKE) -> "PHONE_LIKE"
            line.redacted.has(TokenKind.LONG_NUMBER) -> "LONG_NUMBER"
            line.redacted.has(TokenKind.CURRENCY_AMOUNT) -> "AMOUNT_NOT_NAME"
            text.length > MAX_CHARS -> "TOO_LONG"
            words > MAX_WORDS -> "TOO_MANY_WORDS"
            words == 0 -> "NO_WORDS"
            else -> null
        }
    }
}
