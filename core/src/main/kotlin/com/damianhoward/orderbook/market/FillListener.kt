package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.model.Trade

/**
 * Receives every fill the market produces, invoked on the market's writer thread immediately
 * after the trade prints. Implementations must return quickly and never block — anything slow
 * (I/O, a broker) hands off to its own thread.
 */
fun interface FillListener {
    fun onFill(
        trade: Trade,
        timeMillis: Long,
    )

    companion object {
        /** Discards fills — the default for a market with no egress attached. */
        val NONE = FillListener { _, _ -> }
    }
}
