package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class MarketReplayTest {
    private val seed =
        SeedLiquidity(
            listOf(
                SeedOrder(Price.of("101.00"), Side.OFFER, 5),
                SeedOrder(Price.of("99.00"), Side.BID, 5),
            ),
        )

    @Test
    fun `replaying the recorded command log reproduces the live snapshot exactly`() {
        // A mixed run: resting orders, partial fills, and full sweeps that trigger the session's
        // internal replenishment — none of which is in the log, all of which must reproduce.
        var now = 1_000L
        val log = mutableListOf<SubmitCommand>()
        val live =
            MarketSession(seed = seed, clock = { now }, commands = { log.add(it) }).use { session ->
                val random = Random(7)
                repeat(200) {
                    now += random.nextLong(1, 50)
                    val side = if (random.nextBoolean()) Side.BID else Side.OFFER
                    val price = Price.of("${96 + random.nextInt(9)}.00")
                    session.submit(side, price, random.nextLong(1, 12))
                }
                session.snapshot()
            }

        assertEquals(200, log.size)
        assertEquals(live, replay(seed, log))
    }

    @Test
    fun `a full sweep and its replenishment reproduce from a log that records neither`() {
        var now = 1_000L
        val log = mutableListOf<SubmitCommand>()
        val live =
            MarketSession(seed = seed, clock = { now }, commands = { log.add(it) }).use { session ->
                session.submit(Side.BID, Price.of("101.00"), 5)
                now = 2_000L
                session.submit(Side.BID, Price.of("101.00"), 2)
                session.snapshot()
            }

        val replayed = replay(seed, log)
        assertEquals(live, replayed)
        assertTrue(replayed.tape.size == 2, "both fills print on the replayed tape, got ${replayed.tape}")
    }

    @Test
    fun `an empty log replays to the freshly seeded book`() {
        val replayed = replay(seed, emptyList())
        assertEquals(listOf(Price.of("99.00")), replayed.bids.map { it.price })
        assertEquals(listOf(Price.of("101.00")), replayed.asks.map { it.price })
        assertTrue(replayed.tape.isEmpty())
    }
}
