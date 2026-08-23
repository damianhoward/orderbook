package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.model.Owner
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side

/** A resting order used to seed/replenish a side of the book, and the maker who is quoting it. */
data class SeedOrder(
    val price: Price,
    val side: Side,
    val size: Long,
    val maker: Owner,
)

/**
 * The resting liquidity a [MarketSession] opens with and tops a swept side up from, so the shared
 * book never looks empty. An explicit injected value, not data baked into the session.
 *
 * The ladder belongs to **market makers, not to the venue.** An exchange matches orders; it does
 * not take the other side of them, and a book whose liquidity belonged to the venue running it
 * would describe a dealer rather than an exchange. Several makers rather than one is the same
 * point: it is what a real ladder is, it gives self-match prevention something to protect, and the
 * quote-driven layer above solicits from participants of exactly this kind.
 */
data class SeedLiquidity(
    val orders: List<SeedOrder>,
) {
    fun forSide(side: Side): List<SeedOrder> = orders.filter { it.side == side }

    companion object {
        /**
         * The makers quoting the opening ladder. Named, because a counterparty is not a
         * placeholder. Published so that a ladder built somewhere else — around a real quote, say —
         * is quoted by the same participants rather than inventing a second set.
         */
        val MAKERS = listOf(Owner("mm-northwind"), Owner("mm-lockstep"), Owner("mm-halcyon"))

        /** The maker quoting the [index]th level of a ladder, cycling so each quotes several. */
        fun makerAt(index: Int): Owner = MAKERS[index % MAKERS.size]

        fun default(): SeedLiquidity =
            SeedLiquidity(
                // Interleaved across the makers so each quotes at several levels on both sides,
                // the way a ladder is actually built up — rather than one maker owning a side.
                listOf(
                    SeedOrder(Price.of("100.10"), Side.OFFER, 180, MAKERS[0]),
                    SeedOrder(Price.of("100.22"), Side.OFFER, 390, MAKERS[1]),
                    SeedOrder(Price.of("100.34"), Side.OFFER, 280, MAKERS[2]),
                    SeedOrder(Price.of("100.46"), Side.OFFER, 560, MAKERS[0]),
                    SeedOrder(Price.of("100.58"), Side.OFFER, 310, MAKERS[1]),
                    SeedOrder(Price.of("100.70"), Side.OFFER, 420, MAKERS[2]),
                    SeedOrder(Price.of("99.90"), Side.BID, 210, MAKERS[1]),
                    SeedOrder(Price.of("99.78"), Side.BID, 450, MAKERS[2]),
                    SeedOrder(Price.of("99.66"), Side.BID, 330, MAKERS[0]),
                    SeedOrder(Price.of("99.54"), Side.BID, 600, MAKERS[1]),
                    SeedOrder(Price.of("99.42"), Side.BID, 410, MAKERS[2]),
                    SeedOrder(Price.of("99.30"), Side.BID, 520, MAKERS[0]),
                ),
            )
    }
}
