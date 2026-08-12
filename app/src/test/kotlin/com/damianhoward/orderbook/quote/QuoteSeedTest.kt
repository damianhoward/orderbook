package com.damianhoward.orderbook.quote

import com.damianhoward.marketdata.model.Instrument
import com.damianhoward.marketdata.model.Quote
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class QuoteSeedTest {
    private fun quote(last: String) =
        Quote(
            instrument = Instrument("AAPL", "Apple Inc.", "USD", "NasdaqGS"),
            last = BigDecimal(last),
            previousClose = BigDecimal(last),
            dayHigh = BigDecimal(last),
            dayLow = BigDecimal(last),
            asOf = Instant.parse("2026-07-15T20:00:00Z"),
            marketOpen = true,
        )

    @Test
    fun `builds six levels each side, centred on the quote's last price`() {
        val seed = QuoteSeed.around(quote("100.00"))

        assertEquals(
            listOf(
                Price.of("100.10"),
                Price.of("100.22"),
                Price.of("100.34"),
                Price.of("100.46"),
                Price.of("100.58"),
                Price.of("100.70"),
            ),
            seed.forSide(Side.OFFER).map { it.price },
        )
        assertEquals(
            listOf(
                Price.of("99.90"),
                Price.of("99.78"),
                Price.of("99.66"),
                Price.of("99.54"),
                Price.of("99.42"),
                Price.of("99.30"),
            ),
            seed.forSide(Side.BID).map { it.price },
        )
    }

    @Test
    fun `sizes vary by level, in the hundreds on both sides`() {
        val seed = QuoteSeed.around(quote("100.00"))

        assertEquals(listOf(180L, 390L, 280L, 560L, 310L, 420L), seed.forSide(Side.OFFER).map { it.size })
        assertEquals(listOf(210L, 450L, 330L, 600L, 410L, 520L), seed.forSide(Side.BID).map { it.size })
    }

    @Test
    fun `scales proportionally to price - a low-priced instrument gets a tighter ladder`() {
        val seed = QuoteSeed.around(quote("20.00"))

        assertEquals(
            listOf(
                Price.of("20.02"),
                Price.of("20.04"),
                Price.of("20.07"),
                Price.of("20.09"),
                Price.of("20.12"),
                Price.of("20.14"),
            ),
            seed.forSide(Side.OFFER).map { it.price },
        )
        assertEquals(
            listOf(
                Price.of("19.98"),
                Price.of("19.96"),
                Price.of("19.93"),
                Price.of("19.91"),
                Price.of("19.88"),
                Price.of("19.86"),
            ),
            seed.forSide(Side.BID).map { it.price },
        )
    }

    @Test
    fun `every offer sits above every bid, whatever the price`() {
        val seed = QuoteSeed.around(quote("33.33"))
        val bestBid = seed.forSide(Side.BID).maxOf { it.price }
        val bestOffer = seed.forSide(Side.OFFER).minOf { it.price }

        assertTrue(bestBid < bestOffer, "best bid $bestBid must be below best offer $bestOffer")
    }
}
