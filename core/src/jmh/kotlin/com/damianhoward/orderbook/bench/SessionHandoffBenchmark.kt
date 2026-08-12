package com.damianhoward.orderbook.bench

import com.damianhoward.orderbook.market.MarketSession
import com.damianhoward.orderbook.market.SeedLiquidity
import com.damianhoward.orderbook.market.SeedOrder
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.lmax.disruptor.BlockingWaitStrategy
import com.lmax.disruptor.BusySpinWaitStrategy
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * What the ring-buffer hand-off costs, contended — the regime a live session runs in, with several
 * request threads submitting into one book. [Mode.Throughput] across 8 threads, because the quantity
 * that matters here is how many submits the single writer absorbs per second, not any one caller's
 * latency; [MatchingEngineBenchmark] covers the per-submit tail without a hand-off in the way.
 *
 * The `waitStrategy` parameter is the point of the harness. `BlockingWaitStrategy` is what the
 * deployed service uses, because a process holds one session per open symbol and each owns a thread,
 * so spinning would burn a core per idle book. `BusySpinWaitStrategy` is the ceiling that choice
 * gives up, and running both keeps the size of that trade a measurement rather than an assumption.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(8)
open class SessionHandoffBenchmark {
    @Param("blocking", "busy-spin")
    var waitStrategy: String = ""

    private lateinit var session: MarketSession

    // Deep enough that the aggressive submits below never exhaust it within an iteration, so the
    // measurement stays a hand-off-plus-match cost rather than drifting into replenishment.
    private val ladder =
        (1..200).flatMap {
            listOf(
                SeedOrder(Price((100L + it) * UNIT), Side.OFFER, RESTING_SIZE),
                SeedOrder(Price((100L - it) * UNIT), Side.BID, RESTING_SIZE),
            )
        }

    private val crossingBid = Price(150L * UNIT)
    private val restingBid = Price(50L * UNIT)

    @Setup(Level.Iteration)
    fun setup() {
        session =
            MarketSession(
                seed = SeedLiquidity(ladder),
                maxRestingOrders = Int.MAX_VALUE,
                waitStrategy = if (waitStrategy == "busy-spin") BusySpinWaitStrategy() else BlockingWaitStrategy(),
            )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        session.close()
    }

    /** Marketable submit: hand-off, then a match against the resting ladder, then a snapshot. */
    @Benchmark
    fun submitCrossing(bh: Blackhole) {
        bh.consume(session.submit(Side.BID, crossingBid, RESTING_SIZE))
    }

    /** Passive submit: hand-off and a snapshot, with no fill loop — isolates the hand-off's share. */
    @Benchmark
    fun submitResting(bh: Blackhole) {
        bh.consume(session.submit(Side.BID, restingBid, RESTING_SIZE))
    }

    /** Read-only command: the cheapest thing the ring buffer carries, so the closest to its floor. */
    @Benchmark
    fun snapshot(bh: Blackhole) {
        bh.consume(session.snapshot())
    }
}
