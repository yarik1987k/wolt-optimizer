package dev.yk.offercollector.core

object TextNormalizer {
    private val bidiMarks = Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069\\u061c]")

    /** Strips bidi control marks, unifies spaces and Hebrew geresh/gershayim variants. */
    fun normalize(text: String): String =
        text.replace(bidiMarks, "")
            .replace(' ', ' ')
            .replace(' ', ' ')
            .replace('״', '"')
            .replace('׳', '\'')
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("\\s+"), " ")
            .trim()
}
