package com.damianhoward.orderbook.kafka

import com.damianhoward.orderbook.market.SubmitCommand
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.model.Trade
import com.damianhoward.orderbook.view.DepthLevel
import com.damianhoward.orderbook.view.MarketSnapshot
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class KafkaMarketEgressTest {
    private val trade =
        Trade(
            price = Price.of("101.00"),
            size = 5,
            restingOrderId = 7,
            incomingOrderId = 9,
            incomingSide = Side.BID,
        )
    private val command = SubmitCommand(Side.BID, Price.of("101.00"), 5, 1_000L)
    private val snapshot =
        MarketSnapshot(
            timeMillis = 1_000L,
            bids = listOf(DepthLevel(Price.of("99.00"), 10, 10)),
            asks = listOf(DepthLevel(Price.of("101.00"), 5, 5)),
            tape = emptyList(),
        )

    private fun producer(autoComplete: Boolean = true) = MockProducer(autoComplete, null, StringSerializer(), StringSerializer())

    @Test
    fun `publishes a fill as versioned JSON keyed by symbol on the fills topic`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer, executionIds = { "42-1" })
        egress.fill("SIM", trade, 1_000L)
        egress.close()

        val record = producer.history().single()
        assertEquals("orderbook.fills", record.topic())
        assertEquals("SIM", record.key())
        assertEquals(
            """{"v":1,"execId":"42-1","symbol":"SIM","price":"101.00000000","size":5,""" +
                """"makerOrderId":7,"takerOrderId":9,"aggressor":"BID","ts":1000}""",
            record.value(),
        )
        assertEquals(1, egress.published)
    }

    @Test
    fun `each fill carries its own execution id, stamped at enqueue`() {
        val producer = producer()
        var next = 0
        val egress = KafkaMarketEgress(producer, executionIds = { "42-${++next}" })
        egress.fill("SIM", trade, 1_000L)
        egress.fill("SIM", trade, 2_000L)
        egress.close()

        val ids = producer.history().map { Regex(""""execId":"([^"]+)"""").find(it.value())!!.groupValues[1] }
        assertEquals(listOf("42-1", "42-2"), ids)
    }

    @Test
    fun `publishes an accepted command as versioned JSON on the commands topic`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.submit("SIM", command)
        egress.close()

        val record = producer.history().single()
        assertEquals("orderbook.commands", record.topic())
        assertEquals("SIM", record.key())
        assertEquals(
            """{"v":1,"symbol":"SIM","side":"BID","price":"101.00000000","size":5,"ts":1000}""",
            record.value(),
        )
    }

    @Test
    fun `publishes the latest depth as the view layer's book JSON in the egress envelope`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.depth("SIM", snapshot)
        egress.close()

        val record = producer.history().single()
        assertEquals("orderbook.l2", record.topic())
        assertEquals("SIM", record.key())
        assertEquals(
            """{"v":1,"symbol":"SIM","ts":1000,"bids":[{"price":"99.00000000","size":10,"cumulative":10}],""" +
                """"asks":[{"price":"101.00000000","size":5,"cumulative":5}]}""",
            record.value(),
        )
    }

    @Test
    fun `each symbol's records are keyed with its own symbol, not a shared default`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.fill("AAPL", trade, 1_000L)
        egress.fill("MSFT", trade, 1_000L)
        egress.close()

        assertEquals(listOf("AAPL", "MSFT"), producer.history().map { it.key() })
    }

    @Test
    fun `forSymbol hands a MarketSession the plain listener seams tagged with one symbol`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        val symbolEgress = egress.forSymbol("AAPL")
        symbolEgress.onFill(trade, 1_000L)
        symbolEgress.onSubmit(command)
        symbolEgress.onDepth(snapshot)
        egress.close()

        assertTrue(producer.history().all { it.key() == "AAPL" }, "every record should carry the bound symbol")
    }

    @Test
    fun `fills and commands share one durable queue so their relative order holds`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.submit("SIM", command)
        egress.fill("SIM", trade, 1_000L)
        egress.depth("SIM", snapshot)
        egress.submit("SIM", command.copy(size = 3, timeMillis = 2_000L))
        egress.close()

        assertEquals(
            listOf("orderbook.commands", "orderbook.fills", "orderbook.commands"),
            producer.history().map { it.topic() }.filterNot { it == "orderbook.l2" },
            "the command log and the tape must interleave in submission order",
        )
        assertEquals(1, producer.history().count { it.topic() == "orderbook.l2" })
        assertEquals(4, egress.published)
    }

    @Test
    fun `the started egress thread drains events off the caller's thread`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.start()
        egress.fill("SIM", trade, 1_000L)

        val deadline = System.nanoTime() + 5_000_000_000L
        while (producer.history().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(1, producer.history().size, "the egress thread should have drained the fill")
        egress.close()
    }

    @Test
    fun `a full durable queue sheds the newest fill and counts it as lost — the buffered prefix stays intact`() {
        val producer = producer()
        // Not started: nothing drains, so capacity 1 forces the overflow path deterministically.
        val egress = KafkaMarketEgress(producer, durableCapacity = 1)
        egress.fill("SIM", trade, 1L)
        egress.fill("SIM", trade.copy(incomingOrderId = 10), 2L)
        egress.fill("SIM", trade.copy(incomingOrderId = 11), 3L)

        assertEquals(2, egress.lost, "two overflow fills should have been counted lost")
        assertEquals(0, egress.dropped, "durable overflow is loss, not benign depth shedding")
        egress.close()
        val survivor = producer.history().single()
        assertTrue(survivor.value().contains(""""takerOrderId":9"""), "the buffered oldest fill survives, got ${survivor.value()}")
    }

    @Test
    fun `depth pressure can never evict a fill`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer, durableCapacity = 4, depthCapacity = 2)
        egress.fill("SIM", trade, 1L)
        repeat(10) { egress.depth("SIM", snapshot) }
        egress.close()

        assertEquals(1, producer.history().count { it.topic() == "orderbook.fills" }, "the fill must survive the depth burst")
        assertEquals(2, producer.history().count { it.topic() == "orderbook.l2" })
        assertEquals(8, egress.dropped, "stale depth snapshots shed under their own policy")
        assertEquals(0, egress.lost)
    }

    @Test
    fun `a full depth queue drops the oldest snapshot and counts it`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer, depthCapacity = 1)
        egress.depth("SIM", snapshot)
        egress.depth("SIM", snapshot.copy(timeMillis = 2_000L))

        assertEquals(1, egress.dropped)
        egress.close()
        assertTrue(
            producer
                .history()
                .single()
                .value()
                .contains(""""ts":2000"""),
            "the newest snapshot survives — each one supersedes the last",
        )
    }

    @Test
    fun `a durable record is retried until the broker acks — a transient outage loses nothing`() {
        val producer = producer(autoComplete = false)
        val egress =
            KafkaMarketEgress(
                producer,
                confirmTimeout = Duration.ofMillis(2_000),
                sleep = { Thread.sleep(1) },
            )
        egress.start()
        try {
            egress.fill("SIM", trade, 1L)
            awaitTrue("first attempt sent") { producer.history().isNotEmpty() }
            producer.errorNext(RuntimeException("broker down"))
            awaitTrue("failed attempt counted") { egress.failed >= 1 }
            awaitTrue("the same record is re-sent") { producer.history().size >= 2 }
            producer.completeNext()
            awaitTrue("the retry is acknowledged") { egress.published >= 1 }
            assertEquals(0, egress.lost, "a transient failure must not lose the fill")
        } finally {
            while (producer.errorNext(RuntimeException("drain pending"))) Unit
            egress.close()
        }
    }

    @Test
    fun `an unsendable record at close is attempted, then counted lost, never silently discarded`() {
        val producer = producer(autoComplete = false)
        // A flush budget leaves room for one confirmed attempt, which times out against the
        // never-acking producer — counted as a failed send and a lost record.
        val egress = KafkaMarketEgress(producer, shutdownFlush = Duration.ofMillis(50))
        egress.fill("SIM", trade, 1L)
        egress.close()

        assertEquals(1, egress.failed)
        assertEquals(1, egress.lost)
        assertEquals(0, egress.published)
    }

    @Test
    fun `a spent flush budget counts the rest lost without a blocking send, so a dead broker can't stall shutdown`() {
        val producer = producer(autoComplete = false)
        // Zero budget: the flush is already over on entry, so no record is sent — each is counted
        // lost at once rather than blocking on an ack that will never come.
        val egress = KafkaMarketEgress(producer, shutdownFlush = Duration.ZERO)
        egress.fill("SIM", trade, 1L)
        egress.fill("SIM", trade.copy(incomingOrderId = 10), 2L)
        egress.fill("SIM", trade.copy(incomingOrderId = 11), 3L)
        egress.close()

        assertEquals(3, egress.lost, "every unflushed durable record is accounted for")
        assertEquals(0, egress.failed, "no send was attempted once the budget was spent")
        assertEquals(0, egress.published)
        assertTrue(producer.history().isEmpty(), "no record was handed to the producer")
    }

    @Test
    fun `a drain thread still sending at close keeps the queue — the flush never polls behind it`() {
        val producer = producer(autoComplete = false)
        val egress =
            KafkaMarketEgress(
                producer,
                // The drain thread blocks in its confirmed send far longer than close() waits for
                // it, so close() runs with that thread alive and still owning the queue head —
                // the interleaving where a concurrent flush poll makes the two threads disagree
                // about which record the drain thread's own poll removes.
                confirmTimeout = Duration.ofSeconds(5),
                closeTimeout = Duration.ofMillis(50),
                shutdownFlush = Duration.ofMillis(200),
                sleep = { Thread.sleep(1) },
            )
        egress.start()
        egress.fill("SIM", trade, 1L)
        egress.fill("SIM", trade.copy(incomingOrderId = 10), 2L)
        egress.fill("SIM", trade.copy(incomingOrderId = 11), 3L)
        awaitTrue("the drain thread is inside a confirmed send") { producer.history().size == 1 }

        egress.close()

        assertEquals(1, producer.history().size, "the flush must not send behind the blocked drain thread")
        assertEquals(0, egress.failed, "no send was attempted while the drain thread held the queue")
        assertEquals(3, egress.lost, "everything still queued is reported, not silently discarded")
        assertEquals(0, egress.published)
    }

    private fun awaitTrue(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, message)
            Thread.sleep(5)
        }
    }

    @Test
    fun `create builds and closes a real producer without a reachable broker`() {
        // No broker listens here; construction is lazy, and close() must still return promptly.
        val egress = KafkaMarketEgress.create("localhost:59099")
        egress.close()
        assertEquals(0, egress.published)
    }

    @Test
    fun `create with credentials builds and closes a real SASL producer without a reachable broker`() {
        val egress = KafkaMarketEgress.create("localhost:59099", scram = ScramCredentials("user", "pw"))
        egress.close()
        assertEquals(0, egress.published)
    }

    @Test
    fun `without credentials the producer config carries no security settings`() {
        val props = KafkaMarketEgress.producerProperties("localhost:9092", scram = null)
        assertEquals(null, props["security.protocol"])
        assertEquals(null, props["sasl.mechanism"])
        assertEquals(null, props["sasl.jaas.config"])
    }

    @Test
    fun `with credentials the producer authenticates over SASL_PLAINTEXT with SCRAM-SHA-256`() {
        val props = KafkaMarketEgress.producerProperties("localhost:9092", ScramCredentials("orderbook-egress", "s3cret"))
        assertEquals("SASL_PLAINTEXT", props["security.protocol"])
        assertEquals("SCRAM-SHA-256", props["sasl.mechanism"])
        assertEquals(
            """org.apache.kafka.common.security.scram.ScramLoginModule required """ +
                """username="orderbook-egress" password="s3cret";""",
            props["sasl.jaas.config"],
        )
    }

    @Test
    fun `JAAS values escape quotes and backslashes`() {
        val props = KafkaMarketEgress.producerProperties("localhost:9092", ScramCredentials("user", """p"w\d"""))
        assertEquals(
            """org.apache.kafka.common.security.scram.ScramLoginModule required """ +
                """username="user" password="p\"w\\d";""",
            props["sasl.jaas.config"],
        )
    }
}
