package com.damianhoward.orderbook.web

import com.damianhoward.marketdata.cache.QuoteCache
import com.damianhoward.marketdata.model.Instrument
import com.damianhoward.marketdata.model.Quote
import com.damianhoward.marketdata.source.QuoteSource
import com.damianhoward.marketdata.source.QuoteUnavailable
import com.damianhoward.orderbook.kafka.EgressMetrics
import com.damianhoward.orderbook.market.BookAtCapacityException
import com.damianhoward.orderbook.market.Market
import com.damianhoward.orderbook.market.MarketSession
import com.damianhoward.orderbook.market.SubmitOutcome
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.quote.QuoteSeed
import com.damianhoward.orderbook.view.MarketSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Loopback tests over a real [WebServer] on an ephemeral port — the transport is driven end to
 * end (routing, method enforcement, error mapping, SSE), not mocked.
 */
class WebServerTest {
    // Only "SIM" and "AAPL"/"MSFT" (used by the multi-symbol tests) resolve; anything else fails
    // the way a genuinely unknown ticker fails against the real provider.
    private val fakeQuoteSource =
        QuoteSource { symbol ->
            if (symbol !in setOf("SIM", "AAPL", "MSFT")) throw QuoteUnavailable("no such symbol: $symbol")
            Quote(
                instrument = Instrument(symbol, "$symbol Inc.", "USD", "NasdaqGS"),
                last = BigDecimal("100.00"),
                previousClose = BigDecimal("99.00"),
                dayHigh = BigDecimal("101.00"),
                dayLow = BigDecimal("98.50"),
                asOf = Instant.parse("2026-07-15T20:00:00Z"),
                marketOpen = false,
            )
        }
    private lateinit var quotes: QuoteCache
    private lateinit var registry: SessionRegistry
    private lateinit var server: WebServer
    private val client: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun start() {
        quotes = QuoteCache(fakeQuoteSource)
        // Same wiring as main(): a symbol whose quote can't be fetched is unknown, not a fallback
        // to a synthetic ladder. SSE frames come from the market's depth stream, not from the HTTP
        // handler, so the stream test below exercises the real push path.
        registry =
            SessionRegistry { symbol ->
                val cached = quotes.refresh(symbol) ?: throw UnknownSymbolException(symbol)
                val broadcaster = SseBroadcaster()
                val session = MarketSession(seed = QuoteSeed.around(cached.quote), depth = DepthBroadcast(broadcaster))
                broadcaster.startHeartbeat()
                ManagedSession(session, broadcaster)
            }
        server = WebServer(registry, quotes, WebAssets.load(), port = 0)
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop()
        registry.close()
    }

    private fun request(
        method: String,
        path: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:${server.boundPort}$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `healthz responds ok`() {
        val response = request("GET", "/healthz")
        assertEquals(200, response.statusCode())
        assertEquals("ok", response.body())
    }

    @Test
    fun `readyz is 200 when the matching engine self-check passes`() {
        val response = request("GET", "/readyz")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains(""""ready":true"""), response.body())
    }

    @Test
    fun `readyz is 503 when the self-check throws`() {
        val server =
            WebServer(
                registry,
                quotes,
                WebAssets.load(),
                port = 0,
                readiness = Readiness(selfCheck = { throw IllegalStateException("boom") }),
            )
        server.start()
        try {
            val response =
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:${server.boundPort}/readyz")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(503, response.statusCode())
            assertTrue(response.body().contains(""""ready":false"""), response.body())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `serves the static front end with content types`() {
        assertTrue(request("GET", "/").body().contains("<html"))
        assertEquals("text/css; charset=utf-8", request("GET", "/app.css").headers().firstValue("Content-Type").get())
        assertEquals("text/javascript; charset=utf-8", request("GET", "/app.js").headers().firstValue("Content-Type").get())
    }

    @Test
    fun `serves the privacy notice`() {
        val response = request("GET", "/privacy")
        assertEquals(200, response.statusCode())
        assertEquals("text/html; charset=utf-8", response.headers().firstValue("Content-Type").get())
        assertTrue(response.body().contains("Privacy"))
    }

    @Test
    fun `state returns the book as JSON`() {
        val response = request("GET", "/api/SIM/state")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"bids\":["))
        assertTrue(response.body().contains("\"asks\":["))
    }

    @Test
    fun `unknown path is a 404`() {
        assertEquals(404, request("GET", "/nope").statusCode())
    }

    @Test
    fun `HEAD answers every GET route with the GET's status and headers, minus the body`() {
        for (path in listOf("/", "/healthz", "/readyz", "/metrics", "/privacy", "/api/symbols", "/api/SIM/state", "/api/SIM/quote")) {
            val head = request("HEAD", path)
            assertEquals(request("GET", path).statusCode(), head.statusCode(), path)
            assertEquals("", head.body(), path)
        }
        assertEquals("text/html; charset=utf-8", request("HEAD", "/").headers().firstValue("Content-Type").get())
    }

    @Test
    fun `HEAD on the stream answers headers without attaching to the broadcaster`() {
        val response = request("HEAD", "/api/SIM/stream")
        assertEquals(200, response.statusCode())
        assertEquals("text/event-stream", response.headers().firstValue("Content-Type").get())
        assertEquals("", response.body())
    }

    @Test
    fun `HEAD on the order endpoint stays a 405`() {
        val response = request("HEAD", "/api/SIM/order")
        assertEquals(405, response.statusCode())
        assertEquals("POST", response.headers().firstValue("Allow").get())
    }

    @Test
    fun `a valid order submits and reports fills`() {
        val response = request("POST", "/api/SIM/order?side=BUY&price=102.00&size=3")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().startsWith("""{"matched":"""))
    }

    @Test
    fun `order submission rejects GET - state changes are POST-only`() {
        val response = request("GET", "/api/SIM/order?side=BUY&price=100&size=1")
        assertEquals(405, response.statusCode())
        assertEquals("POST", response.headers().firstValue("Allow").get())
    }

    @Test
    fun `read endpoints reject non-GET methods`() {
        assertEquals(405, request("POST", "/api/SIM/state").statusCode())
        assertEquals(405, request("DELETE", "/healthz").statusCode())
    }

    @Test
    fun `an unknown side is a 400, not a silent sell`() {
        val response = request("POST", "/api/SIM/order?side=BANANA&price=100&size=1")
        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("\"error\""))
    }

    @Test
    fun `a missing parameter is a 400`() {
        assertEquals(400, request("POST", "/api/SIM/order?side=BUY&size=1").statusCode())
        assertEquals(400, request("POST", "/api/SIM/order?side=BUY&price=100").statusCode())
    }

    @Test
    fun `a non-positive size is a 400`() {
        val response = request("POST", "/api/SIM/order?side=BUY&price=100&size=0")
        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("\"error\""))
    }

    @Test
    fun `a malformed size is a 400`() {
        assertEquals(400, request("POST", "/api/SIM/order?side=BUY&price=100&size=lots").statusCode())
    }

    @Test
    fun `an over-precise price is a 400`() {
        val response = request("POST", "/api/SIM/order?side=BUY&price=1.123456789&size=1")
        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("\"error\""))
    }

    @Test
    fun `a malformed price is a 400`() {
        assertEquals(400, request("POST", "/api/SIM/order?side=BUY&price=abc&size=1").statusCode())
    }

    @Test
    fun `order submissions past the per-client rate are a 429 with Retry-After, reads stay open`() {
        val limited =
            WebServer(
                registry,
                quotes,
                WebAssets.load(),
                port = 0,
                orderLimiter = TokenBucketRateLimiter(capacity = 2, refillPerSecond = 0.1),
            )
        limited.start()
        try {
            fun order() =
                client.send(
                    HttpRequest
                        .newBuilder(URI("http://localhost:${limited.boundPort}/api/SIM/order?side=BUY&price=100&size=1"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, order().statusCode())
            assertEquals(200, order().statusCode())
            val denied = order()
            assertEquals(429, denied.statusCode())
            assertTrue(denied.headers().firstValue("Retry-After").isPresent, "a denial must say when to come back")
            assertTrue(denied.body().contains("\"error\""))
            val read =
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:${limited.boundPort}/api/SIM/state")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, read.statusCode(), "only the write path is limited")
        } finally {
            limited.stop()
        }
    }

    @Test
    fun `the rate limit keys per forwarded client when the peer is the local proxy`() {
        val limited =
            WebServer(
                registry,
                quotes,
                WebAssets.load(),
                port = 0,
                orderLimiter = TokenBucketRateLimiter(capacity = 1, refillPerSecond = 0.1),
            )
        limited.start()
        try {
            fun order(forwardedFor: String) =
                client.send(
                    HttpRequest
                        .newBuilder(URI("http://localhost:${limited.boundPort}/api/SIM/order?side=BUY&price=100&size=1"))
                        .header("X-Forwarded-For", forwardedFor)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, order("203.0.113.5").statusCode())
            assertEquals(429, order("203.0.113.5").statusCode(), "the same forwarded client shares one bucket")
            assertEquals(200, order("203.0.113.6").statusCode(), "a different forwarded client is unaffected")
        } finally {
            limited.stop()
        }
    }

    // Rejection happens before any handler runs, so the refused connection closes with no HTTP
    // status line — the client sees a connection-level failure, which is the documented contract.
    @Test
    fun `requests beyond the thread cap are refused rather than queued`() {
        val bounded = WebServer(registry, quotes, WebAssets.load(), port = 0, maxPoolThreads = 2)
        bounded.start()
        val streams = mutableListOf<HttpURLConnection>()
        try {
            repeat(2) {
                val connection =
                    URI("http://localhost:${bounded.boundPort}/api/SIM/stream").toURL().openConnection() as HttpURLConnection
                connection.readTimeout = 5_000
                val reader = connection.inputStream.bufferedReader()
                assertTrue(dataFrame(reader).contains("\"bids\":["), "each stream should be live before saturating")
                streams.add(connection)
            }
            val request = HttpRequest.newBuilder(URI("http://localhost:${bounded.boundPort}/healthz")).GET().build()
            assertThrows(IOException::class.java) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
        } finally {
            streams.forEach { it.disconnect() }
            bounded.stop()
        }
    }

    @Test
    fun `an unexpected failure maps to a 500, not a dropped connection`() {
        val failing =
            object : Market {
                override fun submit(
                    side: Side,
                    price: Price,
                    size: Long,
                ): SubmitOutcome = throw IllegalStateException("boom")

                override fun snapshot(): MarketSnapshot = throw IllegalStateException("boom")
            }
        val failingRegistry = SessionRegistry { _ -> ManagedSession(failing, SseBroadcaster()) }
        val failingServer = WebServer(failingRegistry, quotes, WebAssets.load(), port = 0)
        failingServer.start()
        try {
            val request =
                HttpRequest
                    .newBuilder(URI("http://localhost:${failingServer.boundPort}/api/SIM/state"))
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(500, response.statusCode())
            assertTrue(response.body().contains("\"error\""))
        } finally {
            failingServer.stop()
            failingRegistry.close()
        }
    }

    @Test
    fun `metrics reports egress disabled when no egress is wired`() {
        val response = request("GET", "/metrics")
        assertEquals(200, response.statusCode())
        assertEquals("application/json", response.headers().firstValue("Content-Type").get())
        assertTrue(response.body().contains(""""enabled":false"""), response.body())
    }

    @Test
    fun `metrics publishes the egress counters when an egress is wired`() {
        val metrics =
            object : EgressMetrics {
                override val dropped = 3L
                override val lost = 1L
                override val published = 42L
                override val failed = 2L
            }
        val server = WebServer(registry, quotes, WebAssets.load(), port = 0, egressMetrics = metrics)
        server.start()
        try {
            val response =
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:${server.boundPort}/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, response.statusCode())
            val body = response.body()
            assertTrue(body.contains(""""enabled":true"""), body)
            assertTrue(body.contains(""""published":42"""), body)
            assertTrue(body.contains(""""failed":2"""), body)
            assertTrue(body.contains(""""dropped":3"""), body)
            assertTrue(body.contains(""""lost":1"""), body)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a submit against a saturated book maps to a 503`() {
        val atCapacity =
            object : Market {
                override fun submit(
                    side: Side,
                    price: Price,
                    size: Long,
                ): SubmitOutcome = throw BookAtCapacityException(1000)

                override fun snapshot(): MarketSnapshot = throw UnsupportedOperationException("not used")
            }
        val registry = SessionRegistry { _ -> ManagedSession(atCapacity, SseBroadcaster()) }
        val server = WebServer(registry, quotes, WebAssets.load(), port = 0)
        server.start()
        try {
            val request =
                HttpRequest
                    .newBuilder(URI("http://localhost:${server.boundPort}/api/SIM/order?side=BUY&price=100&size=1"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(503, response.statusCode())
            assertTrue(response.body().contains("capacity"), response.body())
        } finally {
            server.stop()
            registry.close()
        }
    }

    @Test
    fun `the SSE stream sends the initial snapshot and pushes submitted orders`() {
        val connection = URI("http://localhost:${server.boundPort}/api/SIM/stream").toURL().openConnection() as HttpURLConnection
        connection.readTimeout = 5_000
        connection.inputStream.bufferedReader().use { reader ->
            assertTrue(dataFrame(reader).contains("\"bids\":["), "initial snapshot expected on connect")

            request("POST", "/api/SIM/order?side=BUY&price=102.00&size=1")
            assertTrue(dataFrame(reader).contains("\"tape\":["), "broadcast snapshot expected after a submit")
        }
        connection.disconnect()
    }

    @Test
    fun `each symbol gets its own book - a fill on one leaves another untouched`() {
        request("POST", "/api/AAPL/order?side=BUY&price=100.50&size=2")
        request("POST", "/api/AAPL/order?side=SELL&price=100.50&size=2")

        val aapl = request("GET", "/api/AAPL/state")
        val msft = request("GET", "/api/MSFT/state")
        assertTrue(aapl.body().contains("\"tape\":[{"), "AAPL should show the fill just submitted")
        assertTrue(msft.body().contains("\"tape\":[]"), "a fresh MSFT book should have no trades yet")
    }

    @Test
    fun `an unrecognised symbol shape is a 404, not a book`() {
        assertEquals(404, request("GET", "/api/@@@/state").statusCode())
        assertEquals(404, request("GET", "/api//state").statusCode())
    }

    @Test
    fun `a well-shaped symbol no quote exists for is a 404, never a fake book`() {
        val response = request("GET", "/api/ZZZZ/state")
        assertEquals(404, response.statusCode())
        assertTrue(response.body().contains("\"error\""))
    }

    @Test
    fun `symbols lists the curated picker options`() {
        val response = request("GET", "/api/symbols")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"symbol\":\"AAPL\""))
        assertTrue(response.body().contains("\"name\":\"Apple\""))
    }

    @Test
    fun `quote reports the instrument a book was seeded from`() {
        request("GET", "/api/AAPL/state") // creates the session, fetching the fake quote
        val response = request("GET", "/api/AAPL/quote")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"symbol\":\"AAPL\""))
        assertTrue(response.body().contains("\"name\":\"AAPL Inc.\""))
        assertTrue(response.body().contains("\"last\":\"100.00\""))
    }

    @Test
    fun `quote rejects non-GET methods`() {
        assertEquals(405, request("POST", "/api/SIM/quote").statusCode())
    }

    @Test
    fun `a quote aged past the cache max-age is a 503, not a stale 200`() {
        var now = Instant.parse("2026-07-15T20:00:00Z")
        val agingQuotes = QuoteCache(fakeQuoteSource, clock = { now }, maxAge = Duration.ofMinutes(1))
        val agingRegistry =
            SessionRegistry { symbol ->
                val cached = agingQuotes.refresh(symbol) ?: throw UnknownSymbolException(symbol)
                val broadcaster = SseBroadcaster()
                ManagedSession(MarketSession(seed = QuoteSeed.around(cached.quote), depth = DepthBroadcast(broadcaster)), broadcaster)
            }
        val agingServer = WebServer(agingRegistry, agingQuotes, WebAssets.load(), port = 0)
        agingServer.start()
        try {
            fun quote() =
                client.send(
                    HttpRequest.newBuilder(URI("http://localhost:${agingServer.boundPort}/api/AAPL/quote")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, quote().statusCode(), "a fresh quote is served on session creation")
            now = now.plus(Duration.ofMinutes(2)) // no refresh reaches it; the mark ages past the max-age
            val stale = quote()
            assertEquals(503, stale.statusCode())
            assertTrue(stale.body().contains("unavailable"), stale.body())
        } finally {
            agingServer.stop()
            agingRegistry.close()
        }
    }

    private fun dataFrame(reader: BufferedReader): String {
        while (true) {
            val line = reader.readLine() ?: error("stream closed before a data frame arrived")
            if (line.startsWith("data: ")) return line.removePrefix("data: ")
        }
    }
}
