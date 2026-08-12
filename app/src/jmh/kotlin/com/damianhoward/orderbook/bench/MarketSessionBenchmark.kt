package com.damianhoward.orderbook.bench

import com.damianhoward.orderbook.kafka.KafkaMarketEgress
import com.damianhoward.orderbook.market.CommandListener
import com.damianhoward.orderbook.market.DepthListener
import com.damianhoward.orderbook.market.FillListener
import com.damianhoward.orderbook.market.MarketSession
import com.damianhoward.orderbook.market.SeedLiquidity
import com.damianhoward.orderbook.market.SeedOrder
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

// 10^Price.SCALE — a whole price unit expressed in ticks (as in :core's BenchSupport).
private const val UNIT = 100_000_000L

// Every resting order is the same size, so a same-size aggressor fills exactly one at the top.
private const val RESTING_SIZE = 100L

/**
 * [MarketSession.submit] end-to-end (writer-thread hand-off included) with and without the Kafka
 * egress attached. The claim under test: the egress adds a bounded-queue enqueue per event (the
 * accepted command plus each fill) and nothing else — producer I/O happens on the egress thread.
 * Measured in
 * [Mode.SampleTime] so the tail is visible; the broker is a stub that acknowledges instantly,
 * because the subject is the submit path's overhead, not broker round-trips.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class MarketSessionBenchmark {
    @Param("none", "kafka")
    var egress: String = ""

    private lateinit var session: MarketSession
    private var publisher: KafkaMarketEgress? = null
    private val offerPrice = Price(101L * UNIT)

    @Setup(Level.Iteration)
    fun setup() {
        publisher = if (egress == "kafka") KafkaMarketEgress(DiscardingProducer()).also { it.start() } else null
        session =
            MarketSession(
                seed =
                    SeedLiquidity(
                        listOf(
                            SeedOrder(offerPrice, Side.OFFER, RESTING_SIZE),
                            SeedOrder(Price(99L * UNIT), Side.BID, RESTING_SIZE),
                        ),
                    ),
                fills = publisher ?: FillListener.NONE,
                commands = publisher ?: CommandListener.NONE,
                depth = publisher ?: DepthListener.NONE,
            )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        session.close()
        publisher?.close()
    }

    /** Sweeps the lone seeded offer in full; the session replenishes it, so the book is stationary. */
    @Benchmark
    fun submitCrossing(bh: Blackhole) {
        bh.consume(session.submit(Side.BID, offerPrice, RESTING_SIZE))
    }

    /**
     * Acknowledges instantly and retains nothing — [MockProducer] would accumulate every record
     * in its history list, which over a sampling window is gigabytes of GC noise.
     */
    private class DiscardingProducer : MockProducer<String, String>() {
        private val metadata = RecordMetadata(TopicPartition(KafkaMarketEgress.DEFAULT_FILLS_TOPIC, 0), 0, 0, 0, 0, 0)

        override fun send(
            record: ProducerRecord<String, String>,
            callback: Callback?,
        ): Future<RecordMetadata> {
            callback?.onCompletion(metadata, null)
            return CompletableFuture.completedFuture(metadata)
        }
    }
}
