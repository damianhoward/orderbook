package com.damianhoward.orderbook.web

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.RuntimeMXBean
import java.lang.management.ThreadMXBean

/**
 * Process-level series, published alongside the readiness ones rather than instead of them.
 *
 * This is the distinction worth keeping straight against risk-engine and trading-desk, which carry
 * the same class for the opposite reason. Those two cannot render readiness at scrape frequency —
 * one reprices a whole book, the other GETs every upstream — so process metrics are all their
 * `/metrics` has. This service's readiness check is a matching self-test, cheap enough to run per
 * scrape, so [Readiness] renders it and document 17's one-snapshot rule holds here unchanged.
 * Nothing below restates a readiness condition; adding a gauge that did would create a second
 * number free to drift from the first.
 *
 * Heap against its ceiling is the load-bearing pair. Box 1 is 1 GB running four JVMs and the
 * ceilings were set by guess: this service was measured once, by hand, at 11 MB live against a
 * 256 MB ceiling. One reading cannot size a ceiling — a peak can, and a peak needs a series.
 */
class ProcessMetrics(
    private val runtime: RuntimeMXBean = ManagementFactory.getRuntimeMXBean(),
    private val memory: MemoryMXBean = ManagementFactory.getMemoryMXBean(),
    private val threads: ThreadMXBean = ManagementFactory.getThreadMXBean(),
    private val collectors: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans(),
) {
    fun render(): String {
        val out = StringBuilder()

        fun emit(
            name: String,
            help: String,
            type: String,
            samples: List<Pair<String, Any>>,
        ) {
            if (samples.isEmpty()) return
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
            for ((labels, value) in samples) {
                out
                    .append(name)
                    .append(labels)
                    .append(' ')
                    .append(value)
                    .append('\n')
            }
        }

        fun gauge(
            name: String,
            help: String,
            value: Any,
        ) = emit(name, help, "gauge", listOf("" to value))

        gauge(
            "${PREFIX}process_uptime_seconds",
            "Seconds since this process started. A reset is a restart, wanted or not.",
            seconds(runtime.uptime),
        )

        val heap = memory.heapMemoryUsage
        gauge("${PREFIX}jvm_heap_used_bytes", "Heap in use after the last collection this reading saw.", heap.used)
        gauge("${PREFIX}jvm_heap_committed_bytes", "Heap the JVM currently holds from the operating system.", heap.committed)
        // -1 means no ceiling was configured. Publishing it would read as a real limit of minus one
        // byte, and every "used against max" expression built on it would be nonsense.
        if (heap.max >= 0) {
            gauge(
                "${PREFIX}jvm_heap_max_bytes",
                "The configured heap ceiling. Used against this is what the ceilings were guessed at.",
                heap.max,
            )
        }

        gauge("${PREFIX}jvm_threads", "Live threads, daemon and non-daemon.", threads.threadCount)

        // The collector names come from the JVM's own configuration, so the label set is fixed at
        // startup — bounded by configuration rather than by data, which is the rule that keeps a
        // series from being minted per observation.
        emit(
            "${PREFIX}jvm_gc_collections_total",
            "Collections each garbage collector has completed.",
            "counter",
            collectors.map { """{gc=${quote(it.name)}}""" to it.collectionCount },
        )
        emit(
            "${PREFIX}jvm_gc_seconds_total",
            "Seconds each garbage collector has spent collecting.",
            "counter",
            collectors.map { """{gc=${quote(it.name)}}""" to seconds(it.collectionTime) },
        )

        return out.toString()
    }

    // Rendered rather than divided into a Double, so a duration never reaches the endpoint in
    // exponential notation — Prometheus accepts it, humans reading a curl do not.
    private fun seconds(millis: Long): String = "%d.%03d".format(millis / 1000, millis % 1000)

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val PREFIX = "orderbook_"

        /**
         * The Prometheus exposition content type. The version parameter is not decoration: a
         * collector reads it to decide which parser to use.
         */
        const val CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
    }
}
