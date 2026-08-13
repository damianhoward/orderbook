package com.damianhoward.orderbook.book

import com.damianhoward.orderbook.model.Order
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
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
    /**
     * One resting order's place in its level's queue. The links live here rather than on [Order]
     * because `Order` is published, and `snapshot()` hands out detached copies — a copy carrying
     * queue links would be a live pointer into the book escaping through a value object.
     */
    private class Node(
        val order: Order,
    ) {
        var prev: Node? = null
        var next: Node? = null
    }

    /**
     * The resting orders at one price, in arrival order, with their sizes already summed.
     *
     * A doubly-linked list rather than a queue: `addLast` is time priority, the matcher takes the
     * head, and — the reason for the change — an order anywhere in the queue unlinks in O(1). The
     * previous `ArrayDeque` had to scan the level to find the order to drop, so cancelling made
     * the whole operation O(log P + N_p) in the number of orders resting at that price.
     *
     * [totalSize] is maintained on every mutation instead of being summed on demand, for the same
     * reason one level up: depth is rendered per book change, over every level, and a per-call sum
     * makes that quadratic in the book's shape. Every path that changes a resting size has to move
     * it — [modifyOrder] especially, which mutates the order in place precisely so it keeps its
     * queue position, and would otherwise leave the total describing a size no longer there.
     */
    private class Level {
        var head: Node? = null
        var tail: Node? = null
        var totalSize: Long = 0

        fun addLast(node: Node) {
            val previousTail = tail
            node.prev = previousTail
            node.next = null
            if (previousTail == null) head = node else previousTail.next = node
            tail = node
            totalSize += node.order.size
        }

        fun unlink(node: Node) {
            val before = node.prev
            val after = node.next
            if (before == null) head = after else before.next = after
            if (after == null) tail = before else after.prev = before
            node.prev = null
            node.next = null
            totalSize -= node.order.size
        }

        val isEmpty: Boolean get() = head == null

        inline fun forEach(action: (Order) -> Unit) {
            var node = head
            while (node != null) {
                action(node.order)
                node = node.next
            }
        }
    }

    private val buyOrders: NavigableMap<Price, Level> = TreeMap(Comparator.reverseOrder())
    private val sellOrders: NavigableMap<Price, Level> = TreeMap()
    private val ordersMap: MutableMap<Long, Node> = HashMap()

    /** Resting orders across both sides. O(1) — the id map holds exactly the live orders. */
    val size: Int get() = ordersMap.size

    /**
     * Adds the order. This is the storage primitive and it does not police identity: a duplicate
     * `id` replaces the resting order, which silently cancels live liquidity. Callers admitting
     * client-supplied ids must reject a duplicate first — [contains] is the check, and
     * [com.damianhoward.orderbook.engine.MatchingEngine] does it for everything it submits.
     */
    fun addOrder(order: Order) {
        ordersMap[order.id]?.let { removeNode(it) }
        val node = Node(order)
        ordersForSide(order.side).computeIfAbsent(order.price) { Level() }.addLast(node)
        ordersMap[order.id] = node
    }

    fun modifyOrder(
        orderId: Long,
        size: Long,
    ): Boolean {
        // The order is mutated in place so it keeps its queue position (time priority), which is
        // the whole point — but its level's running total was computed from the old size, so the
        // delta has to be applied there too. Miss this and depth silently reports a size the book
        // does not hold, with nothing to catch it: the order itself is correct. O(1).
        val node = ordersMap[orderId] ?: return false
        // Not `?: return false`. A resting order always has a level; if it does not, the two
        // structures have drifted, and reporting that as "no such order" would hide the defect
        // behind a legitimate answer the caller already handles.
        val level = checkNotNull(levelOf(node.order)) { "order $orderId rests at no price level" }
        val previousSize = node.order.size
        node.order.size = size
        level.totalSize += size - previousSize
        return true
    }

    /** True while an order with this id is resting. O(1) — the guard a caller needs before [addOrder]. */
    fun contains(orderId: Long): Boolean = ordersMap.containsKey(orderId)

    /** Cancels the order. O(log P): the level lookup, then an O(1) unlink. */
    fun removeOrder(orderId: Long): Boolean {
        val removed = ordersMap.remove(orderId) ?: return false
        removeNode(removed)
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
    fun getOrders(side: Side): List<Order> =
        buildList {
            for (level in ordersForSide(side).values) {
                level.forEach { add(it.snapshot()) }
            }
        }

    /**
     * The next order to fill on `side` — best price, oldest at that price — or null if the side is
     * empty. A detached snapshot. O(log P): lets the matcher peek the top of book without
     * materialising the whole side (which [getOrders] would).
     */
    fun bestResting(side: Side): Order? =
        ordersForSide(side)
            .firstEntry()
            ?.value
            ?.head
            ?.order
            ?.snapshot()

    private fun requireValidLevel(level: Int) {
        require(level > 0) { "level must be positive, got $level" }
    }

    private fun ordersForSide(side: Side): NavigableMap<Price, Level> =
        when (side) {
            Side.BID -> buyOrders
            Side.OFFER -> sellOrders
        }

    private fun levelOf(order: Order): Level? = ordersForSide(order.side)[order.price]

    private fun removeNode(node: Node) {
        val orders = ordersForSide(node.order.side)
        val level = orders[node.order.price] ?: return
        level.unlink(node)
        if (level.isEmpty) {
            orders.remove(node.order.price)
        }
    }

    private fun getPrice(
        orders: NavigableMap<Price, Level>,
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
        orders: NavigableMap<Price, Level>,
        level: Int,
    ): Long {
        if (level > orders.size) return 0
        val orderItr = orders.values.iterator()
        for (i in 0 until level - 1) {
            orderItr.next()
        }
        return orderItr.next().totalSize
    }
}
