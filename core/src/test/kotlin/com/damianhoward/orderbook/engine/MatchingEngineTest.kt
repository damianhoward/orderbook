package com.damianhoward.orderbook.engine

import com.damianhoward.orderbook.book.OrderBook
import com.damianhoward.orderbook.model.Owner
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.model.TimeInForce
import com.damianhoward.orderbook.model.order
import com.damianhoward.orderbook.model.trade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MatchingEngineTest {
    private val book = OrderBook()
    private val engine = MatchingEngine(book)

    private fun price(value: String): Price = Price.of(value)

    companion object {
        /**
         * A fixed instant. Only the expiry tests care what it is; every other test needs a clock
         * that does not move, so nothing here can pass or fail on when it happened to run.
         */
        private const val NOW = 1_000_000L
    }

    @Test
    fun restsWhenBookEmpty() {
        val trades = engine.submit(order(1L, price("100"), Side.BID, 5L), NOW)

        assertTrue(trades.isEmpty())
        assertEquals(listOf(1L), book.getOrders(Side.BID).map { it.id })
        assertEquals(5L, book.getTotalSize(Side.BID, 1))
    }

    @Test
    fun restsWhenNoCross() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(order(2L, price("100"), Side.BID, 4L), NOW)

        assertTrue(trades.isEmpty())
        assertEquals(price("100"), book.getPrice(Side.BID, 1))
        assertEquals(5L, book.getTotalSize(Side.OFFER, 1)) // resting ask untouched
    }

    @Test
    fun fullyFillsSingleRestingOrderAndRemovesIt() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(order(2L, price("101"), Side.BID, 5L), NOW)

        assertEquals(listOf(trade(price("101"), 5L, 1L, 2L, Side.BID)), trades)
        assertTrue(book.getOrders(Side.OFFER).isEmpty())
        assertTrue(book.getOrders(Side.BID).isEmpty()) // fully filled, nothing rests
    }

    @Test
    fun partiallyFillsRestingOrderLeavingRemainderOnBook() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 10L))

        val trades = engine.submit(order(2L, price("101"), Side.BID, 4L), NOW)

        assertEquals(listOf(trade(price("101"), 4L, 1L, 2L, Side.BID)), trades)
        assertEquals(6L, book.getTotalSize(Side.OFFER, 1)) // 10 - 4 left resting
        assertTrue(book.getOrders(Side.BID).isEmpty())
    }

    @Test
    fun incomingRemainderRestsWhenLiquidityExhausted() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(order(2L, price("101"), Side.BID, 8L), NOW)

        assertEquals(1, trades.size)
        assertTrue(book.getOrders(Side.OFFER).isEmpty())
        assertEquals(price("101"), book.getPrice(Side.BID, 1))
        assertEquals(3L, book.getTotalSize(Side.BID, 1)) // 8 - 5 rests at 101
    }

    @Test
    fun sweepsMultiplePriceLevelsBestFirst() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L))
        book.addOrder(order(2L, price("102"), Side.OFFER, 8L))

        val trades = engine.submit(order(3L, price("102"), Side.BID, 10L), NOW)

        assertEquals(
            listOf(
                trade(price("101"), 5L, 1L, 3L, Side.BID),
                trade(price("102"), 5L, 2L, 3L, Side.BID),
            ),
            trades,
        )
        assertEquals(3L, book.getTotalSize(Side.OFFER, 1)) // 8 - 5 left at 102
        assertTrue(book.getOrders(Side.BID).isEmpty())
    }

    @Test
    fun tradePrintsAtRestingPriceNotAggressorPrice() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(order(2L, price("105"), Side.BID, 5L), NOW) // aggressive bid

        assertEquals(price("101"), trades.single().price) // price improvement accrues to the taker
    }

    @Test
    fun fillsOldestFirstAtSamePrice() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L)) // older
        book.addOrder(order(2L, price("101"), Side.OFFER, 5L)) // newer

        val trades = engine.submit(order(3L, price("101"), Side.BID, 5L), NOW)

        assertEquals(1L, trades.single().makerOrderId) // oldest at the level filled first
        assertEquals(listOf(2L), book.getOrders(Side.OFFER).map { it.id })
    }

    @Test
    fun offerAggressorMatchesBestBidsFirst() {
        book.addOrder(order(1L, price("100"), Side.BID, 6L))
        book.addOrder(order(2L, price("99"), Side.BID, 10L))

        val trades = engine.submit(order(3L, price("99"), Side.OFFER, 8L), NOW)

        assertEquals(
            listOf(
                trade(price("100"), 6L, 1L, 3L, Side.OFFER),
                trade(price("99"), 2L, 2L, 3L, Side.OFFER),
            ),
            trades,
        )
        assertEquals(8L, book.getTotalSize(Side.BID, 1)) // 10 - 2 left at 99
        assertTrue(book.getOrders(Side.OFFER).isEmpty())
    }

    @Test
    fun rejectsAnIdAlreadyResting() {
        book.addOrder(order(1L, price("100"), Side.BID, 5L))

        val rejected = assertThrows<DuplicateOrderIdException> { engine.submit(order(1L, price("99"), Side.BID, 7L), NOW) }

        assertEquals(1L, rejected.orderId)
        // The resting order is untouched: same price, same size, still the only one on the side.
        assertEquals(listOf(order(1L, price("100"), Side.BID, 5L)), book.getOrders(Side.BID))
    }

    @Test
    fun rejectsADuplicateBeforePrintingAnyFill() {
        book.addOrder(order(1L, price("100"), Side.OFFER, 5L))
        book.addOrder(order(2L, price("101"), Side.OFFER, 5L))

        // Marketable against both asks, so without the guard it would fill first and only then
        // replace its own resting twin — leaving trades printed against a rejected order.
        assertThrows<DuplicateOrderIdException> { engine.submit(order(2L, price("101"), Side.BID, 10L), NOW) }

        assertEquals(listOf(1L, 2L), book.getOrders(Side.OFFER).map { it.id })
        assertEquals(5L, book.getTotalSize(Side.OFFER, 1))
    }

    @Test
    fun acceptsAnIdOnceItsOrderHasFullyFilled() {
        book.addOrder(order(1L, price("100"), Side.OFFER, 5L))
        engine.submit(order(2L, price("100"), Side.BID, 5L), NOW) // takes out order 1 entirely

        // An id identifies live liquidity, not history: once nothing rests under it, it is free.
        val trades = engine.submit(order(1L, price("99"), Side.BID, 3L), NOW)

        assertTrue(trades.isEmpty())
        assertEquals(listOf(1L), book.getOrders(Side.BID).map { it.id })
    }

    // ---- Expiry ----------------------------------------------------------------------------

    /**
     * The bug this design exists to avoid: where a sweeper is what stops an expired order trading,
     * everything between the deadline and the next sweep is still matchable. Nothing sweeps here —
     * expiry is judged at match time, against the clock the submit came in on — so a hit arriving
     * after the deadline finds nothing to trade with even though the order is still in the book.
     */
    @Test
    fun anExpiredRestingOrderCannotTradeEvenThoughNothingHasSweptIt() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L, timeInForce = TimeInForce.GoodTilTime(NOW)))

        val trades = engine.submit(order(2L, price("101"), Side.BID, 5L), NOW + 1)

        assertTrue(trades.isEmpty(), "an order past its deadline must not fill")
        assertTrue(book.getOrders(Side.OFFER).isEmpty(), "and must not be left behind either")
    }

    /** Inclusive of the instant itself: an order is expired *after* its deadline, not on it. */
    @Test
    fun anOrderStillTradesInTheMillisecondItExpires() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L, timeInForce = TimeInForce.GoodTilTime(NOW)))

        val trades = engine.submit(order(2L, price("101"), Side.BID, 5L), NOW)

        assertEquals(1, trades.size)
    }

    @Test
    fun anOrderThatArrivesAlreadyExpiredNeitherTradesNorRests() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(order(2L, price("101"), Side.BID, 5L, timeInForce = TimeInForce.GoodTilTime(NOW - 1)), NOW)

        assertTrue(trades.isEmpty())
        assertTrue(book.getOrders(Side.BID).isEmpty(), "it must not rest")
        assertEquals(5L, book.getTotalSize(Side.OFFER, 1), "and must not have touched the book")
    }

    // ---- Time in force ---------------------------------------------------------------------

    @Test
    fun immediateOrCancelFillsWhatItCanAndDiscardsTheRest() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(order(2L, price("101"), Side.BID, 8L, timeInForce = TimeInForce.ImmediateOrCancel), NOW)

        assertEquals(5L, trades.single().size)
        assertTrue(book.getOrders(Side.BID).isEmpty(), "the unfilled 3 must not rest")
    }

    @Test
    fun fillOrKillLeavesTheBookUntouchedWhenItCannotFillCompletely() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(order(2L, price("101"), Side.BID, 8L, timeInForce = TimeInForce.FillOrKill), NOW)

        assertTrue(trades.isEmpty(), "all or nothing")
        assertEquals(5L, book.getTotalSize(Side.OFFER, 1), "the liquidity it did not take is still there")
        assertTrue(book.getOrders(Side.BID).isEmpty())
    }

    @Test
    fun fillOrKillFillsAcrossLevelsWhenTheWholeSizeIsAvailable() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L))
        book.addOrder(order(2L, price("102"), Side.OFFER, 5L))

        val trades = engine.submit(order(3L, price("102"), Side.BID, 8L, timeInForce = TimeInForce.FillOrKill), NOW)

        assertEquals(8L, trades.sumOf { it.size })
        assertTrue(book.getOrders(Side.BID).isEmpty())
    }

    /** Expired liquidity is not available to an all-or-nothing order, so the survey must not count it. */
    @Test
    fun fillOrKillDoesNotCountExpiredLiquidityAsAvailable() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L, timeInForce = TimeInForce.GoodTilTime(NOW - 1)))
        book.addOrder(order(2L, price("101"), Side.OFFER, 3L))

        val trades = engine.submit(order(3L, price("101"), Side.BID, 8L, timeInForce = TimeInForce.FillOrKill), NOW)

        assertTrue(trades.isEmpty(), "only 3 was really available")
        assertEquals(3L, book.getTotalSize(Side.OFFER, 1), "the live order survives; the expired one does not")
    }

    // ---- Self-match prevention ---------------------------------------------------------------

    @Test
    fun anOrderNeverTradesWithItsOwnOwnersLiquidity() {
        val party = Owner("acme")
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L, owner = party))

        val trades = engine.submit(order(2L, price("101"), Side.BID, 5L, owner = party), NOW)

        assertTrue(trades.isEmpty(), "a wash trade must not print")
        assertTrue(book.getOrders(Side.OFFER).isEmpty(), "the aggressor's own stale quote is cancelled")
        // It filled nothing and is good-til-cancelled, so it rests — the party has replaced an
        // offer with a bid, which is what they asked for.
        assertEquals(listOf(2L), book.getOrders(Side.BID).map { it.id })
    }

    @Test
    fun selfMatchCancellationDoesNotStopTheOrderFillingAgainstSomeoneElse() {
        val party = Owner("acme")
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L, owner = party)) // own, and better priced
        book.addOrder(order(2L, price("102"), Side.OFFER, 5L, owner = Owner("other")))

        val trades = engine.submit(order(3L, price("102"), Side.BID, 5L, owner = party), NOW)

        assertEquals(price("102"), trades.single().price, "it walks past its own and fills the next")
        assertEquals(Owner("other"), trades.single().maker)
    }

    @Test
    fun fillOrKillDoesNotCountTheOwnersOwnRestingSizeAsAvailable() {
        val party = Owner("acme")
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L, owner = party))
        book.addOrder(order(2L, price("101"), Side.OFFER, 3L, owner = Owner("other")))

        val trades = engine.submit(order(3L, price("101"), Side.BID, 8L, owner = party, timeInForce = TimeInForce.FillOrKill), NOW)

        assertTrue(trades.isEmpty(), "only 3 was available to this party, not 8")
    }

    // ---- Both parties ------------------------------------------------------------------------

    @Test
    fun aFillNamesBothPartiesAndWhichOfThemBought() {
        book.addOrder(order(1L, price("101"), Side.OFFER, 5L, owner = Owner("seller")))

        val fill = engine.submit(order(2L, price("101"), Side.BID, 5L, owner = Owner("buyer")), NOW).single()

        assertEquals(Owner("seller"), fill.maker)
        assertEquals(Owner("buyer"), fill.taker)
        assertEquals(Owner("buyer"), fill.buyer, "the taker lifted an offer, so the taker bought")
        assertEquals(Owner("seller"), fill.seller)
    }

    @Test
    fun buyerAndSellerFollowTheTakersSideNotItsOrderOfArrival() {
        book.addOrder(order(1L, price("101"), Side.BID, 5L, owner = Owner("buyer")))

        val fill = engine.submit(order(2L, price("101"), Side.OFFER, 5L, owner = Owner("seller")), NOW).single()

        assertEquals(Owner("buyer"), fill.buyer, "the resting bid is the buyer even though it was the maker")
        assertEquals(Owner("seller"), fill.seller)
    }
}
