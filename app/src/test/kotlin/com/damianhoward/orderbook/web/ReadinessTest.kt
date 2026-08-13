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

    @Test
    fun `metrics renders the same verdict as the probe`() {
        // The reason both endpoints render one snapshot: a service that computes "unhealthy" twice
        // can report it twice differently.
        val ready = Readiness({})
        assertTrue(ready.probe().ready)
        assertTrue(ready.metrics().contains("orderbook_ready 1"), ready.metrics())

        val broken = Readiness({ throw IllegalStateException("boom") })
        assertFalse(broken.probe().ready)
        assertTrue(broken.metrics().contains("orderbook_ready 0"), broken.metrics())
    }

    @Test
    fun `metrics publishes the egress counters when a producer is configured`() {
        val metrics = Readiness({}, Counters(published = 12, failed = 1, dropped = 3, lost = 4)).metrics()
        assertTrue(metrics.contains("orderbook_egress_enabled 1"), metrics)
        assertTrue(metrics.contains("orderbook_egress_published_total 12"), metrics)
        assertTrue(metrics.contains("orderbook_egress_failed_total 1"), metrics)
        assertTrue(metrics.contains("orderbook_egress_dropped_total 3"), metrics)
        assertTrue(metrics.contains("orderbook_egress_lost_total 4"), metrics)
    }

    @Test
    fun `metrics omits the counters rather than publishing zeroes when no producer is configured`() {
        // Same distinction the JSON body draws, and the one a rate() would otherwise erase: a
        // counter pinned at zero reads as a working producer that has shipped nothing.
        val metrics = Readiness({}).metrics()
        assertTrue(metrics.contains("orderbook_egress_enabled 0"), metrics)
        assertFalse(metrics.contains("orderbook_egress_published_total"), metrics)
        assertFalse(metrics.contains("orderbook_egress_lost_total"), metrics)
    }

    @Test
    fun `every published series carries its HELP and TYPE`() {
        // Exposition-format validity, checked here rather than assumed: a series without a TYPE is
        // parsed as untyped, so a counter silently loses rate() and increase().
        val body = Readiness({}, Counters(published = 1)).metrics()
        val names =
            body
                .lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.substringBefore(' ') }
                .toList()
        assertTrue(names.isNotEmpty(), body)
        for (name in names) {
            assertTrue(body.contains("# HELP $name "), "$name has no HELP\n$body")
            assertTrue(body.contains("# TYPE $name "), "$name has no TYPE\n$body")
        }
    }

    @Test
    fun `the counters are named for what Prometheus expects a counter to be called`() {
        // The _total suffix is the convention every dashboard and alert expression assumes, and
        // renaming a published series later costs whatever history was already collected.
        val body = Readiness({}, Counters(published = 1)).metrics()
        for (line in body.lineSequence().filter { it.startsWith("# TYPE ") }) {
            val (name, type) = line.removePrefix("# TYPE ").split(' ')
            if (type == "counter") assertTrue(name.endsWith("_total"), "$name is a counter without _total")
        }
    }
}
