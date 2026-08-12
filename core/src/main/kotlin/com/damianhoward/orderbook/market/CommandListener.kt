package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side

/**
 * One accepted order submission, as the market processed it. The command log holds these in
 * writer-thread order: everything else the session does (seeding, replenishment, order ids) is
 * a deterministic function of the seed and this sequence, so replaying the log into a fresh
 * session reproduces the book exactly — see [replay].
 */
data class SubmitCommand(
    val side: Side,
    val price: Price,
    val size: Long,
    val timeMillis: Long,
)

/**
 * Receives every accepted command, invoked on the market's writer thread after the submit has
 * been applied — a rejected submit never reaches the log. Same contract as [FillListener]:
 * return quickly, never block.
 */
fun interface CommandListener {
    fun onSubmit(command: SubmitCommand)

    companion object {
        /** Discards commands — the default for a market with no egress attached. */
        val NONE = CommandListener { }
    }
}
