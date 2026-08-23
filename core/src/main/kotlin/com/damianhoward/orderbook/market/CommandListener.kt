package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.model.Owner
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side

/**
 * One accepted order submission, as the market processed it. The command log holds these in
 * writer-thread order: everything else the session does (seeding, replenishment, order ids) is
 * a deterministic function of the seed and this sequence, so replaying the log into a fresh
 * session reproduces the book exactly — see [replay].
 *
 * [owner] is part of that determinism rather than bookkeeping. Self-match prevention decides
 * whether an order fills or cancels the liquidity it met, so a log that recorded only side, price
 * and size would replay faithfully for a single participant and diverge silently for several — the
 * worst shape of bug, because the replay still produces a book and it looks right.
 */
data class SubmitCommand(
    val side: Side,
    val price: Price,
    val size: Long,
    val timeMillis: Long,
    val owner: Owner,
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
