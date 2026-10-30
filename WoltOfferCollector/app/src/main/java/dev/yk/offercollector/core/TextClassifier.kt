package dev.yk.offercollector.core

import kotlinx.serialization.Serializable

/**
 * Redaction-first classification of on-screen / notification text.
 *
 * Raw text is NEVER returned. Each text is reduced to typed tokens. Only token kinds that
 * cannot identify a person keep a value (money, distance, minutes, clock time, stop/order
 * counts, and words from a fixed UI vocabulary). Names, addresses, free text, phone
 * numbers and other numbers are reduced to a kind marker without content.
 */
@Serializable
enum class TokenKind {
    CURRENCY_AMOUNT, // value = agorot
    DISTANCE_KM, // value = km with up to 3 decimals
    DURATION_MIN, // value = minutes
    CLOCK_TIME, // value = HH:mm
    STOPS_COUNT, // value = count
    ORDER_COUNT, // value = count
    UI_KEYWORD, // value = keyword from the fixed vocabulary below
    PHONE_LIKE, // never a value
    LONG_NUMBER, // 5+ digits (order ids, etc.) — never a value
    PLAIN_NUMBER, // could be a house number — never a value
    OTHER_TEXT, // names, addresses, free text — never a value
}

@Serializable
data class RedactedToken(val kind: TokenKind, val value: String? = null)

@Serializable
data class RedactedText(
    val tokens: List<RedactedToken>,
    val charCount: Int,
    val script: String, // HEBREW | LATIN | MIXED | DIGITS | NONE
) {
    /** Compact human-readable form, e.g. "[AMOUNT ₪23.50] [UI:איסוף] [TEXT×2]". */
    fun display(): String {
        if (tokens.isEmpty()) return "[EMPTY]"
        val parts = mutableListOf<String>()
        var otherRun = 0
        fun flush() {
            if (otherRun > 0) parts += if (otherRun == 1) "[TEXT]" else "[TEXT×$otherRun]"
            otherRun = 0
        }
        for (t in tokens) {
            if (t.kind == TokenKind.OTHER_TEXT) {
                otherRun++
                continue
            }
            flush()
            parts += when (t.kind) {
                TokenKind.CURRENCY_AMOUNT -> "[AMOUNT ₪${formatAgorot(t.value!!.toLong())}]"
                TokenKind.DISTANCE_KM -> "[DIST ${t.value} km]"
                TokenKind.DURATION_MIN -> "[MIN ${t.value}]"
                TokenKind.CLOCK_TIME -> "[TIME ${t.value}]"
                TokenKind.STOPS_COUNT -> "[STOPS ${t.value}]"
                TokenKind.ORDER_COUNT -> "[ORDERS ${t.value}]"
                TokenKind.UI_KEYWORD -> "[UI:${t.value}]"
                TokenKind.PHONE_LIKE -> "[PHONE-REDACTED]"
                TokenKind.LONG_NUMBER -> "[LONG#]"
                TokenKind.PLAIN_NUMBER -> "[#]"
                TokenKind.OTHER_TEXT -> ""
            }
        }
        flush()
        return parts.joinToString(" ")
    }
}

fun formatAgorot(agorot: Long): String {
    val sign = if (agorot < 0) "-" else ""
    val a = kotlin.math.abs(agorot)
    return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
}

object TextClassifier {
    /**
     * Fixed UI vocabulary (Hebrew + English + Russian) that may appear on a courier offer screen.
     * Only these exact words are ever stored as text. Matching is whole-word, case-insensitive.
     */
    val UI_VOCABULARY: List<String> = listOf(
        "הצעה", "הזמנה", "הזמנות", "משלוח", "משלוחים", "איסוף", "מסירה", "יעד", "עצירות", "עצירה",
        "קבל", "קבלה", "אשר", "דחה", "דחייה", "דלג", "תשלום", "רווח", "בונוס", "טיפ", "מרחק", "זמן", "דקות",
        "מסעדה", "חנות", "לקוח", "חדש", "חדשה",
        "offer", "order", "orders", "delivery", "deliveries", "task", "pickup", "pick-up", "dropoff", "drop-off",
        "accept", "decline", "reject", "skip", "earnings", "earning", "fee", "pay", "payout", "bonus", "tip",
        "distance", "venue", "restaurant", "store", "customer", "new", "stops", "stop", "batch",
        "סרב", "сбросить", "отказаться",
        // Russian (the courier app follows the phone language)
        "заказ", "заказы", "задание", "задача", "доставка", "доставки", "забрать", "получение", "вручение",
        "клиент", "ресторан", "магазин", "заведение", "принять", "отклонить", "пропустить", "новый", "новое",
        "новая", "оплата", "заработок", "доход", "бонус", "чаевые", "расстояние", "остановки", "остановка",
    )

    /**
     * Courier-STATE words (availability, delivery steps). Single words only, all generic UI
     * vocabulary — never names or addresses. Grouped by the state they hint at; the grouping is a
     * hypothesis to be confirmed by field tests A/B, not a proven mapping.
     */
    val STATE_VOCABULARY: Map<String, List<String>> = mapOf(
        "AVAILABILITY" to listOf(
            // ru
            "онлайн", "офлайн", "оффлайн", "линии", "линию", "линия", "доступен", "доступна", "недоступен", "недоступна",
            "занят", "занята", "перерыв", "пауза", "смена", "смену", "смены", "начать", "закончить", "выйти", "выход",
            // en
            "online", "offline", "available", "unavailable", "busy", "break", "pause", "paused", "shift",
            // he
            "מחובר", "מחוברת", "מנותק", "מנותקת", "זמין", "זמינה", "הפסקה", "משמרת", "התחל", "התחלה",
        ),
        "DELIVERY_STEP" to listOf(
            // ru
            "забрал", "забрала", "получил", "получила", "доставить", "доставлено", "доставлен", "доставлена", "вручить",
            "вручено", "прибыл", "прибыла", "прибытие", "пути", "маршрут", "навигация", "позвонить", "ожидание",
            "активное", "текущее", "завершить", "завершено", "отменить", "отменено",
            // en
            "picked", "collected", "arrived", "arrive", "deliver", "delivered", "dropped", "navigate", "navigation", "route",
            "call", "waiting", "active", "current", "complete", "completed", "finish", "finished", "cancel", "cancelled",
            // he
            "נאסף", "נאספה", "אספתי", "נמסר", "נמסרה", "מסרתי", "הגעתי", "הגעה", "בדרך", "ניווט", "התקשר", "המתנה",
            "פעיל", "פעילה", "נוכחי", "סיים", "סיום", "הושלם", "בטל", "בוטל",
        ),
        // Whether the RESTAURANT has the order ready. Courier, 29.10.2026: "Готово means the order is ready at the
        // restaurant" — it shows on offer cards too, so it is NOT a delivery step (11:40 false accept).
        "RESTAURANT_STATUS" to listOf("готов", "готово", "готовится", "ready", "מוכן", "מוכנה"),
    )
    private val stateWordGroup: Map<String, String> =
        STATE_VOCABULARY.flatMap { (group, words) -> words.map { it.lowercase() to group } }.toMap()

    /** "AVAILABILITY" / "DELIVERY_STEP" / null for a (lower-cased) UI keyword. */
    fun stateGroupOf(word: String): String? = stateWordGroup[word.lowercase()]

    private val vocabLower = (UI_VOCABULARY + STATE_VOCABULARY.values.flatten()).map { it.lowercase() }.toSet()

    // Numbers like 23 / 23.5 / 23,50 / 1,234.50 / 1 234
    private const val NUM = """\d{1,3}(?:[ ,.]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""

    // Java's \b only knows ASCII word characters, so it never fires after a Hebrew or Cyrillic
    // letter. END is a Unicode-aware "not followed by a letter or digit" instead.
    private const val END = """(?![\p{L}\d])"""

    private val phone = Regex("""(?:\+?972[- ]?|(?<!\d)0)(?:[2-9]\d?)[- ]?\d{3}[- ]?\d{4}(?!\d)""")
    private val currency = Regex(
        """₪ ?(?<a>$NUM)|(?<b>$NUM) ?(?:₪|ש"ח|ש'ח|שח$END|ILS$END|NIS$END)|(?:ILS|NIS) ?(?<c>$NUM)""",
        RegexOption.IGNORE_CASE,
    )
    private val distance = Regex(
        """(?<n>\d+(?:[.,]\d{1,3})?) ?(?<u>ק"מ|קמ|ק'מ|km|קילומטר(?:ים)?|מטר(?:ים)?|מ'|m|км|м)$END""",
        RegexOption.IGNORE_CASE,
    )
    private val duration = Regex("""(?<n>\d{1,3}) ?(?:דקות|דקה|דק'|דק|min(?:s|utes?)?|мин(?:ут[аы]?|\.)?)$END""", RegexOption.IGNORE_CASE)
    private val clock = Regex("""(?<!\d)(?<h>[01]?\d|2[0-3]):(?<m>[0-5]\d)(?!\d)""")
    private val stops = Regex("""(?<n>\d{1,2}) ?(?:עצירות|עצירה|stops?|остановк[аиу]?|остановок)$END""", RegexOption.IGNORE_CASE)
    private val orders = Regex(
        """(?<n>\d{1,2}) ?(?:הזמנות|משלוחים|orders?|deliveries|tasks?|заказ(?:а|ов)?|доставк[аи]|доставок)$END""",
        RegexOption.IGNORE_CASE,
    )
    /** Count AFTER the word, as on Wolt's multi-order card: "<word> заказы 2" (29.10.2026, 2 drop-offs). */
    private val ordersAfter = Regex(
        """(?<!\p{L})(?:הזמנות|orders|заказы|заказов) ?[:·•×x]? ?(?<n>\d{1,2})(?![\d.,:/-])""",
        RegexOption.IGNORE_CASE,
    )
    private val number = Regex("""\d+(?:[.,:/-]\d+)*""")
    private val word = Regex("""[\p{L}][\p{L}\p{M}'"\-]*""")

    private data class Hit(val start: Int, val end: Int, val token: RedactedToken)

    fun classify(raw: String?): RedactedText {
        if (raw.isNullOrBlank()) return RedactedText(emptyList(), 0, "NONE")
        val text = TextNormalizer.normalize(raw)
        val hits = mutableListOf<Hit>()
        val taken = BooleanArray(text.length)
        fun free(r: IntRange) = r.all { !taken[it] }
        fun take(r: IntRange, token: RedactedToken) {
            r.forEach { taken[it] = true }
            hits += Hit(r.first, r.last + 1, token)
        }

        // Order matters: phones first so they can never be read as amounts or times.
        phone.findAll(text).forEach { take(it.range, RedactedToken(TokenKind.PHONE_LIKE)) }
        currency.findAll(text).forEach { m ->
            if (!free(m.range)) return@forEach
            val numText = m.groups["a"]?.value ?: m.groups["b"]?.value ?: m.groups["c"]?.value
            val agorot = numText?.let { AmountParser.toAgorot(it) }
            if (agorot != null) take(m.range, RedactedToken(TokenKind.CURRENCY_AMOUNT, agorot.toString()))
        }
        distance.findAll(text).forEach { m ->
            if (!free(m.range)) return@forEach
            val n = m.groups["n"]!!.value.replace(',', '.').toBigDecimalOrNull() ?: return@forEach
            val unit = m.groups["u"]!!.value.lowercase()
            val meters = unit.startsWith("מטר") || unit == "מ'" || unit == "m" || unit == "м"
            val km = if (meters) n.divide(1000.toBigDecimal()) else n
            take(m.range, RedactedToken(TokenKind.DISTANCE_KM, km.stripTrailingZeros().toPlainString()))
        }
        stops.findAll(text).forEach { m -> if (free(m.range)) take(m.range, RedactedToken(TokenKind.STOPS_COUNT, m.groups["n"]!!.value)) }
        orders.findAll(text).forEach { m -> if (free(m.range)) take(m.range, RedactedToken(TokenKind.ORDER_COUNT, m.groups["n"]!!.value)) }
        ordersAfter.findAll(text).forEach { m -> if (free(m.range)) take(m.range, RedactedToken(TokenKind.ORDER_COUNT, m.groups["n"]!!.value)) }
        duration.findAll(text).forEach { m -> if (free(m.range)) take(m.range, RedactedToken(TokenKind.DURATION_MIN, m.groups["n"]!!.value)) }
        clock.findAll(text).forEach { m ->
            if (free(m.range)) take(m.range, RedactedToken(TokenKind.CLOCK_TIME, "${m.groups["h"]!!.value.padStart(2, '0')}:${m.groups["m"]!!.value}"))
        }
        number.findAll(text).forEach { m ->
            if (!free(m.range)) return@forEach
            val digits = m.value.count { it.isDigit() }
            take(m.range, RedactedToken(if (digits >= 5) TokenKind.LONG_NUMBER else TokenKind.PLAIN_NUMBER))
        }
        word.findAll(text).forEach { m ->
            if (!free(m.range)) return@forEach
            val w = m.value.lowercase()
            take(m.range, if (w in vocabLower) RedactedToken(TokenKind.UI_KEYWORD, w) else RedactedToken(TokenKind.OTHER_TEXT))
        }
        val hebrew = text.any { it in '֐'..'׿' }
        val latin = text.any { it in 'a'..'z' || it in 'A'..'Z' }
        val script = when {
            hebrew && latin -> "MIXED"
            hebrew -> "HEBREW"
            latin -> "LATIN"
            text.any { it.isDigit() } -> "DIGITS"
            else -> "NONE"
        }
        return RedactedText(hits.sortedBy { it.start }.map { it.token }, text.length, script)
    }
}
