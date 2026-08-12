package com.damianhoward.orderbook.kafka

import com.damianhoward.orderbook.market.MarketSession
import com.damianhoward.orderbook.market.SeedLiquidity
import com.damianhoward.orderbook.market.SeedOrder
import com.damianhoward.orderbook.market.SubmitCommand
import com.damianhoward.orderbook.market.replay
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.Properties

/**
 * The egress against a real broker: fills and the command log produced by a live [MarketSession]
 * must arrive on their topics, and a fresh book replayed from the consumed command log must equal
 * the live book. A stub cannot fail the way a broker fails (metadata, partitioning, acks), so this
 * runs against the real thing; it skips only where Docker is absent and always runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaEgressIntegrationTest {
    companion object {
        @Container
        @JvmField
        val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
    }

    private val seed =
        SeedLiquidity(
            listOf(
                SeedOrder(Price.of("101.00"), Side.OFFER, 5),
                SeedOrder(Price.of("99.00"), Side.BID, 5),
            ),
        )

    @Test
    fun `a fresh book replayed from the consumed command log equals the live book`() {
        val egress = KafkaMarketEgress.create(kafka.bootstrapServers)
        val symbolEgress = egress.forSymbol("SIM")
        var now = 1_000L
        val live =
            MarketSession(
                seed = seed,
                clock = { now },
                fills = symbolEgress,
                commands = symbolEgress,
                depth = symbolEgress,
            ).use { session ->
                session.submit(Side.BID, Price.of("101.00"), 5)
                now = 2_000L
                session.submit(Side.OFFER, Price.of("100.50"), 4)
                now = 3_000L
                session.submit(Side.BID, Price.of("100.50"), 2)
                session.snapshot()
            }
        // 3 commands + 2 fills (the 101 sweep and the partial at 100.50) + 4 depth snapshots
        // (seeded book + one per submit); the first send also waits out topic auto-creation,
        // so wait for the acks rather than racing them.
        val ackDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (egress.published < 9L && egress.failed == 0L && System.nanoTime() < ackDeadline) {
            Thread.sleep(100)
        }
        egress.close()
        assertEquals(9, egress.published, "the broker should have acknowledged everything, failed=${egress.failed}")

        val fills = consume(KafkaMarketEgress.DEFAULT_FILLS_TOPIC, expected = 2)
        assertEquals(2, fills.size, "two fills printed")
        assertTrue(fills.all { it.key() == "SIM" })
        assertTrue(fills.first().value().contains(""""price":"101.00000000""""), fills.first().value())

        val commands = consume(KafkaMarketEgress.DEFAULT_COMMANDS_TOPIC, expected = 3)
        val replayed = replay(seed, commands.map { parseCommand(it.value()) })
        assertEquals(live, replayed, "replaying the consumed log must reproduce the live book")

        // Latest-value semantics: the newest L2 record is the live book, tape excluded.
        val depths = consume(KafkaMarketEgress.DEFAULT_L2_TOPIC, expected = 4)
        assertEquals(4, depths.size, "seeded book plus one snapshot per submit")
        assertEquals(
            """{"v":1,"symbol":"SIM",""" + live.depthJson().drop(1),
            depths.last().value(),
            "the newest depth record must be the live book",
        )
    }

    private fun consume(
        topic: String,
        expected: Int,
    ): List<ConsumerRecord<String, String>> {
        val props =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "egress-integration-$topic")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            }
        KafkaConsumer(props, StringDeserializer(), StringDeserializer()).use { consumer ->
            consumer.subscribe(listOf(topic))
            val collected = mutableListOf<ConsumerRecord<String, String>>()
            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            while (collected.size < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach { collected.add(it) }
            }
            return collected
        }
    }

    // Test-scoped parser for the egress's own fixed record shape; production consumers own theirs.
    private fun parseCommand(json: String): SubmitCommand {
        fun field(name: String) =
            Regex(""""$name":"?([^,"}]+)"?""").find(json)?.groupValues?.get(1)
                ?: error("missing $name in $json")
        return SubmitCommand(
            side = Side.valueOf(field("side")),
            price = Price.of(field("price")),
            size = field("size").toLong(),
            timeMillis = field("ts").toLong(),
        )
    }
}
