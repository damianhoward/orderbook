package com.damianhoward.orderbook.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CuratedSymbolsTest {
    @Test
    fun `every curated symbol passes the registry's own shape validation`() {
        CuratedSymbols.ALL.forEach { (symbol, _) ->
            assertEquals(symbol, SessionRegistry.normalize(symbol), "curated symbol '$symbol' must be a valid shape")
        }
    }

    @Test
    fun `serialises as a JSON array of symbol-name pairs`() {
        assertEquals(
            """[{"symbol":"AAPL","name":"Apple"},""" +
                """{"symbol":"MSFT","name":"Microsoft"},""" +
                """{"symbol":"JPM","name":"JPMorgan Chase"},""" +
                """{"symbol":"GS","name":"Goldman Sachs"},""" +
                """{"symbol":"XOM","name":"ExxonMobil"},""" +
                """{"symbol":"CAT","name":"Caterpillar"},""" +
                """{"symbol":"BA","name":"Boeing"},""" +
                """{"symbol":"DIS","name":"Disney"},""" +
                """{"symbol":"KO","name":"Coca-Cola"},""" +
                """{"symbol":"JNJ","name":"Johnson & Johnson"},""" +
                """{"symbol":"TSLA","name":"Tesla"},""" +
                """{"symbol":"WMT","name":"Walmart"}]""",
            CuratedSymbols.toJson(),
        )
    }

    @Test
    fun `covers twelve sector-varied names, not a handful of mega-cap tech`() {
        assertTrue(CuratedSymbols.ALL.size == 12)
        assertTrue(
            CuratedSymbols.ALL
                .map { it.first }
                .toSet()
                .size == 12,
            "no duplicate symbols",
        )
    }
}
