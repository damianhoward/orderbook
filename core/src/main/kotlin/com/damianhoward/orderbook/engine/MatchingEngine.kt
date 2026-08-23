package com.damianhoward.orderbook.engine

import com.damianhoward.orderbook.book.OrderBook
import com.damianhoward.orderbook.model.Order
import com.damianhoward.orderbook.model.Owner
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.model.TimeInForce
import com.damianhoward.orderbook.model.Trade

/** A matching strategy: crosses an incoming order against resting liquidity and returns the fills. */
interface Matcher {
    /**
     * Matches [order] against the book as at [nowMillis], which is what decides whether an order
     * carrying a deadline is still tradeable. The clock is passed in rather than read here so a
     * submit and the expiry it is judged against cannot come from two different instants.
     */
    fun submit(
        order: Order,
        nowMillis: Long,
    ): List<Trade>
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
 * accrues to the taker); the unfilled remainder rests unless its time-in-force says otherwise.
 * Drives only the book's public operations, never its internals. Not thread-safe: it holds no state
 * of its own, so it is safe exactly where the book it drives is — on the one thread that owns that
 * book.
 *
 * Priority never reads an owner. A time-in-force is read only to decide whether a resting order is
 * still tradeable and what becomes of an unfilled remainder, so the queue an order joins and the
 * order it fills in are decided by price and arrival alone.
 */
class MatchingEngine(
    private val book: OrderBook,
) : Matcher {
    override fun submit(
        order: Order,
        nowMillis: Long,
    ): List<Trade> {
        // Checked before a single fill is printed, so a rejected submit leaves the book untouched
        // rather than half-matched against liquidity it was never entitled to trade with.
        if (book.contains(order.id)) throw DuplicateOrderIdException(order.id)

        // An order that arrives already past its deadline trades nothing and rests nowhere. Silent
        // rather than an exception: a deadline passing is the order working as specified, not a
        // caller error, and the submitter learns it from the empty fill list either way.
        if (order.hasExpiredAt(nowMillis)) return emptyList()

        // Expired liquidity leaves before anything reads the book, so the all-or-nothing survey
        // below and the match that follows it cannot disagree about what is available.
        dropExpired(order.side.opposite(), nowMillis)

        // All-or-nothing is decided before the first fill prints. Matching and then unwinding would
        // mean briefly having traded at prices the order was never entitled to take.
        if (order.timeInForce == TimeInForce.FillOrKill && fillableSize(order) < order.size) {
            return emptyList()
        }

        val trades = mutableListOf<Trade>()
        var remaining = order.size
        val opposite = order.side.opposite()

        while (remaining > 0) {
            // O(log P) peek at the top of book — no full-side list materialised per fill iteration.
            val best = book.bestResting(opposite) ?: break
            if (!crosses(order.side, order.price, best.price)) break

            // Self-match prevention: cancel the resting side and carry on down the book. The
            // aggressor has just stated a newer intention, which makes their own older quote at a
            // price they are now crossing stale by their own action — leaving it would let them be
            // hit a moment later at a price they had already walked through.
            if (isSelfMatch(order.owner, best.owner)) {
                book.removeOrder(best.id)
                continue
            }

            val fill = minOf(remaining, best.size)
            trades +=
                Trade(
                    price = best.price,
                    size = fill,
                    makerOrderId = best.id,
                    takerOrderId = order.id,
                    takerSide = order.side,
                    maker = best.owner,
                    taker = order.owner,
                )
            remaining -= fill

            if (fill == best.size) {
                book.removeOrder(best.id)
            } else {
                book.modifyOrder(best.id, best.size - fill)
            }
        }

        if (remaining > 0 && order.timeInForce.rests()) {
            book.addOrder(Order(order.id, order.price, order.side, remaining, order.owner, order.timeInForce))
        }
        return trades
    }

    /**
     * What [order] could fill against the book right now, capped at its own size so a deep book
     * does not cost a full walk. Only [TimeInForce.FillOrKill] needs it, and only before matching.
     */
    private fun fillableSize(order: Order): Long {
        var available = 0L
        for (resting in book.getOrders(order.side.opposite())) {
            if (!crosses(order.side, order.price, resting.price)) break
            // The aggressor's own resting size is not liquidity available to it: the match loop
            // cancels those rather than filling against them. Counting it here would let an
            // all-or-nothing order pass a survey it cannot then satisfy, which is the one outcome
            // fill-or-kill exists to make impossible.
            if (isSelfMatch(order.owner, resting.owner)) continue
            available += resting.size
            if (available >= order.size) return available
        }
        return available
    }

    /**
     * True when both sides are the same party. There is no exemption, because there is no owner
     * meaning "nobody" — so anything that has to trade against the venue's own liquidity must be a
     * different party from it, and a caller that reuses one owner for what are really several
     * participants will find them unable to trade with each other.
     */
    private fun isSelfMatch(
        incoming: Owner,
        resting: Owner,
    ): Boolean = incoming == resting

    /**
     * Removes every order on [side] whose deadline has passed. Ids are collected before anything is
     * removed: `getOrders` hands out detached snapshots, but removing while walking the side would
     * still mutate the levels the walk came from.
     */
    private fun dropExpired(
        side: Side,
        nowMillis: Long,
    ) {
        val expired = book.getOrders(side).filter { it.hasExpiredAt(nowMillis) }
        expired.forEach { book.removeOrder(it.id) }
    }

    /** True where an unfilled remainder joins the book rather than being discarded. */
    private fun TimeInForce.rests(): Boolean =
        when (this) {
            TimeInForce.GoodTilCancelled, is TimeInForce.GoodTilTime -> true
            TimeInForce.ImmediateOrCancel, TimeInForce.FillOrKill -> false
        }

    private fun crosses(
        takerSide: Side,
        incomingPrice: Price,
        restingPrice: Price,
    ): Boolean =
        when (takerSide) {
            Side.BID -> incomingPrice >= restingPrice // a buy crosses asks at or below its limit
            Side.OFFER -> incomingPrice <= restingPrice // a sell crosses bids at or above its limit
        }

    private fun Side.opposite(): Side =
        when (this) {
            Side.BID -> Side.OFFER
            Side.OFFER -> Side.BID
        }
}
