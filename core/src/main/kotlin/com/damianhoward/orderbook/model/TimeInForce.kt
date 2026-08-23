package com.damianhoward.orderbook.model

/**
 * How long an order is allowed to work, and what happens to the part that does not fill.
 *
 * Expiry is a time-in-force rather than a nullable timestamp beside one. A `GoodTilCancelled` order
 * with an expiry field set is a state the type would permit and the book would have to interpret;
 * [GoodTilTime] makes "this order has a deadline" the same fact as "this order rests until told
 * otherwise", read from one place. It also keeps the combinations honest — an immediate order
 * cannot carry a deadline, because it never survives its own submit.
 */
sealed interface TimeInForce {
    /**
     * True when this order must not trade at [nowMillis]. Only [GoodTilTime] can answer yes; every
     * other time-in-force either rests indefinitely or does not survive its own submit.
     */
    fun hasExpiredAt(nowMillis: Long): Boolean = false

    /** Rests until it fills or is cancelled. The default, and what seeded liquidity uses. */
    data object GoodTilCancelled : TimeInForce

    /**
     * Fills what it can immediately; the remainder is discarded rather than rested. What a hit on
     * a quote should be, so a thin book cannot leave the taker resting at a price it chose for an
     * execution it expected to be complete.
     */
    data object ImmediateOrCancel : TimeInForce

    /**
     * Fills completely or not at all, leaving the book untouched when it cannot. What a negotiated
     * size needs: a partial fill of an agreed quantity is a different trade from the one agreed.
     */
    data object FillOrKill : TimeInForce

    /**
     * Rests until [expiresAtMillis], then stops being tradeable. A maker's quote is the case — it
     * is live for as long as the maker stands behind it and no longer.
     *
     * The deadline is inclusive of the instant itself: an order is expired *after*
     * [expiresAtMillis], so one submitted and matched within the same millisecond still trades.
     */
    data class GoodTilTime(
        val expiresAtMillis: Long,
    ) : TimeInForce {
        override fun hasExpiredAt(nowMillis: Long): Boolean = nowMillis > expiresAtMillis
    }
}
