package com.damianhoward.orderbook.book

import com.damianhoward.orderbook.model.Order
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The book's behavioural contract: level pricing and sizing, time priority, id identity, and the
 * boundaries each operation rejects. Single-threaded, matching how the book is used — concurrent
 * access is [com.damianhoward.orderbook.market.MarketSession]'s responsibility and is covered
 * by its own stress test.
 */
class OrderBookTest {
    private lateinit var orderBook: OrderBook

    private fun price(value: String): Price = Price.of(value)

    @BeforeEach
    fun setup() {
        orderBook = OrderBook()
        orderBook.addOrder(Order(1L, price("19"), Side.OFFER, 8))
        orderBook.addOrder(Order(2L, price("19"), Side.OFFER, 4))
        orderBook.addOrder(Order(5L, price("22"), Side.OFFER, 7))
        orderBook.addOrder(Order(3L, price("21"), Side.OFFER, 16))
        orderBook.addOrder(Order(4L, price("21"), Side.OFFER, 1))
        orderBook.addOrder(Order(6L, price("15"), Side.BID, 5))
        orderBook.modifyOrder(6L, 10)
        orderBook.addOrder(Order(7L, price("13"), Side.BID, 20))
        orderBook.removeOrder(7L)
        orderBook.addOrder(Order(8L, price("10"), Side.BID, 13))
        orderBook.addOrder(Order(9L, price("10"), Side.BID, 13))
    }

    @Test
    fun testGetPriceForOfferLevelOne() {
        assertEquals(price("19"), orderBook.getPrice(Side.OFFER, 1))
    }

    @Test
    fun testGetPriceForOfferLevelTwo() {
        assertEquals(price("21"), orderBook.getPrice(Side.OFFER, 2))
    }

    @Test
    fun testGetPriceForOfferLevelThree() {
        assertEquals(price("22"), orderBook.getPrice(Side.OFFER, 3))
    }

    @Test
    fun testGetPriceForOfferLevelFourIsNull() {
        assertNull(orderBook.getPrice(Side.OFFER, 4))
    }

    @Test
    fun testGetPriceForBidLevelOne() {
        assertEquals(price("15"), orderBook.getPrice(Side.BID, 1))
    }

    @Test
    fun testGetPriceForBidLevelTwo() {
        assertEquals(price("10"), orderBook.getPrice(Side.BID, 2))
    }

    @Test
    fun testGetPriceForBidLevelThreeIsNull() {
        assertNull(orderBook.getPrice(Side.BID, 3))
    }

    @Test
    fun testGetTotalSizeForOfferLevelOne() {
        assertEquals(12L, orderBook.getTotalSize(Side.OFFER, 1))
    }

    @Test
    fun testGetTotalSizeForOfferLevelTwo() {
        assertEquals(17L, orderBook.getTotalSize(Side.OFFER, 2))
    }

    @Test
    fun testGetTotalSizeForOfferLevelThree() {
        assertEquals(7L, orderBook.getTotalSize(Side.OFFER, 3))
    }

    @Test
    fun testGetTotalSizeForOfferLevelFour() {
        assertEquals(0L, orderBook.getTotalSize(Side.OFFER, 4))
    }

    @Test
    fun testGetTotalSizeForBidLevelOne() {
        assertEquals(10L, orderBook.getTotalSize(Side.BID, 1))
    }

    @Test
    fun testGetTotalSizeForBidLevelTwo() {
        assertEquals(26L, orderBook.getTotalSize(Side.BID, 2))
    }

    @Test
    fun testGetTotalSizeForBidLevelThree() {
        assertEquals(0L, orderBook.getTotalSize(Side.BID, 3))
    }

    @Test
    fun testGetOfferOrders() {
        val offers = orderBook.getOrders(Side.OFFER)
        assertEquals(5, offers.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), offers.map { it.id })
    }

    @Test
    fun testGetBidOrders() {
        val bids = orderBook.getOrders(Side.BID)
        assertEquals(3, bids.size)
        assertEquals(listOf(6L, 8L, 9L), bids.map { it.id })
    }

    @Test
    fun testModifyPreservesTimePriorityAtSamePrice() {
        orderBook.modifyOrder(1L, 12)

        val offers = orderBook.getOrders(Side.OFFER)
        assertEquals(1L, offers[0].id)
        assertEquals(12L, offers[0].size)
        assertEquals(2L, offers[1].id)
        assertEquals(16L, offers[2].size)
    }

    @Test
    fun testAddExistingIdRemovesOldOrder() {
        orderBook.addOrder(Order(1L, price("23"), Side.OFFER, 11))

        val offers = orderBook.getOrders(Side.OFFER)
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), offers.map { it.id })
        assertEquals(4L, orderBook.getTotalSize(Side.OFFER, 1))
        assertEquals(price("23"), orderBook.getPrice(Side.OFFER, 4))
        assertEquals(11L, orderBook.getTotalSize(Side.OFFER, 4))
    }

    @Test
    fun testAddExistingIdCanMoveSides() {
        orderBook.addOrder(Order(1L, price("16"), Side.BID, 11))

        assertEquals(listOf(2L, 3L, 4L, 5L), orderBook.getOrders(Side.OFFER).map { it.id })
        assertEquals(listOf(1L, 6L, 8L, 9L), orderBook.getOrders(Side.BID).map { it.id })
        assertEquals(price("16"), orderBook.getPrice(Side.BID, 1))
    }

    @Test
    fun modifyUnknownIdIsNoOp() {
        assertFalse(orderBook.modifyOrder(999L, 50))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), orderBook.getOrders(Side.OFFER).map { it.id })
        assertEquals(12L, orderBook.getTotalSize(Side.OFFER, 1))
    }

    @Test
    fun modifyKnownIdReturnsTrue() {
        assertTrue(orderBook.modifyOrder(1L, 99))
    }

    @Test
    fun removeUnknownIdIsNoOp() {
        assertFalse(orderBook.removeOrder(999L))
        assertEquals(5, orderBook.getOrders(Side.OFFER).size)
        assertEquals(3, orderBook.getOrders(Side.BID).size)
    }

    @Test
    fun removeKnownIdReturnsTrue() {
        assertTrue(orderBook.removeOrder(1L))
        assertFalse(orderBook.removeOrder(1L))
    }

    @Test
    fun bestRestingReturnsTopOfBook() {
        val bestOffer = orderBook.bestResting(Side.OFFER)
        assertEquals(1L, bestOffer?.id)
        assertEquals(price("19"), bestOffer?.price)
        assertEquals(8L, bestOffer?.size)

        val bestBid = orderBook.bestResting(Side.BID)
        assertEquals(6L, bestBid?.id)
        assertEquals(price("15"), bestBid?.price)
        assertEquals(10L, bestBid?.size)
    }

    @Test
    fun bestRestingOnEmptySideIsNull() {
        val empty = OrderBook()
        assertNull(empty.bestResting(Side.OFFER))
        assertNull(empty.bestResting(Side.BID))
    }

    @Test
    fun nonPositiveLevelThrows() {
        assertThrows(IllegalArgumentException::class.java) { orderBook.getPrice(Side.OFFER, 0) }
        assertThrows(IllegalArgumentException::class.java) { orderBook.getPrice(Side.OFFER, -1) }
        assertThrows(IllegalArgumentException::class.java) { orderBook.getTotalSize(Side.OFFER, 0) }
        assertThrows(IllegalArgumentException::class.java) { orderBook.getTotalSize(Side.OFFER, -1) }
    }

    @Test
    fun emptyBookGetOrdersReturnsEmptyList() {
        val empty = OrderBook()
        assertTrue(empty.getOrders(Side.OFFER).isEmpty())
        assertTrue(empty.getOrders(Side.BID).isEmpty())
        assertNull(empty.getPrice(Side.OFFER, 1))
        assertEquals(0L, empty.getTotalSize(Side.BID, 1))
    }

    @Test
    fun modifyOrderRejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException::class.java) { orderBook.modifyOrder(1L, 0) }
        assertThrows(IllegalArgumentException::class.java) { orderBook.modifyOrder(1L, -1) }
    }

    @Test
    fun containsReportsOnlyOrdersCurrentlyResting() {
        assertTrue(orderBook.contains(1L), "resting on the offer side")
        assertTrue(orderBook.contains(6L), "resting on the bid side")
        assertFalse(orderBook.contains(7L), "added then removed in setup")
        assertFalse(orderBook.contains(99L), "never added")
    }

    @Test
    fun containsFollowsTheOrderThroughRemoval() {
        val id = 42L
        assertFalse(orderBook.contains(id))

        orderBook.addOrder(Order(id, price("14"), Side.BID, 3))
        assertTrue(orderBook.contains(id))

        // A modify keeps the order resting; only removal releases the id.
        orderBook.modifyOrder(id, 1)
        assertTrue(orderBook.contains(id))

        orderBook.removeOrder(id)
        assertFalse(orderBook.contains(id))
    }
}
