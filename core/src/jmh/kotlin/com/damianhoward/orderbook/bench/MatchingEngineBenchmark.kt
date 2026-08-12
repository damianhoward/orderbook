package com.damianhoward.orderbook.bench

import com.damianhoward.orderbook.book.OrderBook
import com.damianhoward.orderbook.engine.MatchingEngine
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
 * End-to-end latency + allocation of [MatchingEngine.submit] — the path the live server runs and
 * the optimisation track targets. Measured in [Mode.SampleTime] (p50 / p90 / p99 / p99.9 / max):
 * a submit crosses several book operations and is µs-scale, the regime where JMH's sampling timer
 * is meaningful. Run with `-prof gc` (the build sets it) for allocations/op.
 *
 * Runs against a bare [OrderBook], with no session around it: this measures the data structure and
 * the engine, and the ring-buffer hand-off that
 * [com.damianhoward.orderbook.market.MarketSession] adds is measured separately in
 * [SessionHandoffBenchmark] so the two costs stay distinguishable.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class MatchingEngineBenchmark {
    @Param("10000")
    var prepopulated: Int = 0

    @Param("50")
    var priceLevels: Int = 0

    private lateinit var book: OrderBook
    private lateinit var engine: MatchingEngine
    private val nextId = AtomicLong()

    // With this deterministic fill the spread is best bid 100 / best offer 101. A BID at 101 crosses
    // the top of book; a BID at 95 rests without crossing.
    private val bestOfferPrice = Price(101L * UNIT)
    private val restingBidPrice = Price(95L * UNIT)

    @Setup(Level.Iteration)
    fun setup() {
        book = OrderBook()
        engine = MatchingEngine(book)
        nextId.set(prepopulated.toLong())
        for (i in 0 until prepopulated) {
            val side = nextSide(i.toLong())
            book.addOrder(Order(i.toLong(), priceFor(side, i.toLong(), priceLevels), side, RESTING_SIZE))
        }
    }

    /**
     * Aggressive submit: a marketable BID that fully fills one resting offer at the top of book,
     * then restores it so the book stays stationary across the measurement window. Exercises the
     * fill loop — `getOrders(OFFER)` (the full-side allocation), `removeOrder`, and a [Trade].
     */
    @Benchmark
    fun submitCrossingTopOfBook(bh: Blackhole) {
        val incoming = Order(nextId.incrementAndGet(), bestOfferPrice, Side.BID, RESTING_SIZE)
        bh.consume(engine.submit(incoming))
        book.addOrder(Order(nextId.incrementAndGet(), bestOfferPrice, Side.OFFER, RESTING_SIZE))
    }

    /**
     * Passive submit: a non-marketable BID that rests, then is cancelled — book stationary.
     * Exercises `submit`'s no-cross check (one `getOrders(OFFER)` allocation) plus add and remove.
     */
    @Benchmark
    fun submitResting(bh: Blackhole) {
        val id = nextId.incrementAndGet()
        bh.consume(engine.submit(Order(id, restingBidPrice, Side.BID, RESTING_SIZE)))
        book.removeOrder(id)
    }
}
