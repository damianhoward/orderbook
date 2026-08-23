package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.model.Owner
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.model.TRADER
import com.damianhoward.orderbook.model.TimeInForce
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
                SeedOrder(Price.of("101.00"), Side.OFFER, 5, Owner("mm-seed")),
                SeedOrder(Price.of("99.00"), Side.BID, 5, Owner("mm-seed")),
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
            val outcome = session.submit(Side.BID, Price.of("101.00"), 5, TRADER)

            assertEquals(1, outcome.matched)
            assertEquals(1, outcome.snapshot.tape.size)
            val trade = outcome.snapshot.tape.first()
            assertEquals(Price.of("101.00"), trade.price)
            assertEquals(Side.BID, trade.takerSide)
            assertEquals(1_000L, trade.timeMillis)
        }
    }

    @Test
    fun `a non-crossing order rests on the book without matching`() {
        session().use { session ->
            val outcome = session.submit(Side.BID, Price.of("98.00"), 3, TRADER)

            assertEquals(0, outcome.matched)
            assertTrue(outcome.snapshot.tape.isEmpty())
            assertEquals(listOf(Price.of("99.00"), Price.of("98.00")), outcome.snapshot.bids.map { it.price })
        }
    }

    @Test
    fun `a swept side is replenished so the book never goes one-sided`() {
        session().use { session ->
            // The lone seeded ask is 5 at 101.00; buying it all empties the offer side.
            val outcome = session.submit(Side.BID, Price.of("101.00"), 5, TRADER)

            assertTrue(outcome.snapshot.asks.isNotEmpty(), "offer side should be replenished from the seed")
            assertEquals(listOf(Price.of("101.00")), outcome.snapshot.asks.map { it.price })
        }
    }

    @Test
    fun `an invalid order surfaces the validation error itself, not a wrapped ExecutionException`() {
        session().use { session ->
            val thrown =
                assertThrows(IllegalArgumentException::class.java) {
                    session.submit(Side.BID, Price.of("100.00"), 0, TRADER)
                }
            assertTrue(thrown.message!!.contains("size"), "expected Order's own message, got: ${thrown.message}")
        }
    }

    @Test
    fun `every fill reaches the fill listener with the tape timestamp`() {
        val fills = mutableListOf<Pair<Trade, Long>>()
        val listener = FillListener { trade, ts -> fills.add(trade to ts) }
        MarketSession(seed = seed, clock = { 1_000L }, fills = listener).use { session ->
            session.submit(Side.BID, Price.of("101.00"), 5, TRADER)
            session.submit(Side.BID, Price.of("98.00"), 3, TRADER)
        }

        val (trade, ts) = fills.single()
        assertEquals(Price.of("101.00"), trade.price)
        assertEquals(5L, trade.size)
        assertEquals(Side.BID, trade.takerSide)
        assertEquals(1_000L, ts)
    }

    @Test
    fun `accepted submits reach the command listener in order, rejected ones never do`() {
        val log = mutableListOf<SubmitCommand>()
        MarketSession(seed = seed, clock = { 1_000L }, commands = { log.add(it) }).use { session ->
            session.submit(Side.BID, Price.of("101.00"), 5, TRADER)
            assertThrows(IllegalArgumentException::class.java) {
                session.submit(Side.BID, Price.of("100.00"), 0, TRADER)
            }
            session.submit(Side.OFFER, Price.of("102.00"), 3, TRADER)
        }

        assertEquals(
            listOf(
                SubmitCommand(Side.BID, Price.of("101.00"), 5, 1_000L, TRADER),
                SubmitCommand(Side.OFFER, Price.of("102.00"), 3, 1_000L, TRADER),
            ),
            log,
        )
    }

    @Test
    fun `depth snapshots arrive for the seeded book and after every accepted submit`() {
        val depths = mutableListOf<MarketSnapshot>()
        MarketSession(seed = seed, clock = { 1_000L }, depth = { depths.add(it) }).use { session ->
            val outcome = session.submit(Side.BID, Price.of("98.00"), 3, TRADER)

            assertEquals(2, depths.size, "one for the seeded book, one for the submit")
            assertEquals(listOf(Price.of("99.00")), depths.first().bids.map { it.price })
            assertEquals(outcome.snapshot, depths.last())
        }
    }

    @Test
    fun `the tape is bounded by the configured limit`() {
        MarketSession(seed = seed, clock = { 1_000L }, tapeLimit = 2).use { session ->
            repeat(4) { session.submit(Side.BID, Price.of("101.00"), 5, TRADER) }
            assertEquals(2, session.snapshot().tape.size)
        }
    }

    @Test
    fun `resting orders are capped so the book cannot grow without bound`() {
        // Seed rests 2 (one per side); a cap of 4 leaves room for exactly two more resting limits.
        MarketSession(seed = seed, clock = { 1_000L }, maxRestingOrders = 4).use { session ->
            session.submit(Side.BID, Price.of("98.00"), 1, TRADER)
            session.submit(Side.BID, Price.of("97.00"), 1, TRADER)

            val rejected =
                assertThrows(BookAtCapacityException::class.java) {
                    session.submit(Side.BID, Price.of("96.00"), 1, TRADER)
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
            assertThrows(BookAtCapacityException::class.java) { session.submit(Side.BID, Price.of("101.00"), 5, TRADER) }
            val snapshot = session.snapshot()
            assertTrue(snapshot.tape.isEmpty(), "a rejected submit must not print a fill")
            assertEquals(listOf(Price.of("101.00")), snapshot.asks.map { it.price }, "the resting offer is untouched")
        }
    }

    @Test
    fun `submit returns the id the book assigned, and cancel takes that order back off`() {
        session().use { session ->
            val outcome = session.submit(Side.BID, Price.of("98.00"), 4, TRADER)

            assertTrue(outcome.trades.isEmpty(), "below the best offer, so it rests")
            assertTrue(session.snapshot().bids.any { it.price == Price.of("98.00") })

            assertTrue(session.cancel(outcome.orderId), "the id it just handed back must be cancellable")
            assertTrue(session.snapshot().bids.none { it.price == Price.of("98.00") })
            assertTrue(!session.cancel(outcome.orderId), "cancelling it twice is false, not an error")
        }
    }

    /**
     * Nothing here relies on the sweep for correctness — a submit discards expired liquidity before
     * it matches. What the sweep is for is the book telling the truth when nothing is trading, so
     * this drives the clock past a quote's deadline and asserts depth changed without a submit.
     */
    @Test
    fun `sweeping expired quotes removes them and publishes the new depth`() {
        var now = 1_000L
        val published = mutableListOf<MarketSnapshot>()
        MarketSession(seed = seed, clock = { now }, depth = { published += it }).use { session ->
            session.submit(Side.BID, Price.of("100.00"), 3, TRADER, TimeInForce.GoodTilTime(2_000L))
            assertTrue(session.snapshot().bids.any { it.price == Price.of("100.00") })

            now = 1_500L
            assertEquals(0, session.sweepExpired(), "not past its deadline yet")

            now = 2_001L
            val before = published.size
            assertEquals(1, session.sweepExpired(), "one quote lapsed")
            assertTrue(session.snapshot().bids.none { it.price == Price.of("100.00") })
            assertEquals(before + 1, published.size, "a lapsed quote changes the book, so depth is published")
        }
    }

    @Test
    fun `the command log records who submitted, so a replay can reproduce the same book`() {
        val log = mutableListOf<SubmitCommand>()
        MarketSession(seed = seed, clock = { 1_000L }, commands = { log += it }).use { session ->
            session.submit(Side.OFFER, Price.of("100.50"), 4, Owner("maker"))
            session.submit(Side.BID, Price.of("100.50"), 4, Owner("taker"))
        }

        assertEquals(listOf(Owner("maker"), Owner("taker")), log.map { it.owner })
        // Replayed under the recorded owners the two cross; under one owner they would not, which
        // is the divergence the owner is recorded to prevent.
        assertEquals(2, replay(seed, log).tape.size + 1, "the replayed book prints the same fill")
    }

    /**
     * The opening ladder belongs to market makers, not to the venue. An exchange matches orders; it
     * does not take the other side of them, and a book whose liquidity belonged to whoever ran it
     * would be describing a dealer. Several makers rather than one is the same point, and it is
     * what gives self-match prevention something to protect.
     */
    @Test
    fun `the seeded ladder is quoted by several named makers, not by the venue`() {
        val makers =
            SeedLiquidity
                .default()
                .orders
                .map { it.maker }
                .toSet()

        assertTrue(makers.size > 1, "one owner quoting the whole book is a dealer, not an exchange")
        assertTrue(makers.none { it.id.contains("house") || it.id.contains("venue") }, "the venue is not a counterparty")

        // No maker quotes both sides at the same level, which is the one arrangement that would
        // have a ladder repeatedly cancelling its own liquidity.
        SeedLiquidity.default().orders.groupBy { it.maker }.forEach { (maker, quotes) ->
            val bothSidesAtOneLevel = quotes.groupBy { it.price }.values.any { it.map { q -> q.side }.distinct().size > 1 }
            assertTrue(!bothSidesAtOneLevel, "$maker quotes both sides at one price")
        }
    }
}
