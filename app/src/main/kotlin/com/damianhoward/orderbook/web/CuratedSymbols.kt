package com.damianhoward.orderbook.web

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

    fun toJson(): String =
        ALL.joinToString(",", "[", "]") { (symbol, name) ->
            """{"symbol":${jsonString(symbol)},"name":${jsonString(name)}}"""
        }

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
