package com.damianhoward.orderbook.quote

import com.damianhoward.marketdata.model.Quote
import com.damianhoward.orderbook.market.SeedLiquidity
import com.damianhoward.orderbook.market.SeedOrder
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Builds a [SeedLiquidity] ladder around a real [Quote]'s last price: six levels each side,
 * the same shape as [SeedLiquidity.default], just centred on the market instead of a fixed ~100.
 * Sizes vary level to level rather than tapering monotonically, so the depth profile reads like a
 * working book. Offsets are a percentage of price, not a fixed cent amount, so the ladder looks
 * sane whether the instrument trades at $20 or $300.
 */
object QuoteSeed {
    private val OFFSET_PERCENTAGES =
        listOf("0.0010", "0.0022", "0.0034", "0.0046", "0.0058", "0.0070").map { BigDecimal(it) }
    private val OFFER_SIZES = listOf(180L, 390L, 280L, 560L, 310L, 420L)
    private val BID_SIZES = listOf(210L, 450L, 330L, 600L, 410L, 520L)

    fun around(quote: Quote): SeedLiquidity {
        val mid = quote.last
        val offers =
            OFFSET_PERCENTAGES.zip(OFFER_SIZES).map { (percent, size) ->
                SeedOrder(tick(mid + mid.multiply(percent)), Side.OFFER, size)
            }
        val bids =
            OFFSET_PERCENTAGES.zip(BID_SIZES).map { (percent, size) ->
                SeedOrder(tick(mid - mid.multiply(percent)), Side.BID, size)
            }
        return SeedLiquidity(offers + bids)
    }

    private fun tick(value: BigDecimal): Price = Price.of(value.setScale(2, RoundingMode.HALF_UP).toPlainString())
}
