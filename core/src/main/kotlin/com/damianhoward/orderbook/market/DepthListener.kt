package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.view.MarketSnapshot

/**
 * Receives the book's post-mutation snapshot: once for the freshly seeded book, then after every
 * accepted submit. Each snapshot supersedes the last, so a consumer needs only the newest one —
 * which is what lets the egress shed stale depth under pressure without corrupting anyone
 * downstream. Same contract as [FillListener]: invoked on the writer thread, return quickly,
 * never block.
 */
fun interface DepthListener {
    fun onDepth(snapshot: MarketSnapshot)

    companion object {
        /** Discards snapshots — the default for a market with no egress attached. */
        val NONE = DepthListener { }

        /** One listener fanning the stream out to [listeners], invoked in order on the writer thread. */
        fun tee(vararg listeners: DepthListener): DepthListener = DepthListener { snapshot -> listeners.forEach { it.onDepth(snapshot) } }
    }
}
