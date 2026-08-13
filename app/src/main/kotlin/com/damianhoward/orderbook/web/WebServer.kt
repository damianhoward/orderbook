package com.damianhoward.orderbook.web

import com.damianhoward.marketdata.cache.QuoteCache
import com.damianhoward.marketdata.source.YahooQuoteSource
import com.damianhoward.orderbook.kafka.EgressMetrics
import com.damianhoward.orderbook.kafka.KafkaMarketEgress
import com.damianhoward.orderbook.kafka.ScramCredentials
import com.damianhoward.orderbook.market.BookAtCapacityException
import com.damianhoward.orderbook.market.CommandListener
import com.damianhoward.orderbook.market.DepthListener
import com.damianhoward.orderbook.market.FillListener
import com.damianhoward.orderbook.market.Market
import com.damianhoward.orderbook.market.MarketSession
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.quote.QuoteSeed
import com.damianhoward.orderbook.quote.toJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * HTTP transport for a [SessionRegistry]: serves the static UI plus, per symbol, the JSON API and
 * the SSE stream endpoint. Live frames reach each symbol's [Broadcaster] from its market's depth
 * stream via [DepthBroadcast] (wired where the session is built — see [main]); this class only
 * attaches clients to it. Plumbing only — book behaviour lives in the market layer, rendering in
 * the view layer. JDK [HttpServer] on a request pool capped at [maxPoolThreads]; each SSE stream
 * pins one pool thread for its connection's lifetime, and requests beyond the cap are refused at
 * the connection rather than queued.
 *
 * `/api/symbols` lists the curated picker options; `/api/{symbol}/quote` reads the [quotes] cache
 * a background refresh keeps warm (see [main]) — the displayed reference price, not the book.
 * `/metrics` publishes the Kafka egress counters (see [EgressMetrics]) in Prometheus text format,
 * rendered from the same [Readiness] snapshot `/readyz` answers from.
 *
 * Reads are GET (HEAD answers identically, minus the body); `/api/{symbol}/order` mutates the
 * book, so it is POST-only — a GET that changes
 * state would be prefetchable/cacheable — and rate-limited per client ([orderLimiter], keyed by
 * [ClientIp]). An unrecognised symbol shape is a 404 (see [UnknownSymbolException]); invalid
 * order input maps to a 400 with a JSON `error` body; a client past its rate maps to a 429 with
 * `Retry-After`; a saturated book maps to a 503 (see [BookAtCapacityException]); anything
 * unexpected maps to a 500.
 *
 * `/healthz` proves the web process answers; `/readyz` runs [Readiness]'s matching-engine
 * self-check (build a synthetic book, fill a marketable order), so a deploy whose matching path
 * is broken reads as not-ready rather than serving a broken book.
 */
class WebServer(
    private val registry: SessionRegistry,
    private val quotes: QuoteCache,
    private val assets: WebAssets,
    private val port: Int,
    private val orderLimiter: TokenBucketRateLimiter = TokenBucketRateLimiter(capacity = 20, refillPerSecond = 5.0),
    private val maxPoolThreads: Int = 64,
    private val egressMetrics: EgressMetrics? = null,
    private val readiness: Readiness = Readiness.matchingEngine(egressMetrics),
    /**
     * Loopback by default: this server is meant to be reached through the reverse proxy that
     * terminates TLS, applies the security headers and writes the access log, never directly.
     * Binding every interface — which `InetSocketAddress(port)` alone does — makes that
     * guarantee depend on a firewall rule being right somewhere else. Appended last so existing
     * positional call sites are unaffected.
     */
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
) {
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService

    /** Binds and starts serving; requesting port 0 binds an ephemeral port (see [boundPort]). */
    fun start() {
        server = HttpServer.create(InetSocketAddress(bindAddress, port), 0)
        // Cached-pool reuse and keep-alive but with a hard thread ceiling: SSE streams hold their
        // pool thread, so an unbounded pool lets slow-reading clients grow memory without limit.
        // No work queue — a request queued behind saturated SSE streams would wait forever, so
        // saturation refuses the new connection instead.
        executor =
            ThreadPoolExecutor(0, maxPoolThreads, 60L, TimeUnit.SECONDS, SynchronousQueue()) {
                Thread(it).apply { isDaemon = true }
            }
        server.executor = executor
        server.createContext("/", ::route)
        server.start()
        println("Order book server listening on :$boundPort")
    }

    /** The port actually bound — differs from the requested one when 0 (ephemeral) was asked for. */
    val boundPort: Int get() = server.address.port

    /** Stops accepting connections and shuts down the request pool this server created. */
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun route(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val api = API_PATH.matchEntire(path)
            when {
                path == "/healthz" -> get(exchange) { respond(exchange, 200, "text/plain", "ok") }
                path == "/readyz" -> get(exchange) { ready(exchange) }
                path == "/metrics" -> get(exchange) { respond(exchange, 200, PROMETHEUS_CONTENT_TYPE, readiness.metrics()) }
                path == "/" -> get(exchange) { respond(exchange, 200, "text/html; charset=utf-8", assets.indexHtml) }
                path == "/app.css" -> get(exchange) { respond(exchange, 200, "text/css; charset=utf-8", assets.appCss) }
                path == "/app.js" -> get(exchange) { respond(exchange, 200, "text/javascript; charset=utf-8", assets.appJs) }
                path == "/privacy" -> get(exchange) { respond(exchange, 200, "text/html; charset=utf-8", assets.privacyHtml) }
                path == "/api/symbols" -> get(exchange) { respond(exchange, 200, "application/json", CuratedSymbols.toJson()) }
                api != null -> routeApi(exchange, api.groupValues[1], api.groupValues[2])
                else -> respond(exchange, 404, "text/plain", "not found")
            }
        } catch (e: UnknownSymbolException) {
            respond(exchange, 404, "application/json", """{"error":${jsonString(e.message ?: "unknown symbol")}}""")
        } catch (e: IllegalArgumentException) {
            respond(exchange, 400, "application/json", """{"error":${jsonString(e.message ?: "bad request")}}""")
        } catch (e: BookAtCapacityException) {
            // The book is a bounded, resettable resource; a saturated one can't accept more resting
            // orders until it drains, so this is a 503, not a client error in the request itself.
            respond(exchange, 503, "application/json", """{"error":${jsonString(e.message ?: "order book at capacity")}}""")
        } catch (e: Exception) {
            // Anything unexpected must still answer the request — without this the connection
            // just closes with no status line. The stack goes to stderr -> journalctl; the
            // response stays generic.
            e.printStackTrace()
            runCatching { respond(exchange, 500, "application/json", """{"error":"internal error"}""") }
        }
    }

    // The symbol resolves to its ManagedSession (creating or evicting via the registry) before the
    // method check, so an unknown symbol reads as a 404 regardless of verb.
    private fun routeApi(
        exchange: HttpExchange,
        rawSymbol: String,
        action: String,
    ) {
        val managed = registry.sessionFor(rawSymbol)
        val symbol = SessionRegistry.normalize(rawSymbol)
        when (action) {
            "state" -> get(exchange) { respond(exchange, 200, "application/json", managed.session.snapshot().toJson()) }
            "order" ->
                post(exchange) {
                    rateLimited(exchange) { respond(exchange, 200, "application/json", submit(exchange, managed.session)) }
                }
            "stream" ->
                get(exchange) {
                    // A HEAD must not attach to the broadcaster — it wants headers, not a stream.
                    if (exchange.requestMethod == "HEAD") {
                        respond(exchange, 200, "text/event-stream", "")
                    } else {
                        managed.broadcaster.stream(exchange, managed.session.snapshot().toJson())
                    }
                }
            // The resting book anchors once at session creation; this is the number that keeps
            // ticking on a schedule (see main's quote-refresh loop) without touching the book.
            // A quote aged out under the cache's max-age (a prolonged provider outage) is withheld
            // rather than shown stale — a 503, not a fabricated freshness.
            "quote" ->
                get(exchange) {
                    val cached = quotes.latest(symbol)
                    if (cached == null) {
                        respond(exchange, 503, "application/json", """{"error":"quote temporarily unavailable"}""")
                    } else {
                        respond(exchange, 200, "application/json", cached.quote.toJson())
                    }
                }
        }
    }

    // Only the state-mutating route is limited; reads stay open. The key follows ClientIp's trust
    // rule, so traffic through Caddy or the desk is limited per real client, not per proxy.
    private inline fun rateLimited(
        exchange: HttpExchange,
        handler: () -> Unit,
    ) {
        val key = ClientIp.of(exchange.remoteAddress.address, exchange.requestHeaders.getFirst("X-Forwarded-For"))
        val decision = orderLimiter.tryAcquire(key)
        if (decision.allowed) {
            handler()
        } else {
            exchange.responseHeaders.add("Retry-After", decision.retryAfterSeconds.toString())
            respond(exchange, 429, "application/json", """{"error":"rate limit exceeded"}""")
        }
    }

    private fun submit(
        exchange: HttpExchange,
        session: Market,
    ): String {
        val params = queryParams(exchange.requestURI.rawQuery)
        val side = parseSide(params["side"])
        val price = parsePrice(params["price"])
        val size = parseSize(params["size"])

        // The SSE push is not done here: the market's depth stream broadcasts on the writer
        // thread, so racing submits can't deliver stale frames out of order.
        val outcome = session.submit(side, price, size)
        return """{"matched":${outcome.matched},${outcome.snapshot.toJson().drop(1)}"""
    }

    private fun parseSide(raw: String?): Side =
        when {
            raw.equals("BID", true) || raw.equals("BUY", true) -> Side.BID
            raw.equals("OFFER", true) || raw.equals("SELL", true) -> Side.OFFER
            else -> throw IllegalArgumentException("side must be BID/BUY or OFFER/SELL, got '${raw ?: ""}'")
        }

    // Price.of signals over-precision/overflow with ArithmeticException; remap it so every invalid
    // input reaches the caller as the one exception type route() turns into a 400.
    private fun parsePrice(raw: String?): Price {
        val text = raw ?: throw IllegalArgumentException("price required")
        return try {
            Price.of(text)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("price is not a valid decimal: '$text'")
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("price must fit ${Price.SCALE} decimal places without overflow: '$text'")
        }
    }

    private fun parseSize(raw: String?): Long {
        val text = raw ?: throw IllegalArgumentException("size required")
        return text.toLongOrNull() ?: throw IllegalArgumentException("size is not a valid integer: '$text'")
    }

    // HEAD rides every GET route: the handler runs identically and respond() suppresses the body,
    // so the status and headers a HEAD probe sees are the ones the GET would have produced.
    private inline fun get(
        exchange: HttpExchange,
        handler: () -> Unit,
    ) = allow(exchange, "GET, HEAD", handler)

    private inline fun post(
        exchange: HttpExchange,
        handler: () -> Unit,
    ) = allow(exchange, "POST", handler)

    private inline fun allow(
        exchange: HttpExchange,
        methods: String,
        handler: () -> Unit,
    ) {
        if (exchange.requestMethod in methods.split(", ")) {
            handler()
        } else {
            exchange.responseHeaders.add("Allow", methods)
            respond(exchange, 405, "text/plain", "method not allowed")
        }
    }

    private fun queryParams(raw: String?): Map<String, String> =
        (raw ?: "").split("&").filter { it.contains("=") }.associate {
            val (key, value) = it.split("=", limit = 2)
            key to java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

    // Liveness says the web process answers; this exercises the matching engine itself, so a deploy
    // whose matching path is broken is not-ready rather than serving a broken book.
    private fun ready(exchange: HttpExchange) {
        val probe = readiness.probe()
        respond(exchange, if (probe.ready) 200 else 503, "application/json", probe.json)
    }

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        if (exchange.requestMethod == "HEAD") {
            // Headers only: -1 tells the JDK server no body follows, which is the one length it
            // accepts on a HEAD without logging a warning (it drops Content-Length either way).
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        private val API_PATH = Regex("^/api/([^/]+)/(state|order|stream|quote)$")

        // The version parameter is part of the Prometheus exposition content type, not decoration:
        // a collector reads it to decide which parser to use, and omitting it makes the endpoint
        // depend on the collector guessing right from the body.
        private const val PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
    }
}

fun main() {
    val port = (System.getenv("PORT") ?: "8080").toInt()
    // Kafka egress is opt-in by environment: unset means no producer exists at all and the
    // server runs exactly as before. The fills topic is the seam downstream consumers read;
    // the commands topic is the ordered log a fresh book can be replayed from; the L2 topic
    // carries the latest depth (each record supersedes the last — compact it broker-side).
    val egress =
        System.getenv("KAFKA_BOOTSTRAP_SERVERS")?.let { bootstrap ->
            KafkaMarketEgress.create(
                bootstrapServers = bootstrap,
                fillsTopic = System.getenv("KAFKA_FILLS_TOPIC") ?: KafkaMarketEgress.DEFAULT_FILLS_TOPIC,
                commandsTopic = System.getenv("KAFKA_COMMANDS_TOPIC") ?: KafkaMarketEgress.DEFAULT_COMMANDS_TOPIC,
                l2Topic = System.getenv("KAFKA_L2_TOPIC") ?: KafkaMarketEgress.DEFAULT_L2_TOPIC,
                scram = ScramCredentials.fromEnv(System.getenv()),
            )
        }
    // The book anchors once, at session creation, to this quote's last price — see QuoteSeed.
    // The displayed reference price then ticks independently on a schedule (below), without ever
    // touching resting orders: no real venue reprices a trader's resting limit orders for them.
    // Withhold a displayed quote once it is older than QUOTE_MAX_AGE (a sustained Yahoo outage):
    // far above the refresh interval, so a healthy refresh keeps it current and only a real outage
    // trips it. The negative cache (unknown symbols) and last-good behaviour are the cache's defaults.
    val quotes = QuoteCache(YahooQuoteSource(), maxAge = QUOTE_MAX_AGE)
    // One session per symbol, created on first request and evicted least-recently-used past the
    // cap — see SessionRegistry. A symbol whose quote can't be fetched at all (never resolved
    // before, and this attempt also failed) is unknown, not a fallback to the synthetic ladder —
    // this feature exists to show real prices, so a book must never silently fake one.
    val registry =
        SessionRegistry { symbol ->
            val cached = quotes.refresh(symbol) ?: throw UnknownSymbolException(symbol)
            val broadcaster = SseBroadcaster()
            val symbolEgress = egress?.forSymbol(symbol)
            val session =
                MarketSession(
                    seed = QuoteSeed.around(cached.quote),
                    fills = symbolEgress ?: FillListener.NONE,
                    commands = symbolEgress ?: CommandListener.NONE,
                    // SSE frames leave from the depth stream on the writer thread — see DepthBroadcast.
                    depth = DepthListener.tee(symbolEgress ?: DepthListener.NONE, DepthBroadcast(broadcaster)),
                )
            broadcaster.startHeartbeat()
            ManagedSession(session, broadcaster)
        }
    // Keeps every currently open symbol's displayed quote fresh without a client ever triggering
    // a Yahoo call itself; a transient failure here just leaves quotes.latest() at the last-good
    // value (QuoteCache's own contract), so a blip never blanks an already-open book's label.
    val quoteRefresh =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "quote-refresh").apply { isDaemon = true } }
    quoteRefresh.scheduleWithFixedDelay(
        { registry.symbols().forEach { symbol -> runCatching { quotes.refresh(symbol) } } },
        QUOTE_REFRESH_INTERVAL_SECONDS,
        QUOTE_REFRESH_INTERVAL_SECONDS,
        TimeUnit.SECONDS,
    )
    val server = WebServer(registry, quotes, WebAssets.load(), port, egressMetrics = egress)
    // main owns what it wires: on SIGTERM (systemd stop/restart) the server stops accepting,
    // then every open session's broadcaster heartbeat and writer thread are released, and the
    // egress flushes what it holds before the producer closes.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            quoteRefresh.shutdownNow()
            registry.close()
            egress?.close()
        },
    )
    server.start()
}

private const val QUOTE_REFRESH_INTERVAL_SECONDS = 30L

// Twenty refresh cycles: a healthy 30s refresh keeps a quote far inside this, so it withholds a
// displayed price only during a sustained provider outage, never in normal operation.
private val QUOTE_MAX_AGE: Duration = Duration.ofMinutes(10)
