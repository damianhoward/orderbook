package com.damianhoward.orderbook.model

/**
 * A fill: an incoming order crossing resting liquidity. Prints at the **maker's** price — the taker
 * pays the price already on the book, so price improvement accrues to the taker.
 *
 * Maker and taker rather than resting and incoming, because that is what the published fill schema
 * already calls them (`makerOrderId`, `takerOrderId`) and what the rest of the industry does. One
 * concept should not carry one name in the domain and another on the wire; a reader tracing a fill
 * from the book to the ledger should not have to translate on the way.
 *
 * Both parties are named, because between identified counterparties a fill is a transfer rather
 * than something that happened to one side: the buyer's position moves up and the seller's down by
 * the same size, and a ledger recording only one side of that cannot balance. They are also who a
 * quote-driven layer notifies, and it must be able to without asking the book who was resting there
 * — by the time a fill is reported, that order may be gone.
 */
data class Trade(
    val price: Price,
    val size: Long,
    val makerOrderId: Long,
    val takerOrderId: Long,
    val takerSide: Side,
    val maker: Owner,
    val taker: Owner,
) {
    /** The buyer, whose position this fill increases. */
    val buyer: Owner get() = if (takerSide == Side.BID) taker else maker

    /** The seller, whose position this fill decreases by the same size. */
    val seller: Owner get() = if (takerSide == Side.BID) maker else taker
}
