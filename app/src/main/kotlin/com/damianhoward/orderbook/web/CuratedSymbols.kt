package com.damianhoward.orderbook.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The symbols suggested in the picker's dropdown — a small, sector-varied set so nobody needs to
 * already know a ticker. Free-text entry for any other real listed symbol still works; this list
 * is a starting point, not a restriction.
 */
object CuratedSymbols {
    val ALL =
        listOf(
            "AAPL" to "Apple",
            "MSFT" to "Microsoft",
            "JPM" to "JPMorgan Chase",
            "GS" to "Goldman Sachs",
            "XOM" to "ExxonMobil",
            "CAT" to "Caterpillar",
            "BA" to "Boeing",
            "DIS" to "Disney",
            "KO" to "Coca-Cola",
            "JNJ" to "Johnson & Johnson",
            "TSLA" to "Tesla",
            "WMT" to "Walmart",
        )

    fun toJson(): String = Json.encodeToString(ALL.map { (symbol, name) -> Listing(symbol, name) })

    @Serializable
    private class Listing(
        private val symbol: String,
        private val name: String,
    )
}
