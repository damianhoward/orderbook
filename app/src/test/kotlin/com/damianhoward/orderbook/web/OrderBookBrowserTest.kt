package com.damianhoward.orderbook.web

import com.damianhoward.marketdata.cache.QuoteCache
import com.damianhoward.marketdata.model.Instrument
import com.damianhoward.marketdata.model.Quote
import com.damianhoward.marketdata.source.QuoteSource
import com.damianhoward.marketdata.source.QuoteUnavailable
import com.damianhoward.orderbook.market.MarketSession
import com.damianhoward.orderbook.quote.QuoteSeed
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.regex.Pattern

/**
 * The live site in a headless Chromium over a real [WebServer]: the SSE stream, the order ticket
 * and the symbol picker, driven the way a visitor drives them. [WebServerTest] pins the HTTP
 * contract; this pins `app.js` holding up its end of it, so a renamed snapshot field or a broken
 * stream handler fails CI instead of the public page.
 *
 * A fresh server per test: submitting orders changes the book, and each journey starts from the
 * book a first visitor would see.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderBookBrowserTest {
    // AAPL (the page's default) and MSFT resolve; anything else fails like an unknown ticker.
    private val quoteSource =
        QuoteSource { symbol ->
            if (symbol !in setOf("AAPL", "MSFT")) throw QuoteUnavailable("no such symbol: $symbol")
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
    private lateinit var registry: SessionRegistry
    private lateinit var server: WebServer
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser

    @BeforeAll
    fun launch() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch()
    }

    @AfterAll
    fun close() {
        browser.close()
        playwright.close()
    }

    @BeforeEach
    fun start() {
        val quotes = QuoteCache(quoteSource)
        // Same wiring as main(), so the page's frames come from the market's real push path.
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

    private fun open(query: String = ""): Page {
        val page = browser.newContext(Browser.NewContextOptions().setViewportSize(1440, 900)).newPage()
        page.navigate("http://127.0.0.1:${server.boundPort}/$query")
        return page
    }

    private fun live(page: Page) {
        assertThat(page.locator("#connlbl")).hasText("live")
        assertThat(page.locator("#bids .row").first()).isVisible()
    }

    @Test
    fun `streams the book on load and primes the ticket from the best bid`() {
        val page = open()
        live(page)
        assertThat(page.locator("#asks .row").first()).isVisible()
        assertThat(page.locator("#st-bid")).not().hasText("—")
        assertThat(page.locator("#price")).hasValue(page.locator("#st-bid").textContent())
        assertThat(page.locator("#tape")).hasText("no trades yet")
    }

    @Test
    fun `a sell at the best bid fills, and the fill reaches the tape and the last price`() {
        val page = open()
        live(page)
        val bestBid = page.locator("#st-bid").textContent()

        page.locator("#sell").click()
        assertThat(page.locator("#sell")).hasAttribute("aria-pressed", "true")
        page.locator("#price").fill(bestBid)
        page.locator("#size").fill("1")
        assertThat(page.locator("#submit")).hasText("Sell 1 @ $bestBid")
        page.locator("#submit").click()

        assertThat(page.locator("#result")).hasText("✓ 1 fill")
        assertThat(page.locator("#tape .trow").first().locator(".ts")).hasText("SELL")
        assertThat(page.locator("#tape .trow").first().locator(".tp")).hasText(bestBid)
        assertThat(page.locator("#st-last")).hasText(bestBid)
    }

    @Test
    fun `a bad size is refused with the server's reason`() {
        val page = open()
        live(page)
        page.locator("#size").fill("0")
        page.locator("#submit").click()
        assertThat(page.locator("#result")).hasClass(Pattern.compile("\\berr\\b"))
        assertThat(page.locator("#result")).containsText("✕")
        assertThat(page.locator("#tape")).hasText("no trades yet")
    }

    @Test
    fun `an unknown symbol is refused and the current book stays connected`() {
        val page = open()
        live(page)
        page.locator("#symbol-input").fill("ZZZZ")
        page.locator("#symbol-input").press("Enter")

        assertThat(page.locator("#result")).containsText("✕")
        assertThat(page).not().hasURL(Pattern.compile("symbol=ZZZZ"))
        assertThat(page.locator("#connlbl")).hasText("live")
    }

    @Test
    fun `switching symbol moves the page to the new instrument`() {
        val page = open()
        live(page)
        page.locator("#symbol-input").fill("msft")
        page.locator("#symbol-input").press("Enter")

        assertThat(page).hasURL(Pattern.compile("symbol=MSFT"))
        assertThat(page.locator("#symbol-input")).hasValue("MSFT")
        assertThat(page.locator("#eyebrow")).containsText("MSFT Inc.")
        live(page)
    }

    @Test
    fun `embedded in the desk, the site's own chrome is hidden`() {
        val page = open("?embed=1")
        live(page)
        assertThat(page.locator(".topbar")).isHidden()
        assertThat(page.locator(".statusbar")).isHidden()
    }
}
