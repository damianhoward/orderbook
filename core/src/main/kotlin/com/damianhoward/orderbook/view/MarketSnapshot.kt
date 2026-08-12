package com.damianhoward.orderbook.view

import com.damianhoward.orderbook.model.Order
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side

/** A price level aggregated across every resting order at that price, with the running depth total. */
data class DepthLevel(
    val price: Price,
    val size: Long,
    val cumulative: Long,
)

/** One printed trade, tagged with the side of the incoming (aggressing) order and the time it matched. */
data class TapeEntry(
    val price: Price,
    val size: Long,
    val incomingSide: Side,
    val timeMillis: Long,
)

/**
 * Immutable, render-ready projection: each side aggregated by price (best first) with cumulative
 * depth, plus the recent tape. Pure and serialisable, so it is unit-tested directly and the web
 * layer stays a thin transport over it.
 */
data class MarketSnapshot(
    val timeMillis: Long,
    val bids: List<DepthLevel>,
    val asks: List<DepthLevel>,
    val tape: List<TapeEntry>,
) {
    fun toJson(): String {
        val tapeJson = tape.joinToString(",", "[", "]", transform = ::tapeJson)
        return depthJson().dropLast(1) + ""","tape":$tapeJson}"""
    }

    /** The book only — both sides' aggregated levels, no tape. What the L2 egress publishes. */
    fun depthJson(): String {
        val bidsJson = bids.joinToString(",", "[", "]", transform = ::levelJson)
        val asksJson = asks.joinToString(",", "[", "]", transform = ::levelJson)
        return """{"ts":$timeMillis,"bids":$bidsJson,"asks":$asksJson}"""
    }

    private fun levelJson(level: DepthLevel): String {
        val price = quote(level.price.toString())
        return """{"price":$price,"size":${level.size},"cumulative":${level.cumulative}}"""
    }

    private fun tapeJson(entry: TapeEntry): String {
        val price = quote(entry.price.toString())
        val side = quote(entry.incomingSide.name)
        return """{"price":$price,"size":${entry.size},"side":$side,"time":${entry.timeMillis}}"""
    }

    companion object {
        /** Builds a snapshot from each side's resting orders (best level first) and the current tape. */
        fun of(
            bids: List<Order>,
            asks: List<Order>,
            tape: List<TapeEntry>,
            timeMillis: Long,
        ): MarketSnapshot = MarketSnapshot(timeMillis, aggregate(bids), aggregate(asks), tape)

        /** Merges orders sharing a price into a single level (preserving order) and runs the cumulative total. */
        fun aggregate(orders: List<Order>): List<DepthLevel> {
            val byPrice = LinkedHashMap<Price, Long>()
            orders.forEach { byPrice.merge(it.price, it.size, Long::plus) }
            var cumulative = 0L
            return byPrice.map { (price, size) ->
                cumulative += size
                DepthLevel(price, size, cumulative)
            }
        }

        private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
