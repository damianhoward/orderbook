package com.damianhoward.orderbook.bench

import com.damianhoward.orderbook.book.OrderBook
import com.damianhoward.orderbook.model.Order
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The raw book operations, beneath the engine and the session.
 *
 * The README published a table of these for a long time with no benchmark behind it — the class
 * that produced them had been deleted, so the numbers could not be reproduced, and two of them
 * stopped being true when the per-price queue became a linked level. A measurement nobody can
 * re-run is a claim, not evidence, so this exists to make the table checkable again.
 *
 * [Mode.AverageTime] rather than [Mode.SampleTime]: these are tens of nanoseconds, the regime where
 * `nanoTime` overhead of roughly 25 ns would swamp what is being measured. The end-to-end submit
 * path is µs-scale and stays sampled, in [MatchingEngineBenchmark].
 *
 * Every method leaves the book as it found it, so the measurement window sees a stationary book
 * rather than an average over a growing one.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class OrderBookOpsBenchmark {
    @Param("10000")
    var prepopulated: Int = 0

    @Param("50")
    var priceLevels: Int = 0

    private lateinit var book: OrderBook
    private val nextId = AtomicLong()

    /** A populated level, so a per-level cost is measured against real occupancy rather than one order. */
    private val busyBidPrice = Price(100L * UNIT)

    @Setup(Level.Iteration)
    fun setup() {
        book = OrderBook()
        nextId.set(prepopulated.toLong())
        for (i in 0 until prepopulated) {
            val side = nextSide(i.toLong())
            book.addOrder(Order(i.toLong(), priceFor(side, i.toLong(), priceLevels), side, RESTING_SIZE))
        }
    }

    @Benchmark
    fun getPriceBestBid(bh: Blackhole) = bh.consume(book.getPrice(Side.BID, 1))

    @Benchmark
    fun getPriceBestOffer(bh: Blackhole) = bh.consume(book.getPrice(Side.OFFER, 1))

    /**
     * Depth at a level five deep. This is the operation the maintained total changed: it used to
     * sum the orders resting at that price on every call, and the dashboard asks for depth on every
     * book change, across every level.
     */
    @Benchmark
    fun getTotalSizeAtLevelFive(bh: Blackhole) = bh.consume(book.getTotalSize(Side.BID, 5))

    /** A size change on a resting order, which keeps its queue position and moves its level's total. */
    @Benchmark
    fun modifyRestingOrder(bh: Blackhole) {
        bh.consume(book.modifyOrder(1L, MODIFIED_SIZE))
        bh.consume(book.modifyOrder(1L, RESTING_SIZE))
    }

    /**
     * Add then cancel at a populated price. The pair is measured together because a standalone add
     * grows the book unboundedly inside the window and averages over every size it passes through.
     *
     * The cancel is what the linked level changed. The added order goes to the tail of a level
     * holding roughly `prepopulated / priceLevels` orders, so the previous `ArrayDeque.removeIf`
     * scanned all of them to find it.
     */
    @Benchmark
    fun addThenRemoveAtABusyPrice(bh: Blackhole) {
        val id = nextId.incrementAndGet()
        book.addOrder(Order(id, busyBidPrice, Side.BID, RESTING_SIZE))
        bh.consume(book.removeOrder(id))
    }

    private companion object {
        const val MODIFIED_SIZE = 50L
    }
}
