package dev.yk.offercollector.core

/**
 * Parses a numeric amount string to integer agorot (minor units) without floating point.
 * Accepts "23.50", "23,50", "1,234.50", "1.234,50", "1 234.50". Returns null when the
 * string is not an unambiguous amount — the caller must then store NULL, never a guess.
 */
object AmountParser {
    fun toAgorot(raw: String): Long? {
        var s = TextNormalizer.normalize(raw).replace("₪", "").replace(" ", "").trim()
        if (s.isEmpty()) return null
        val negative = s.startsWith("-")
        if (negative) s = s.substring(1)
        val hasComma = ',' in s
        val hasDot = '.' in s
        s = when {
            hasComma && hasDot ->
                if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            hasComma -> if (Regex("""^\d+,\d{1,2}$""").matches(s)) s.replace(',', '.') else s.replace(",", "")
            // A lone dot is always decimal; "1.234" (dot as thousands) is ambiguous and rejected below.
            else -> s
        }
        val m = Regex("""^(\d+)(?:\.(\d{1,2}))?$""").matchEntire(s) ?: return null
        val whole = m.groupValues[1].toLongOrNull() ?: return null
        val frac = (m.groupValues[2] + "00").take(2).toLong()
        val agorot = whole * 100 + frac
        return if (negative) -agorot else agorot
    }
}
