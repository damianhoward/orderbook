package com.damianhoward.orderbook.kafka

/**
 * A read-only view of the egress counters, so the web layer can publish them without depending on
 * the whole [KafkaMarketEgress] or its producer. All four are monotonic since process start.
 */
interface EgressMetrics {
    /** Depth snapshots shed under pressure — benign, each superseded by the next. */
    val dropped: Long

    /** Fills or commands shed because the durable queue overflowed — a real gap in the log. */
    val lost: Long

    /** Records the broker acknowledged, across all topics. */
    val published: Long

    /** Send attempts that completed with an error (broker unreachable, timeout). */
    val failed: Long
}
