package com.damianhoward.orderbook.web

import com.damianhoward.orderbook.kafka.EgressMetrics
import com.damianhoward.orderbook.market.MarketSession
import com.damianhoward.orderbook.model.Side

/**
 * The order book's operational truth for `/readyz`. `/healthz` proves the web process answers; the
 * matching engine has no external dependency in its serve path, so the readiness signal that means
 * something is that the engine itself computes. [matchingEngine] builds a throwaway synthetic book
 * and fills a marketable order end to end, so a deploy whose matching path throws or mis-seeds reads
 * as not-ready (503) rather than serving a broken book on the first request.
 *
 * The check is injected so a test can force the failure path; the probe swallows any throw into a
 * 503, never dropping the connection.
 *
 * The egress counters ride along in the body without affecting the verdict, and that separation is
 * deliberate. `lost` above zero is this service's most alert-worthy number: fills or commands shed
 * because the durable queue overflowed, a real gap in the log, unlike `dropped` depth snapshots
 * which are each superseded by the next. But it is monotonic since process start, so failing
 * readiness on it would mean 503 forever until a restart, and the deploy gate would roll back every
 * release after the first incident. A counter is something to alert on; readiness is whether this
 * process can serve the next request. Reporting it here is what lets a single external probe of
 * `/readyz` see both.
 */
class Readiness(
    private val selfCheck: () -> Unit,
    private val egress: EgressMetrics? = null,
) {
    data class Probe(
        val ready: Boolean,
        val json: String,
    )

    fun probe(): Probe =
        try {
            selfCheck()
            Probe(true, """{"ready":true,"match":{"ok":true},"egress":${egressJson()}}""")
        } catch (_: Exception) {
            Probe(false, """{"ready":false,"match":{"ok":false},"egress":${egressJson()}}""")
        }

    // Absent rather than a misleading zero when no producer is configured: "enabled":false says
    // nothing shipped because nothing was meant to, which is a different fact from shipping
    // nothing while trying to.
    private fun egressJson(): String =
        egress?.let {
            """{"enabled":true,"published":${it.published},"failed":${it.failed},""" +
                """"dropped":${it.dropped},"lost":${it.lost}}"""
        } ?: """{"enabled":false}"""

    companion object {
        /**
         * The default self-check: seed a throwaway [MarketSession] (the synthetic ladder, no live
         * quote or Kafka) and fill a marketable buy against it, closing the session's writer thread
         * afterwards. A broken matching path throws and the probe answers 503.
         */
        fun matchingEngine(egress: EgressMetrics? = null): Readiness =
            Readiness(
                selfCheck = {
                    MarketSession().use { session ->
                        val book = session.snapshot()
                        check(book.bids.isNotEmpty() && book.asks.isNotEmpty()) { "seeded book has an empty side" }
                        check(session.submit(Side.BID, book.asks.first().price, 1).matched > 0) {
                            "matching engine did not fill a marketable order"
                        }
                    }
                },
                egress = egress,
            )
    }
}
