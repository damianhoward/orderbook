package com.damianhoward.orderbook.book

import com.damianhoward.orderbook.model.Order
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import java.util.ArrayDeque
import java.util.Comparator
import java.util.NavigableMap
import java.util.TreeMap

/**
 * The order book: price levels, the resting orders at each, and the operations over them. No
 * concurrency control of its own — one thread owns an instance and runs every read and write on
 * it. [com.damianhoward.orderbook.market.MarketSession] is what provides that thread.
 *
 * Not an interface. There is one book, and a seam with a single implementation buys nothing but
 * indirection; [com.damianhoward.orderbook.engine.Matcher] is where a genuine strategy seam
 * exists.
 */
class OrderBook {
    // Per-price queues are ArrayDeques: contiguous storage (cache-friendly, no node-per-element
    // allocation), addLast = arrival order = time priority, and the matcher only ever takes the head.
    private val buyOrders: NavigableMap<Price, ArrayDeque<Order>> = TreeMap(Comparator.reverseOrder())
    private val sellOrders: NavigableMap<Price, ArrayDeque<Order>> = TreeMap()
    private val ordersMap: MutableMap<Long, Order> = HashMap()

    /** Resting orders across both sides. O(1) — the id map holds exactly the live orders. */
    val size: Int get() = ordersMap.size

    /**
     * Adds the order. This is the storage primitive and it does not police identity: a duplicate
     * `id` replaces the resting order, which silently cancels live liquidity. Callers admitting
     * client-supplied ids must reject a duplicate first — [contains] is the check, and
     * [com.damianhoward.orderbook.engine.MatchingEngine] does it for everything it submits.
     */
    fun addOrder(order: Order) {
        val orders = ordersForSide(order.side)
        ordersMap[order.id]?.let { removeOrderFromBook(it) }
        orders.computeIfAbsent(order.price) { ArrayDeque() }.addLast(order)
        ordersMap[order.id] = order
    }

    fun modifyOrder(
        orderId: Long,
        size: Long,
    ): Boolean {
        // ordersMap and the price queue hold the *same* Order instance, so mutating its remaining
        // size in place updates both and keeps it at its queue position (time priority). O(1).
        val order = ordersMap[orderId] ?: return false
        order.size = size
        return true
    }

    /** True while an order with this id is resting. O(1) — the guard a caller needs before [addOrder]. */
    fun contains(orderId: Long): Boolean = ordersMap.containsKey(orderId)

    fun removeOrder(orderId: Long): Boolean {
        val removed = ordersMap.remove(orderId) ?: return false
        removeOrderFromBook(removed)
        return true
    }

    /** Price at `level` (1 = best) on `side`, or null if that level doesn't exist. `level <= 0` throws. */
    fun getPrice(
        side: Side,
        level: Int,
    ): Price? {
        requireValidLevel(level)
        return getPrice(ordersForSide(side), level)
    }

    /** Summed size at `level` (1 = best) on `side`, or 0 if that level doesn't exist. `level <= 0` throws. */
    fun getTotalSize(
        side: Side,
        level: Int,
    ): Long {
        requireValidLevel(level)
        return getTotalSize(ordersForSide(side), level)
    }

    /** Resting orders on `side`, best price first then time order. Each is a detached snapshot. */
    fun getOrders(side: Side): List<Order> = ordersForSide(side).values.flatMap { level -> level.map { it.snapshot() } }

    /**
     * The next order to fill on `side` — best price, oldest at that price — or null if the side is
     * empty. A detached snapshot. O(log P): lets the matcher peek the top of book without
     * materialising the whole side (which [getOrders] would).
     */
    fun bestResting(side: Side): Order? =
        ordersForSide(side)
            .firstEntry()
            ?.value
            ?.peekFirst()
            ?.snapshot()

    private fun requireValidLevel(level: Int) {
        require(level > 0) { "level must be positive, got $level" }
    }

    private fun ordersForSide(side: Side): NavigableMap<Price, ArrayDeque<Order>> =
        when (side) {
            Side.BID -> buyOrders
            Side.OFFER -> sellOrders
        }

    private fun removeOrderFromBook(order: Order) {
        val orders = ordersForSide(order.side)
        val ordersAtPrice = orders[order.price] ?: return
        ordersAtPrice.removeIf { it.id == order.id }
        if (ordersAtPrice.isEmpty()) {
            orders.remove(order.price)
        }
    }

    private fun getPrice(
        orders: NavigableMap<Price, ArrayDeque<Order>>,
        level: Int,
    ): Price? {
        if (level > orders.size) return null
        if (level == 1) return orders.firstKey()
        val orderItr = orders.keys.iterator()
        for (i in 0 until level - 1) {
            orderItr.next()
        }
        return orderItr.next()
    }

    private fun getTotalSize(
        orders: NavigableMap<Price, ArrayDeque<Order>>,
        level: Int,
    ): Long {
        if (level > orders.size) return 0
        val orderItr = orders.values.iterator()
        for (i in 0 until level - 1) {
            orderItr.next()
        }
        return orderItr.next().sumOf { it.size }
    }
}
