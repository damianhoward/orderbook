package com.damianhoward.orderbook.web

import com.damianhoward.orderbook.market.Market
import com.damianhoward.orderbook.market.SubmitOutcome
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.view.MarketSnapshot
import com.sun.net.httpserver.HttpExchange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionRegistryTest {
    // A fake Market that records whether it was ever asked to close, so eviction can be asserted
    // without a real MarketSession's writer thread.
    private class FakeMarket :
        Market,
        AutoCloseable {
        var closed = false

        override fun submit(
            side: Side,
            price: Price,
            size: Long,
        ): SubmitOutcome = throw UnsupportedOperationException()

        override fun snapshot(): MarketSnapshot = MarketSnapshot(0, emptyList(), emptyList(), emptyList())

        override fun close() {
            closed = true
        }
    }

    private class FakeBroadcaster : Broadcaster {
        override fun startHeartbeat(periodSeconds: Long) {}

        override fun broadcast(json: String) {}

        override fun stream(
            exchange: HttpExchange,
            initialJson: String,
        ) {}
    }

    private fun registry(maxSessions: Int = 20): Pair<SessionRegistry, MutableMap<String, FakeMarket>> {
        val markets = mutableMapOf<String, FakeMarket>()
        val registry =
            SessionRegistry(maxSessions) { symbol ->
                val market = FakeMarket().also { markets[symbol] = it }
                ManagedSession(market, FakeBroadcaster())
            }
        return registry to markets
    }

    @Test
    fun `creates one session per distinct symbol and reuses it on repeat requests`() {
        val (registry, markets) = registry()
        val first = registry.sessionFor("AAPL")
        val again = registry.sessionFor("AAPL")
        registry.sessionFor("MSFT")

        assertSame(first, again, "the same symbol must not re-create a session")
        assertEquals(2, registry.size)
        assertEquals(setOf("AAPL", "MSFT"), markets.keys)
    }

    @Test
    fun `normalizes the symbol - trims and uppercases before keying`() {
        val (registry, markets) = registry()
        val first = registry.sessionFor(" aapl ")
        val again = registry.sessionFor("AAPL")

        assertSame(first, again, "'aapl' and 'AAPL' must resolve to the same session")
        assertEquals(setOf("AAPL"), markets.keys)
    }

    @Test
    fun `an unrecognised symbol shape is rejected before any session is created`() {
        val (registry, markets) = registry()
        assertThrows(UnknownSymbolException::class.java) { registry.sessionFor("") }
        assertThrows(UnknownSymbolException::class.java) { registry.sessionFor("@@@") }
        assertThrows(UnknownSymbolException::class.java) { registry.sessionFor("TOOLONGASYMBOL") }
        assertTrue(markets.isEmpty(), "a rejected symbol must not create a session")
    }

    @Test
    fun `evicts the least-recently-used session once the cap is exceeded`() {
        val (registry, markets) = registry(maxSessions = 2)
        registry.sessionFor("AAPL")
        registry.sessionFor("MSFT")
        registry.sessionFor("JPM") // pushes the cap; AAPL was least recently used

        assertEquals(2, registry.size)
        assertTrue(markets.getValue("AAPL").closed, "the evicted session must be closed")
        assertTrue(!markets.getValue("MSFT").closed)
        assertTrue(!markets.getValue("JPM").closed)
    }

    @Test
    fun `reading a session counts as use and protects it from the next eviction`() {
        val (registry, markets) = registry(maxSessions = 2)
        registry.sessionFor("AAPL")
        registry.sessionFor("MSFT")
        registry.sessionFor("AAPL") // touch AAPL so MSFT becomes the least recently used
        registry.sessionFor("JPM") // pushes the cap; MSFT should be evicted instead of AAPL

        assertTrue(!markets.getValue("AAPL").closed, "recently-used AAPL must survive")
        assertTrue(markets.getValue("MSFT").closed, "MSFT was least recently used and should be evicted")
    }

    @Test
    fun `re-requesting an evicted symbol builds a fresh session`() {
        val (registry, _) = registry(maxSessions = 1)
        val first = registry.sessionFor("AAPL")
        registry.sessionFor("MSFT") // evicts AAPL
        val rebuilt = registry.sessionFor("AAPL")

        assertNotSame(first, rebuilt, "a re-requested evicted symbol must get a new session")
        assertTrue((first.session as FakeMarket).closed, "the original AAPL session should still be closed")
        assertTrue(!(rebuilt.session as FakeMarket).closed, "the rebuilt session must be fresh, not closed")
    }

    @Test
    fun `close shuts down every open session`() {
        val (registry, markets) = registry()
        registry.sessionFor("AAPL")
        registry.sessionFor("MSFT")
        registry.close()

        assertTrue(markets.values.all { it.closed })
    }

    @Test
    fun `a slow build for one symbol does not block another symbol's access`() {
        val building = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registry =
            SessionRegistry(maxSessions = 20) { symbol ->
                if (symbol == "SLOW") {
                    building.countDown()
                    // Stand in for a slow provider fetch: hold the build open until the test releases it.
                    release.await()
                }
                ManagedSession(FakeMarket(), FakeBroadcaster())
            }
        val slowThread = Thread { registry.sessionFor("SLOW") }.apply { start() }
        try {
            assertTrue(building.await(2, TimeUnit.SECONDS), "the slow build should have started")
            // FAST must resolve while SLOW is still building; if the factory ran under the registry
            // lock this would block until release and the timeout would fire.
            val fast =
                assertTimeoutPreemptively(Duration.ofSeconds(2)) { registry.sessionFor("FAST") }
            assertNotNull(fast)
        } finally {
            release.countDown()
            slowThread.join(2_000)
        }
    }
}
