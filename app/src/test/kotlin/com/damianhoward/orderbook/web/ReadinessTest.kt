package com.damianhoward.orderbook.web

import com.damianhoward.orderbook.kafka.EgressMetrics
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadinessTest {
    private class Counters(
        override val dropped: Long = 0,
        override val lost: Long = 0,
        override val published: Long = 0,
        override val failed: Long = 0,
    ) : EgressMetrics

    @Test
    fun `a working matching path is ready`() {
        val probe = Readiness({}).probe()
        assertTrue(probe.ready)
        assertTrue(probe.json.contains(""""match":{"ok":true}"""))
    }

    @Test
    fun `a throwing self-check is not ready and does not propagate`() {
        val probe = Readiness({ throw IllegalStateException("boom") }).probe()
        assertFalse(probe.ready)
        assertTrue(probe.json.contains(""""match":{"ok":false}"""))
    }

    @Test
    fun `egress counters are reported when a producer is configured`() {
        val probe = Readiness({}, Counters(published = 12, failed = 1, dropped = 3, lost = 0)).probe()
        assertTrue(probe.json.contains(""""egress":{"enabled":true,"published":12,"failed":1,"dropped":3,"lost":0}"""), probe.json)
    }

    @Test
    fun `egress is absent rather than zero when no producer is configured`() {
        // "enabled":false says nothing shipped because nothing was meant to, which is not the same
        // fact as shipping nothing while trying to.
        assertTrue(Readiness({}).probe().json.contains(""""egress":{"enabled":false}"""))
    }

    @Test
    fun `a lost fill is reported but does not fail readiness`() {
        // The load-bearing decision. lost is monotonic since process start, so failing readiness on
        // it would mean 503 forever until a restart — and the deploy gate would roll back every
        // release after the first incident. It is something to alert on, not a reason this process
        // cannot serve the next request.
        val probe = Readiness({}, Counters(published = 100, lost = 4)).probe()
        assertTrue(probe.ready, "a past gap in the log does not stop this process serving")
        assertTrue(probe.json.contains(""""lost":4"""), "but it is visible to whatever is watching")
    }

    @Test
    fun `a failing matching path still reports the counters`() {
        // A probe that drops its numbers exactly when something is wrong is the wrong way round.
        val probe = Readiness({ throw IllegalStateException("boom") }, Counters(lost = 2)).probe()
        assertFalse(probe.ready)
        assertTrue(probe.json.contains(""""lost":2"""))
    }
}
