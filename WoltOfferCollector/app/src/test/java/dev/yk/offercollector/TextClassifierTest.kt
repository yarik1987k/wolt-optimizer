package dev.yk.offercollector

import dev.yk.offercollector.core.TextClassifier
import dev.yk.offercollector.core.TokenKind
import dev.yk.offercollector.core.TokenKind.CURRENCY_AMOUNT
import dev.yk.offercollector.core.TokenKind.OTHER_TEXT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextClassifierTest {
    private fun kinds(s: String) = TextClassifier.classify(s).tokens.map { it.kind }
    private fun values(s: String, k: TokenKind) = TextClassifier.classify(s).tokens.filter { it.kind == k }.map { it.value }

    /** Real card 29.10.2026 11:36 (2 drop-offs) was stored as "[TEXT] [UI:заказы] [#]": the count comes AFTER the word.
     *  The first word is unknown (redacted), so any word stands in for it here. */
    @Test fun orderCountAfterTheWord() {
        assertEquals(listOf("2"), values("Слово заказы 2", TokenKind.ORDER_COUNT))
        assertEquals(listOf("3"), values("Orders: 3", TokenKind.ORDER_COUNT))
        assertEquals(listOf("2"), values("2 заказа", TokenKind.ORDER_COUNT))
        assertTrue(values("заказы 123456", TokenKind.ORDER_COUNT).isEmpty())
        assertTrue(values("заказы 12:30", TokenKind.ORDER_COUNT).isEmpty())
        assertTrue(values("заказы", TokenKind.ORDER_COUNT).isEmpty())
    }

    @Test fun shekelAmountsInHebrewRtlText() {
        assertEquals(listOf("2350"), values("‏₪23.50", CURRENCY_AMOUNT))
        assertEquals(listOf("2350"), values("23.50 ₪", CURRENCY_AMOUNT))
        assertEquals(listOf("2350"), values("רווח: 23,50 ₪", CURRENCY_AMOUNT))
        assertEquals(listOf("4000"), values("40 ש״ח", CURRENCY_AMOUNT))
        assertEquals(listOf("4000"), values("40 ש\"ח למשלוח", CURRENCY_AMOUNT))
        assertEquals(listOf("123450"), values("₪1,234.50", CURRENCY_AMOUNT))
        assertEquals(listOf("2500", "3000"), values("₪25–₪30", CURRENCY_AMOUNT))
        assertEquals(listOf("1890"), values("ILS 18.90", CURRENCY_AMOUNT))
    }

    @Test fun numbersThatAreNotPricesNeverBecomeAmounts() {
        assertTrue(values("הזמנה #123456", CURRENCY_AMOUNT).isEmpty())
        assertTrue(kinds("הזמנה #123456").contains(TokenKind.LONG_NUMBER))
        assertTrue(values("3.2 ק״מ", CURRENCY_AMOUNT).isEmpty())
        assertEquals(listOf("3.2"), values("3.2 ק״מ", TokenKind.DISTANCE_KM))
        assertEquals(listOf("0.8"), values("800 מטר", TokenKind.DISTANCE_KM))
        assertEquals(listOf("12"), values("12 דק'", TokenKind.DURATION_MIN))
        assertEquals(listOf("19:45"), values("איסוף עד 19:45", TokenKind.CLOCK_TIME))
        assertEquals(listOf("2"), values("2 עצירות", TokenKind.STOPS_COUNT))
        assertEquals(listOf("3"), values("3 הזמנות", TokenKind.ORDER_COUNT))
        assertTrue(values("23 דקות", CURRENCY_AMOUNT).isEmpty())
    }

    @Test fun russianUnitsAndVocabulary() {
        assertEquals(listOf("3.4"), values("3,4 км", TokenKind.DISTANCE_KM))
        assertEquals(listOf("0.8"), values("800 м", TokenKind.DISTANCE_KM))
        assertEquals(listOf("12"), values("12 мин", TokenKind.DURATION_MIN))
        assertEquals(listOf("12"), values("12 минут", TokenKind.DURATION_MIN))
        assertTrue(values("12 мин", TokenKind.DISTANCE_KM).isEmpty()) // "м" must not eat "мин"
        assertEquals(listOf("2"), values("2 остановки", TokenKind.STOPS_COUNT))
        assertEquals(listOf("3"), values("3 заказа", TokenKind.ORDER_COUNT))
        assertEquals(listOf("2523"), values("₪25,23", CURRENCY_AMOUNT))
        assertEquals(listOf("принять", "заказ"), values("Принять заказ", TokenKind.UI_KEYWORD))
        val addr = TextClassifier.classify("Иван Петров, ул. Герцль 20")
        assertTrue(addr.tokens.all { it.value == null })
    }

    @Test fun unitsAtEndOfHebrewWordAreRecognised() {
        // Regression: Java's \b does not fire after a Hebrew letter.
        assertEquals(listOf("4000"), values("40 שח", CURRENCY_AMOUNT))
        assertEquals(listOf("7"), values("7 דק", TokenKind.DURATION_MIN))
        assertTrue(values("7 דקלים", TokenKind.DURATION_MIN).isEmpty())
    }

    @Test fun phoneNumbersAreRedactedAndNeverParsed() {
        val r = TextClassifier.classify("התקשר 050-1234567")
        // "התקשר" (call) is a fixed state-vocabulary word since the courier-state tests; the number never is.
        assertEquals(listOf(TokenKind.UI_KEYWORD, TokenKind.PHONE_LIKE), r.tokens.map { it.kind })
        assertTrue(r.tokens.all { it.kind != TokenKind.PHONE_LIKE || it.value == null })
        assertTrue(kinds("+972 52 123 4567").contains(TokenKind.PHONE_LIKE))
        assertFalse(r.display().contains("050"))
    }

    @Test fun namesAndAddressesKeepNoContent() {
        val r = TextClassifier.classify("יוסי כהן, רחוב הרצל 20 ראשון לציון")
        assertTrue(r.tokens.all { it.value == null })
        val shown = r.display()
        assertFalse(shown.contains("יוסי"))
        assertFalse(shown.contains("הרצל"))
        assertFalse(shown.contains("20"))
        assertEquals("[TEXT×4] [#] [TEXT×2]", shown)
    }

    @Test fun uiVocabularyIsKeptOnlyForFixedWords() {
        val r = TextClassifier.classify("Accept order · קבל הזמנה")
        assertEquals(listOf("accept", "order", "קבל", "הזמנה"), r.tokens.filter { it.kind == TokenKind.UI_KEYWORD }.map { it.value })
    }

    @Test fun displayIsCompact() {
        assertEquals("[UI:איסוף] [TEXT] [AMOUNT ₪23.50] [DIST 3.2 km]", TextClassifier.classify("איסוף פיצה ₪23.50 3.2 km").display())
        assertEquals("[EMPTY]", TextClassifier.classify("   ").display())
    }
}
