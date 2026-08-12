package com.damianhoward.orderbook.engine

import com.damianhoward.orderbook.book.OrderBook
import com.damianhoward.orderbook.model.Order
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.model.Trade

/** A matching strategy: crosses an incoming order against resting liquidity and returns the fills. */
interface Matcher {
    fun submit(order: Order): List<Trade>
}

/**
 * A submit was rejected because [orderId] is already resting. Order ids identify live liquidity, so
 * reusing one is a client error with no safe interpretation: the book's `addOrder` would replace
 * the resting order, silently cancelling size its owner still believes is working, and any fill
 * later reported against that id would be ambiguous between the two orders.
 */
class DuplicateOrderIdException(
    val orderId: Long,
) : RuntimeException("order id already resting: $orderId")

/**
 * Price-time priority over an [OrderBook]: an order crosses the best opposite levels first,
 * oldest-first within a level, printing each [Trade] at the resting price (so price improvement
 * accrues to the taker); the unfilled remainder rests. Drives only the book's public operations,
 * never its internals. Not thread-safe: it holds no state of its own, so it is safe exactly where
 * the book it drives is — on the one thread that owns that book.
 */
class MatchingEngine(
    private val book: OrderBook,
) : Matcher {
    override fun submit(order: Order): List<Trade> {
        // Checked before a single fill is printed, so a rejected submit leaves the book untouched
        // rather than half-matched against liquidity it was never entitled to trade with.
        if (book.contains(order.id)) throw DuplicateOrderIdException(order.id)

        val trades = mutableListOf<Trade>()
        var remaining = order.size
        val opposite = order.side.opposite()

        while (remaining > 0) {
            // O(log P) peek at the top of book — no full-side list materialised per fill iteration.
            val best = book.bestResting(opposite) ?: break
            if (!crosses(order.side, order.price, best.price)) break

            val fill = minOf(remaining, best.size)
            trades += Trade(best.price, fill, best.id, order.id, order.side)
            remaining -= fill

            if (fill == best.size) {
                book.removeOrder(best.id)
            } else {
                book.modifyOrder(best.id, best.size - fill)
            }
        }

        if (remaining > 0) {
            book.addOrder(Order(order.id, order.price, order.side, remaining))
        }
        return trades
    }

    private fun crosses(
        incomingSide: Side,
        incomingPrice: Price,
        restingPrice: Price,
    ): Boolean =
        when (incomingSide) {
            Side.BID -> incomingPrice >= restingPrice // a buy crosses asks at or below its limit
            Side.OFFER -> incomingPrice <= restingPrice // a sell crosses bids at or above its limit
        }

    private fun Side.opposite(): Side =
        when (this) {
            Side.BID -> Side.OFFER
            Side.OFFER -> Side.BID
        }
}
