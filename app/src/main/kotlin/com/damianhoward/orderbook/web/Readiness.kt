package com.damianhoward.orderbook.web

import com.damianhoward.orderbook.kafka.EgressMetrics
import com.damianhoward.orderbook.market.MarketSession
import com.damianhoward.orderbook.model.Side

/**
 * The order book's operational truth, rendered for `/readyz` and `/metrics`. `/healthz` proves the
 * web process answers; the matching engine has no external dependency in its serve path, so the
 * readiness signal that means something is that the engine itself computes. [matchingEngine] builds
 * a throwaway synthetic book and fills a marketable order end to end, so a deploy whose matching
 * path throws or mis-seeds reads as not-ready (503) rather than serving a broken book on the first
 * request.
 *
 * The check is injected so a test can force the failure path; the probe swallows any throw into a
 * 503, never dropping the connection.
 *
 * The egress counters ride along without affecting the verdict, and that separation is deliberate.
 * `lost` above zero is this service's most alert-worthy number: fills or commands shed because the
 * durable queue overflowed, a real gap in the log, unlike `dropped` depth snapshots which are each
 * superseded by the next. But it is monotonic since process start, so failing readiness on it would
 * mean 503 forever until a restart, and the deploy gate would roll back every release after the
 * first incident. A counter is something to alert on; readiness is whether this process can serve
 * the next request. Reporting it here is what lets a single external probe of `/readyz` see both.
 */
class Readiness(
    private val selfCheck: () -> Unit,
    private val egress: EgressMetrics? = null,
) {
    data class Probe(
        val ready: Boolean,
        val json: String,
    )

    /**
     * One evaluation of everything above, which both `/readyz` and `/metrics` render.
     *
     * Document 17 requires the two endpoints to derive from the same objects so they cannot
     * disagree. Taking one snapshot is the strongest form of that, and it does real work here
     * beyond the rule: the four counters are independent volatile reads off a running producer, so
     * rendering them twice from the live object would let `/metrics` publish a set that was never
     * simultaneously true.
     */
    private data class Snapshot(
        val ready: Boolean,
        val egress: EgressState?,
    )

    private data class EgressState(
        val published: Long,
        val failed: Long,
        val dropped: Long,
        val lost: Long,
    )

    private fun snapshot(): Snapshot {
        val ready =
            try {
                selfCheck()
                true
            } catch (_: Exception) {
                false
            }
        return Snapshot(
            ready = ready,
            egress = egress?.let { EgressState(it.published, it.failed, it.dropped, it.lost) },
        )
    }

    fun probe(): Probe {
        val now = snapshot()
        return Probe(
            now.ready,
            """{"ready":${now.ready},"match":{"ok":${now.ready}},"egress":${egressJson(now.egress)}}""",
        )
    }

    /**
     * The same snapshot in Prometheus text format.
     *
     * There is no separate gauge for the matching self-check. It is the only readiness condition
     * this service has, so a second series would restate `orderbook_ready` under another name and
     * invite the two to drift apart in a dashboard. `trading-system` publishes per-condition gauges
     * because it aggregates several; here the verdict and the condition are the same fact.
     *
     * The counters are absent rather than zero when no producer is wired, matching `/readyz`.
     * `orderbook_egress_enabled` is what distinguishes the two cases, and it is a gauge rather than
     * an absence so a collector can tell "not configured" from "not scraped".
     */
    fun metrics(): String {
        val now = snapshot()
        val out = StringBuilder()

        fun emit(
            name: String,
            help: String,
            type: String,
            value: Any,
        ) {
            out
                .append("# HELP ")
                .append(name)
                .append(' ')
                .append(help)
                .append('\n')
            out
                .append("# TYPE ")
                .append(name)
                .append(' ')
                .append(type)
                .append('\n')
            out
                .append(name)
                .append(' ')
                .append(value)
                .append('\n')
        }

        emit("orderbook_ready", "Whether the matching engine filled its self-check order.", "gauge", now.ready.toInt())
        emit(
            "orderbook_egress_enabled",
            "Whether a Kafka producer is wired. The counters below are absent when it is not.",
            "gauge",
            (now.egress != null).toInt(),
        )
        now.egress?.let {
            emit(
                "orderbook_egress_published_total",
                "Records the broker acknowledged, across all topics.",
                "counter",
                it.published,
            )
            emit(
                "orderbook_egress_failed_total",
                "Send attempts that completed with an error.",
                "counter",
                it.failed,
            )
            emit(
                "orderbook_egress_dropped_total",
                "Depth snapshots shed under pressure. Benign — each is superseded by the next.",
                "counter",
                it.dropped,
            )
            emit(
                "orderbook_egress_lost_total",
                "Fills or commands shed because the durable queue overflowed. A real gap in the log.",
                "counter",
                it.lost,
            )
        }

        return out.toString()
    }

    private fun Boolean.toInt(): Int = if (this) 1 else 0

    // Absent rather than a misleading zero when no producer is configured: "enabled":false says
    // nothing shipped because nothing was meant to, which is a different fact from shipping
    // nothing while trying to.
    private fun egressJson(state: EgressState?): String =
        state?.let {
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
