package com.damianhoward.orderbook.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Deterministic tests via an injected clock — no sleeps, no wall-time dependence. */
class TokenBucketRateLimiterTest {
    private var nowNanos = 0L

    private fun limiter(
        capacity: Int,
        refillPerSecond: Double,
        maxKeys: Int = 4096,
    ) = TokenBucketRateLimiter(capacity, refillPerSecond, maxKeys) { nowNanos }

    private fun advanceSeconds(seconds: Double) {
        nowNanos += (seconds * 1e9).toLong()
    }

    @Test
    fun `allows a burst up to capacity then denies`() {
        val limiter = limiter(capacity = 3, refillPerSecond = 1.0)
        repeat(3) { assertTrue(limiter.tryAcquire("a").allowed, "request ${it + 1} should be within the burst") }
        assertFalse(limiter.tryAcquire("a").allowed)
    }

    @Test
    fun `a denial reports the wait until the next token, at least one second`() {
        val slow = limiter(capacity = 1, refillPerSecond = 0.5)
        assertTrue(slow.tryAcquire("a").allowed)
        assertEquals(2, slow.tryAcquire("a").retryAfterSeconds)

        val fast = limiter(capacity = 1, refillPerSecond = 100.0)
        assertTrue(fast.tryAcquire("b").allowed)
        assertEquals(1, fast.tryAcquire("b").retryAfterSeconds, "sub-second waits still advertise a whole second")
    }

    @Test
    fun `elapsed time refills, capped at capacity`() {
        val limiter = limiter(capacity = 2, refillPerSecond = 1.0)
        repeat(2) { assertTrue(limiter.tryAcquire("a").allowed) }
        assertFalse(limiter.tryAcquire("a").allowed)

        advanceSeconds(1.0)
        assertTrue(limiter.tryAcquire("a").allowed, "one second at 1/s should grant one token")
        assertFalse(limiter.tryAcquire("a").allowed)

        advanceSeconds(60.0)
        repeat(2) { assertTrue(limiter.tryAcquire("a").allowed, "a long gap refills to capacity, not beyond") }
        assertFalse(limiter.tryAcquire("a").allowed)
    }

    @Test
    fun `keys have independent buckets`() {
        val limiter = limiter(capacity = 1, refillPerSecond = 0.1)
        assertTrue(limiter.tryAcquire("a").allowed)
        assertFalse(limiter.tryAcquire("a").allowed)
        assertTrue(limiter.tryAcquire("b").allowed, "one exhausted key must not affect another")
    }

    @Test
    fun `past maxKeys the least recently used bucket is evicted, not the map grown`() {
        val limiter = limiter(capacity = 1, refillPerSecond = 0.001, maxKeys = 2)
        assertTrue(limiter.tryAcquire("a").allowed)
        assertTrue(limiter.tryAcquire("b").allowed)
        assertFalse(limiter.tryAcquire("a").allowed, "touching a keeps it recent, so b is now eldest")

        assertTrue(limiter.tryAcquire("c").allowed, "third key evicts b")
        assertFalse(limiter.tryAcquire("a").allowed, "a's exhausted bucket survived the eviction")
        assertTrue(limiter.tryAcquire("b").allowed, "an evicted key starts a fresh bucket")
    }
}
