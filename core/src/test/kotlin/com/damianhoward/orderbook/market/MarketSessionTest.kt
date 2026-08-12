package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.model.Trade
import com.damianhoward.orderbook.view.MarketSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketSessionTest {
    private val seed =
        SeedLiquidity(
            listOf(
                SeedOrder(Price.of("101.00"), Side.OFFER, 5),
                SeedOrder(Price.of("99.00"), Side.BID, 5),
            ),
        )

    private fun session() = MarketSession(seed = seed, clock = { 1_000L })

    @Test
    fun `opens with the seeded liquidity on both sides`() {
        session().use { session ->
            val snapshot = session.snapshot()
            assertEquals(listOf(Price.of("99.00")), snapshot.bids.map { it.price })
            assertEquals(listOf(Price.of("101.00")), snapshot.asks.map { it.price })
            assertTrue(snapshot.tape.isEmpty())
        }
    }

    @Test
    fun `a crossing order fills resting liquidity and prints to the tape`() {
        session().use { session ->
            val outcome = session.submit(Side.BID, Price.of("101.00"), 5)

            assertEquals(1, outcome.matched)
            assertEquals(1, outcome.snapshot.tape.size)
            val trade = outcome.snapshot.tape.first()
            assertEquals(Price.of("101.00"), trade.price)
            assertEquals(Side.BID, trade.incomingSide)
            assertEquals(1_000L, trade.timeMillis)
        }
    }

    @Test
    fun `a non-crossing order rests on the book without matching`() {
        session().use { session ->
            val outcome = session.submit(Side.BID, Price.of("98.00"), 3)

            assertEquals(0, outcome.matched)
            assertTrue(outcome.snapshot.tape.isEmpty())
            assertEquals(listOf(Price.of("99.00"), Price.of("98.00")), outcome.snapshot.bids.map { it.price })
        }
    }

    @Test
    fun `a swept side is replenished so the book never goes one-sided`() {
        session().use { session ->
            // The lone seeded ask is 5 at 101.00; buying it all empties the offer side.
            val outcome = session.submit(Side.BID, Price.of("101.00"), 5)

            assertTrue(outcome.snapshot.asks.isNotEmpty(), "offer side should be replenished from the seed")
            assertEquals(listOf(Price.of("101.00")), outcome.snapshot.asks.map { it.price })
        }
    }

    @Test
    fun `an invalid order surfaces the validation error itself, not a wrapped ExecutionException`() {
        session().use { session ->
            val thrown =
                assertThrows(IllegalArgumentException::class.java) {
                    session.submit(Side.BID, Price.of("100.00"), 0)
                }
            assertTrue(thrown.message!!.contains("size"), "expected Order's own message, got: ${thrown.message}")
        }
    }

    @Test
    fun `every fill reaches the fill listener with the tape timestamp`() {
        val fills = mutableListOf<Pair<Trade, Long>>()
        val listener = FillListener { trade, ts -> fills.add(trade to ts) }
        MarketSession(seed = seed, clock = { 1_000L }, fills = listener).use { session ->
            session.submit(Side.BID, Price.of("101.00"), 5)
            session.submit(Side.BID, Price.of("98.00"), 3)
        }

        val (trade, ts) = fills.single()
        assertEquals(Price.of("101.00"), trade.price)
        assertEquals(5L, trade.size)
        assertEquals(Side.BID, trade.incomingSide)
        assertEquals(1_000L, ts)
    }

    @Test
    fun `accepted submits reach the command listener in order, rejected ones never do`() {
        val log = mutableListOf<SubmitCommand>()
        MarketSession(seed = seed, clock = { 1_000L }, commands = { log.add(it) }).use { session ->
            session.submit(Side.BID, Price.of("101.00"), 5)
            assertThrows(IllegalArgumentException::class.java) {
                session.submit(Side.BID, Price.of("100.00"), 0)
            }
            session.submit(Side.OFFER, Price.of("102.00"), 3)
        }

        assertEquals(
            listOf(
                SubmitCommand(Side.BID, Price.of("101.00"), 5, 1_000L),
                SubmitCommand(Side.OFFER, Price.of("102.00"), 3, 1_000L),
            ),
            log,
        )
    }

    @Test
    fun `depth snapshots arrive for the seeded book and after every accepted submit`() {
        val depths = mutableListOf<MarketSnapshot>()
        MarketSession(seed = seed, clock = { 1_000L }, depth = { depths.add(it) }).use { session ->
            val outcome = session.submit(Side.BID, Price.of("98.00"), 3)

            assertEquals(2, depths.size, "one for the seeded book, one for the submit")
            assertEquals(listOf(Price.of("99.00")), depths.first().bids.map { it.price })
            assertEquals(outcome.snapshot, depths.last())
        }
    }

    @Test
    fun `the tape is bounded by the configured limit`() {
        MarketSession(seed = seed, clock = { 1_000L }, tapeLimit = 2).use { session ->
            repeat(4) { session.submit(Side.BID, Price.of("101.00"), 5) }
            assertEquals(2, session.snapshot().tape.size)
        }
    }

    @Test
    fun `resting orders are capped so the book cannot grow without bound`() {
        // Seed rests 2 (one per side); a cap of 4 leaves room for exactly two more resting limits.
        MarketSession(seed = seed, clock = { 1_000L }, maxRestingOrders = 4).use { session ->
            session.submit(Side.BID, Price.of("98.00"), 1)
            session.submit(Side.BID, Price.of("97.00"), 1)

            val rejected =
                assertThrows(BookAtCapacityException::class.java) {
                    session.submit(Side.BID, Price.of("96.00"), 1)
                }
            assertEquals(4, rejected.capacity)
            // The rejected order never rested: the deepest bid is still the last one accepted.
            assertEquals(
                Price.of("97.00"),
                session
                    .snapshot()
                    .bids
                    .last()
                    .price,
            )
        }
    }

    @Test
    fun `a rejected submit at capacity leaves the book and tape untouched`() {
        MarketSession(seed = seed, clock = { 1_000L }, maxRestingOrders = 2).use { session ->
            // Already at the cap from the seed alone: even a marketable order is turned away, and
            // nothing is mutated — no fill prints and the resting book is unchanged.
            assertThrows(BookAtCapacityException::class.java) { session.submit(Side.BID, Price.of("101.00"), 5) }
            val snapshot = session.snapshot()
            assertTrue(snapshot.tape.isEmpty(), "a rejected submit must not print a fill")
            assertEquals(listOf(Price.of("101.00")), snapshot.asks.map { it.price }, "the resting offer is untouched")
        }
    }
}
