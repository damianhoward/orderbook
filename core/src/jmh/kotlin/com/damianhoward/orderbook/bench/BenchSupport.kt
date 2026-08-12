package com.damianhoward.orderbook.bench

import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side

// 10^Price.SCALE — a whole price unit expressed in ticks.
internal const val UNIT = 100_000_000L

// Every resting order is the same size, so a same-size aggressor fills exactly one at the top.
internal const val RESTING_SIZE = 100L

/** Deterministic side from an id, so a run is reproducible. */
internal fun nextSide(id: Long): Side = if (id and 1L == 0L) Side.BID else Side.OFFER

/** A price spread across [priceLevels] levels either side of 100, in exact ticks. */
internal fun priceFor(
    side: Side,
    id: Long,
    priceLevels: Int,
): Price {
    val offset = id % priceLevels
    val whole = if (side == Side.BID) 100L - offset else 100L + offset
    return Price(whole * UNIT)
}
