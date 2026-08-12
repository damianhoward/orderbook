package com.damianhoward.orderbook.web

import kotlin.math.ceil

/**
 * Per-key token bucket: each key may burst up to [capacity] requests, then sustain
 * [refillPerSecond]. Refill is computed lazily from elapsed [nanoTime] on access, so there is no
 * scheduler thread; tests inject the clock. Thread-safe — every bucket access is serialised on the
 * key map.
 *
 * The map itself is bounded: past [maxKeys] the least-recently-used key's bucket is evicted, so
 * hostile key churn (e.g. rotating forwarded addresses) can reset other clients' buckets but never
 * grow memory. That trade — an evicted client starts a fresh burst — is acceptable here because
 * the limiter protects effort, not correctness.
 */
class TokenBucketRateLimiter(
    private val capacity: Int,
    private val refillPerSecond: Double,
    private val maxKeys: Int = 4096,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Outcome of [tryAcquire]. When [allowed] is false, [retryAfterSeconds] is at least 1. */
    data class Decision(
        val allowed: Boolean,
        val retryAfterSeconds: Long,
    )

    private class Bucket(
        var tokens: Double,
        var refilledAtNanos: Long,
    )

    private val buckets =
        object : LinkedHashMap<String, Bucket>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>): Boolean = size > maxKeys
        }

    /** Spends one token from [key]'s bucket, refilling for the time elapsed since its last use. */
    fun tryAcquire(key: String): Decision =
        synchronized(buckets) {
            val now = nanoTime()
            val bucket = buckets.getOrPut(key) { Bucket(capacity.toDouble(), now) }
            val elapsedSeconds = (now - bucket.refilledAtNanos) / 1e9
            bucket.tokens = minOf(capacity.toDouble(), bucket.tokens + elapsedSeconds * refillPerSecond)
            bucket.refilledAtNanos = now
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                Decision(allowed = true, retryAfterSeconds = 0)
            } else {
                val secondsToNextToken = (1.0 - bucket.tokens) / refillPerSecond
                Decision(allowed = false, retryAfterSeconds = ceil(secondsToNextToken).toLong().coerceAtLeast(1))
            }
        }
}
