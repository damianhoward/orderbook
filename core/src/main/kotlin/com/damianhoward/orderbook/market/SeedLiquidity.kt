package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side

/** A resting order used to seed/replenish a side of the book. */
data class SeedOrder(
    val price: Price,
    val side: Side,
    val size: Long,
)

/**
 * The resting liquidity a [MarketSession] opens with and tops a swept side up from, so the shared
 * book never looks empty. An explicit injected value, not data baked into the session.
 */
data class SeedLiquidity(
    val orders: List<SeedOrder>,
) {
    fun forSide(side: Side): List<SeedOrder> = orders.filter { it.side == side }

    companion object {
        fun default(): SeedLiquidity =
            SeedLiquidity(
                listOf(
                    SeedOrder(Price.of("100.10"), Side.OFFER, 180),
                    SeedOrder(Price.of("100.22"), Side.OFFER, 390),
                    SeedOrder(Price.of("100.34"), Side.OFFER, 280),
                    SeedOrder(Price.of("100.46"), Side.OFFER, 560),
                    SeedOrder(Price.of("100.58"), Side.OFFER, 310),
                    SeedOrder(Price.of("100.70"), Side.OFFER, 420),
                    SeedOrder(Price.of("99.90"), Side.BID, 210),
                    SeedOrder(Price.of("99.78"), Side.BID, 450),
                    SeedOrder(Price.of("99.66"), Side.BID, 330),
                    SeedOrder(Price.of("99.54"), Side.BID, 600),
                    SeedOrder(Price.of("99.42"), Side.BID, 410),
                    SeedOrder(Price.of("99.30"), Side.BID, 520),
                ),
            )
    }
}
