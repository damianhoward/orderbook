package com.damianhoward.orderbook.kafka

import java.util.concurrent.atomic.AtomicLong

/**
 * Stable execution identity for published fills: `<epochMillis>-<sequence>`, where the epoch is
 * captured at construction and the sequence is monotonic within the process. Two ids from one
 * egress never collide, and restarts change the epoch, so an id is unique across the stream's
 * whole history — one egress process exists per estate, which is what makes the epoch a safe
 * namespace. The id travels inside the record, so a republished copy of the same fill keeps its
 * identity wherever it lands.
 */
class ExecutionIds(
    private val epochMillis: Long = System.currentTimeMillis(),
) {
    private val sequence = AtomicLong()

    fun next(): String = "$epochMillis-${sequence.incrementAndGet()}"
}
