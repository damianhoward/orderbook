package com.damianhoward.orderbook.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestPrice {
    @Test
    fun parsesDecimalStringToExactTicks() {
        assertEquals(345_000_000L, Price.of("3.45").ticks)
        assertEquals(0L, Price.of("0").ticks)
        assertEquals(100_000_000L, Price.of("1").ticks)
    }

    @Test
    fun parseAndRenderRoundTrips() {
        assertEquals("3.45000000", Price.of("3.45").toString())
        assertEquals("19.00000000", Price.of("19").toString())
    }

    @Test
    fun equalDecimalsShareKeyRegardlessOfFormatting() {
        // The exact-integer representation is why this holds where Double would drift.
        assertEquals(Price.of("3.45"), Price.of("3.450"))
        assertEquals(Price.of("3.45").ticks, Price.of("3.45000000").ticks)
    }

    @Test
    fun ordersByTicks() {
        assertTrue(Price.of("3.45") < Price.of("3.46"))
        assertTrue(Price.of("100") > Price.of("99.99999999"))
        assertEquals(0, Price.of("7").compareTo(Price.of("7")))
    }

    @Test
    fun rejectsNegativePrice() {
        assertThrows(IllegalArgumentException::class.java) { Price(-1L) }
        assertThrows(IllegalArgumentException::class.java) { Price.of("-0.01") }
    }

    @Test
    fun rejectsPrecisionBeyondScale() {
        // 9 decimal places exceeds SCALE (8) and cannot be represented exactly.
        assertThrows(ArithmeticException::class.java) { Price.of("1.000000001") }
    }

    @Test
    fun rejectsNonNumeric() {
        assertThrows(NumberFormatException::class.java) { Price.of("abc") }
    }
}
