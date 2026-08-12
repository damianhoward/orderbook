package com.damianhoward.orderbook.model

/**
 * A fill from an incoming order crossing resting liquidity. Prints at the **resting** (maker) price —
 * the taker pays the price already on the book, so price improvement accrues to the taker.
 */
data class Trade(
    val price: Price,
    val size: Long,
    val restingOrderId: Long,
    val incomingOrderId: Long,
    val incomingSide: Side,
)
