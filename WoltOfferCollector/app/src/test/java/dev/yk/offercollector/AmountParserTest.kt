package dev.yk.offercollector

import dev.yk.offercollector.core.AmountParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountParserTest {
    @Test fun parsesDecimalFormats() {
        assertEquals(2350L, AmountParser.toAgorot("23.50"))
        assertEquals(2350L, AmountParser.toAgorot("23,50"))
        assertEquals(2300L, AmountParser.toAgorot("23"))
        assertEquals(2305L, AmountParser.toAgorot("23.05"))
        assertEquals(2350L, AmountParser.toAgorot("23.5"))
        assertEquals(123450L, AmountParser.toAgorot("1,234.50"))
        assertEquals(123450L, AmountParser.toAgorot("1.234,50"))
        assertEquals(123400L, AmountParser.toAgorot("1,234"))
        assertEquals(123450L, AmountParser.toAgorot("1 234.50"))
        assertEquals(2350L, AmountParser.toAgorot("‏₪ 23.50"))
        assertEquals(-700L, AmountParser.toAgorot("-7"))
    }

    @Test fun rejectsAmbiguousOrNonAmounts() {
        assertNull(AmountParser.toAgorot(""))
        assertNull(AmountParser.toAgorot("abc"))
        assertNull(AmountParser.toAgorot("23.505"))
        assertNull(AmountParser.toAgorot("1.234")) // dot-as-thousands is ambiguous: never guess
        assertNull(AmountParser.toAgorot("12:30"))
    }
}
