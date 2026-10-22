package com.damianhoward.orderbook.quote

import com.damianhoward.marketdata.model.Quote
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The `/api/{symbol}/quote` response body — everything the topbar needs to label the instrument. */
fun Quote.toJson(): String =
    Json.encodeToString(
        QuoteBody(
            symbol = symbol,
            name = instrument.name,
            exchange = instrument.exchange,
            currency = instrument.currency,
            last = last.toPlainString(),
            change = change.toPlainString(),
            changePercent = changePercent.toPlainString(),
            marketOpen = marketOpen,
            asOfMillis = asOf.toEpochMilli(),
        ),
    )

// Decimals travel as strings so the client shows exactly what was quoted, not a float's nearest.
@Serializable
private data class QuoteBody(
    val symbol: String,
    val name: String,
    val exchange: String,
    val currency: String,
    val last: String,
    val change: String,
    val changePercent: String,
    val marketOpen: Boolean,
    val asOfMillis: Long,
)
