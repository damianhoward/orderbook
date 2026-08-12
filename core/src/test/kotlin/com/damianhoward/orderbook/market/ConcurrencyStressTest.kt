package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.view.MarketSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-threaded stress tests for [MarketSession], the component that claims to be safe under
 * concurrent access — the book it owns explicitly is not.
 *
 * They assert two properties under contention: liveness (callers make progress, and the ring-buffer
 * hand-off neither deadlocks nor drops a command) and serialisation (a submit is applied as one
 * indivisible unit, so no caller observes a half-applied match). They do not prove linearizability —
 * establishing that every operation appears to take effect atomically in some sequential order is a
 * model checker's job, not these tests'.
 *
 * Throughput is the JMH benchmark's job; these are about correctness invariants.
 */
class ConcurrencyStressTest {
    private fun price(value: Int): Price = Price.of(value.toString())

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun everyConcurrentSubmitIsAppliedExactlyOnce() {
        // No seed liquidity and nothing to cross, so every submit rests and the resting total is an
        // exact count of applied commands. A dropped or double-applied hand-off shows up as a wrong
        // total rather than as a rare corruption nobody can reproduce.
        val session = MarketSession(seed = SeedLiquidity(emptyList()), maxRestingOrders = 100_000)
        val threads = 8
        val submitsPerThread = 500
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = ConcurrentLinkedQueue<Throwable>()

        session.use {
            repeat(threads) {
                executor.submit {
                    runCatching {
                        startGate.await()
                        repeat(submitsPerThread) { session.submit(Side.BID, price(50), 1) }
                    }.onFailure { errors.add(it) }
                    done.countDown()
                }
            }
            startGate.countDown()
            assertTrue(done.await(25, TimeUnit.SECONDS), "submits must finish without deadlocking")
            executor.shutdown()
            assertTrue(errors.isEmpty(), "no caller should observe an exception, but saw: $errors")

            val bids = session.snapshot().bids
            assertEquals(1, bids.size, "every order rested at one price, so one level")
            assertEquals((threads * submitsPerThread).toLong(), bids[0].size, "no submit lost or applied twice")
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun concurrentCrossingSubmitsNeverPrintMoreThanTheLiquidityTheyTook() {
        // Aggressive buys against a seeded ladder. Every fill must correspond to real resting size,
        // so no print can exceed the resting order it took — which a torn match would violate.
        val offerSize = 5L
        val ladder = (100..119).map { SeedOrder(price(it), Side.OFFER, offerSize) }
        val session = MarketSession(seed = SeedLiquidity(ladder), tapeLimit = 10_000)
        val threads = 6
        val submitsPerThread = 40
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val printed = AtomicLong()
        val errors = ConcurrentLinkedQueue<Throwable>()

        session.use {
            repeat(threads) {
                executor.submit {
                    runCatching {
                        startGate.await()
                        // Marketable across the whole ladder; any unfilled remainder rests.
                        repeat(submitsPerThread) { printed.addAndGet(session.submit(Side.BID, price(200), 3).matched.toLong()) }
                    }.onFailure { errors.add(it) }
                    done.countDown()
                }
            }
            startGate.countDown()
            assertTrue(done.await(25, TimeUnit.SECONDS), "submits must finish without deadlocking")
            executor.shutdown()
            assertTrue(errors.isEmpty(), "no caller should observe an exception, but saw: $errors")

            val tape = session.snapshot().tape
            assertEquals(printed.get(), tape.size.toLong(), "every reported fill reached the tape exactly once")
            assertTrue(tape.all { it.size in 1..offerSize }, "no print exceeds the resting order it took: $tape")
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun snapshotsTakenDuringSubmitsAreAlwaysInternallyConsistent() {
        // Readers race writers. A snapshot is built on the owning thread as part of one command, so
        // it must never show a book mid-match: levels strictly ordered best-first, cumulative depth
        // increasing, and the tape never longer than its cap.
        val tapeLimit = 30
        val session = MarketSession(tapeLimit = tapeLimit)
        val writers = 4
        val readers = 4
        val opsPerWriter = 400
        val stop = AtomicBoolean(false)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(writers + readers)
        val startGate = CountDownLatch(1)
        val writersDone = CountDownLatch(writers)
        val readersDone = CountDownLatch(readers)

        session.use {
            repeat(writers) {
                executor.submit {
                    runCatching {
                        startGate.await()
                        val rng = ThreadLocalRandom.current()
                        repeat(opsPerWriter) {
                            val side = if (rng.nextBoolean()) Side.BID else Side.OFFER
                            session.submit(side, price(rng.nextInt(90, 111)), rng.nextLong(1, 10))
                        }
                    }.onFailure { errors.add(it) }
                    writersDone.countDown()
                }
            }
            repeat(readers) {
                executor.submit {
                    runCatching {
                        startGate.await()
                        while (!stop.get()) assertConsistent(session.snapshot(), tapeLimit)
                    }.onFailure { errors.add(it) }
                    readersDone.countDown()
                }
            }

            startGate.countDown()
            assertTrue(writersDone.await(25, TimeUnit.SECONDS), "writers must finish")
            stop.set(true)
            assertTrue(readersDone.await(5, TimeUnit.SECONDS), "readers must finish once the stop flag is set")
            executor.shutdown()
            assertTrue(errors.isEmpty(), "no thread should observe an inconsistent snapshot, but saw: $errors")
        }
    }

    private fun assertConsistent(
        snapshot: MarketSnapshot,
        tapeLimit: Int,
    ) {
        assertOrdered(snapshot.bids.map { it.price to it.cumulative }, descending = true)
        assertOrdered(snapshot.asks.map { it.price to it.cumulative }, descending = false)
        assertTrue(snapshot.tape.size <= tapeLimit, "tape must stay bounded, saw ${snapshot.tape.size}")
    }

    private fun assertOrdered(
        levels: List<Pair<Price, Long>>,
        descending: Boolean,
    ) {
        levels.zipWithNext { (leftPrice, leftDepth), (rightPrice, rightDepth) ->
            val ordered = if (descending) leftPrice > rightPrice else leftPrice < rightPrice
            assertTrue(ordered, "levels must be strictly ordered best-first, saw $leftPrice then $rightPrice")
            assertTrue(rightDepth > leftDepth, "cumulative depth must increase, saw $leftDepth then $rightDepth")
        }
    }
}
