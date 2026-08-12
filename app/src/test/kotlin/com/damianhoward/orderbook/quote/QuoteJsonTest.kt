package com.damianhoward.orderbook.quote

import com.damianhoward.marketdata.model.Instrument
import com.damianhoward.marketdata.model.Quote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class QuoteJsonTest {
    @Test
    fun `serialises the fields the topbar needs to label the instrument`() {
        val quote =
            Quote(
                instrument = Instrument("AAPL", "Apple Inc.", "USD", "NasdaqGS"),
                last = BigDecimal("317.31"),
                previousClose = BigDecimal("315.32"),
                dayHigh = BigDecimal("323.45"),
                dayLow = BigDecimal("315.78"),
                asOf = Instant.ofEpochSecond(1784134800L),
                marketOpen = false,
            )

        assertEquals(
            """{"symbol":"AAPL","name":"Apple Inc.","exchange":"NasdaqGS","currency":"USD",""" +
                """"last":"317.31","change":"1.99","changePercent":"0.63","marketOpen":false,""" +
                """"asOfMillis":1784134800000}""",
            quote.toJson(),
        )
    }
}
