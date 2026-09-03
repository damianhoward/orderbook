package com.damianhoward.orderbook.web

import jdk.jfr.consumer.RecordingStream
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * What this process holds outside its heap, by the category that asked for it.
 *
 * The heap is the minority of what a JVM occupies and it is the only part `/metrics` measured. This
 * service's live set is single-digit megabytes inside a 256 MB ceiling while the process itself
 * holds well over a hundred, so every question about how much memory a service needs — what limit
 * its unit wants, whether another one fits on the box, which is growing — was unanswerable from the
 * series. Answering it meant an operator, an SSH session, and a JDK installed on the box to supply
 * `jcmd`. That answers once, for one process, while someone is already looking.
 *
 * The numbers come from the JVM's own native memory accounting, which is off unless the process was
 * started with `-XX:NativeMemoryTracking`. When it is off the events below never fire, so
 * [readings] stays empty and nothing is published: absent rather than zero, because zero would be a
 * claim about the process and absence is a claim about the measurement.
 *
 * **It does not see everything the process occupies.** Only what the JVM allocated through its own
 * tracking is here. Memory taken underneath it — the C allocator's retained arenas, a mapping made
 * by a native library — is invisible to this and shows up only as the difference between the total
 * here and the resident size the host reports. That difference is a real quantity and finding it
 * growing is a finding, not a gap in this file.
 */
data class NativeMemoryUsage(
    val reserved: Long,
    val committed: Long,
)

/**
 * The reading side, so [ProcessMetrics] can be given a fixed answer in a test rather than a JVM.
 */
fun interface NativeMemorySource {
    /** Latest usage per category, empty when nothing is tracking it. */
    fun readings(): Map<String, NativeMemoryUsage>

    companion object {
        /** The source used where native memory is not being tracked, and by every test but its own. */
        val NONE: NativeMemorySource = NativeMemorySource { emptyMap() }
    }
}

/**
 * A [NativeMemorySource] fed by Flight Recorder rather than by attaching to the process.
 *
 * `jcmd VM.native_memory` is the usual way to read this and the wrong one to build an endpoint on.
 * A JVM cannot attach to itself without being started to allow it, an attach forks a second JVM,
 * and `/metrics` here is public and unauthenticated — so a handler that did real work per request
 * would be a way to exhaust a 1 GB box from the internet. The same accounting arrives as JFR events
 * with no attach and no subprocess, which is what makes it publishable at all.
 *
 * The stream keeps the latest value per category and the endpoint reads that map, so a scrape does
 * no work beyond formatting. The period below is the JVM's emission rate rather than the scrape
 * rate; the two are unrelated, and the collector reads this far more slowly than it is produced.
 */
class JfrNativeMemory private constructor(
    private val stream: RecordingStream,
) : NativeMemorySource, AutoCloseable {
    private val latest = ConcurrentHashMap<String, NativeMemoryUsage>()

    override fun readings(): Map<String, NativeMemoryUsage> = latest.toMap()

    override fun close() = stream.close()

    companion object {
        private const val EVENT = "jdk.NativeMemoryUsage"

        /**
         * Starts the stream, or gives back a source that answers nothing.
         *
         * Instrumentation must not decide whether the service runs. Flight Recorder can be absent,
         * refused, or unable to write its repository, and none of those is a reason for an order
         * book to fail to start — so a failure here degrades to publishing no native memory series,
         * which is the same state as running without tracking enabled and is already handled.
         */
        fun startOrNone(): NativeMemorySource =
            try {
                val stream = RecordingStream()
                // Bounded so the repository cannot grow: these events are consumed as they arrive
                // and nothing reads the recording back off disk.
                stream.setMaxAge(Duration.ofMinutes(1))
                stream.enable(EVENT).withPeriod(Duration.ofSeconds(10))
                JfrNativeMemory(stream).also { sink ->
                    stream.onEvent(EVENT) { event ->
                        sink.latest[event.getString("type")] =
                            NativeMemoryUsage(
                                reserved = event.getLong("reserved"),
                                committed = event.getLong("committed"),
                            )
                    }
                    stream.startAsync()
                }
                // Broad on purpose, and the only place in this service that is. The contract being
                // protected is "the service starts", and every failure mode of an optional recorder
                // is equally not a reason to break it. Narrowing this would mean listing the ways
                // Flight Recorder can be unavailable and being wrong about one of them later.
            } catch (e: Exception) {
                System.err.println("native memory tracking unavailable, publishing no native series: $e")
                NativeMemorySource.NONE
            }
    }
}
