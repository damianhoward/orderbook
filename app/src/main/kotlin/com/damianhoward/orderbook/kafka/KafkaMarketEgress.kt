package com.damianhoward.orderbook.kafka

import com.damianhoward.orderbook.market.CommandListener
import com.damianhoward.orderbook.market.DepthListener
import com.damianhoward.orderbook.market.FillListener
import com.damianhoward.orderbook.market.SubmitCommand
import com.damianhoward.orderbook.model.Trade
import com.damianhoward.orderbook.view.MarketSnapshot
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Kafka egress for the market's event stream: fills on one topic, the accepted-command log on a
 * second, and the book's latest depth on a third. Never on the match path — the listener
 * callbacks run on the market's writer thread and only enqueue; a dedicated egress thread
 * drains into the [Producer].
 *
 * The two channels carry different obligations, so they get different delivery policies:
 *
 * - **Durable** (fills and commands, one queue so their relative order holds): each record is
 *   sent with a confirmed, blocking ack and stays at the head of the queue until the broker
 *   accepts it — the queue itself is the retry buffer, so a broker outage shorter than the
 *   buffer's depth loses nothing. Depth pressure can never evict a fill. Only overflow loses:
 *   a full durable queue sheds the *newest* event (the buffered prefix stays contiguous and
 *   ordered) and counts it under [lost] — the alarming counter, and the one that says whether
 *   the command log still replays.
 * - **Lossy** (depth): each snapshot supersedes the last, so the queue sheds the *oldest* on
 *   pressure ([dropped]) and sends are fire-and-forget with failures counted.
 *
 * A confirmed send that times out may still land later, so a retry can double-publish the same
 * record — delivery is at-least-once, and each fill's payload `execId` ([ExecutionIds]) is what
 * lets a consumer recognise the copy. One producer serves every symbol's `MarketSession` via
 * [forSymbol]; records are versioned JSON keyed by symbol, so per-symbol ordering holds across
 * instruments.
 *
 * [sleep] paces the durable retry loop and is injectable so tests drive it without real waiting.
 */
class KafkaMarketEgress(
    private val producer: Producer<String, String>,
    private val fillsTopic: String = DEFAULT_FILLS_TOPIC,
    private val commandsTopic: String = DEFAULT_COMMANDS_TOPIC,
    private val l2Topic: String = DEFAULT_L2_TOPIC,
    durableCapacity: Int = DEFAULT_DURABLE_CAPACITY,
    depthCapacity: Int = DEFAULT_DEPTH_CAPACITY,
    private val confirmTimeout: Duration = DEFAULT_CONFIRM_TIMEOUT,
    private val retryBackoff: Duration = DEFAULT_RETRY_BACKOFF,
    private val shutdownFlush: Duration = DEFAULT_SHUTDOWN_FLUSH,
    private val closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT,
    private val executionIds: () -> String = ExecutionIds()::next,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it) },
) : AutoCloseable,
    EgressMetrics {
    private sealed interface Pending

    private data class PendingFill(
        val symbol: String,
        val trade: Trade,
        val timeMillis: Long,
        val execId: String,
    ) : Pending

    private data class PendingCommand(
        val symbol: String,
        val command: SubmitCommand,
    ) : Pending

    private data class PendingDepth(
        val symbol: String,
        val snapshot: MarketSnapshot,
    )

    private val durable = ArrayBlockingQueue<Pending>(durableCapacity)
    private val depths = ArrayBlockingQueue<PendingDepth>(depthCapacity)
    private val egress = Thread(::drain, "kafka-market-egress").apply { isDaemon = true }
    private val droppedCount = AtomicLong()
    private val lostCount = AtomicLong()
    private val publishedCount = AtomicLong()
    private val failedCount = AtomicLong()

    @Volatile
    private var running = false

    /** Depth snapshots shed under pressure — each one superseded by the next, so a gap is benign. */
    override val dropped: Long get() = droppedCount.get()

    /** Fills or commands shed because the durable queue overflowed — the log has a real gap. */
    override val lost: Long get() = lostCount.get()

    /** Records acknowledged by the broker, across all topics. */
    override val published: Long get() = publishedCount.get()

    /** Send attempts that completed with an error (broker unreachable, timeout). */
    override val failed: Long get() = failedCount.get()

    /** Starts the egress thread. Separate from construction so tests can exercise a stopped queue. */
    fun start() {
        running = true
        egress.start()
    }

    fun fill(
        symbol: String,
        trade: Trade,
        timeMillis: Long,
    ) = enqueueDurable(PendingFill(symbol, trade, timeMillis, executionIds()))

    fun submit(
        symbol: String,
        command: SubmitCommand,
    ) = enqueueDurable(PendingCommand(symbol, command))

    fun depth(
        symbol: String,
        snapshot: MarketSnapshot,
    ) {
        // Drop-oldest, never block: shedding the stalest snapshot keeps the stream current and
        // the writer thread unblocked. The loop resolves the race with a concurrent drain.
        val event = PendingDepth(symbol, snapshot)
        while (!depths.offer(event)) {
            if (depths.poll() != null) droppedCount.incrementAndGet()
        }
    }

    /** The listener seam a single symbol's [MarketSession] is wired to. */
    fun forSymbol(symbol: String): SymbolEgress = SymbolEgress(symbol, this)

    private fun enqueueDurable(event: Pending) {
        // Never block the writer and never evict buffered history: overflow sheds the newest
        // event so what is already queued stays a contiguous, ordered prefix — and is counted.
        if (!durable.offer(event)) lostCount.incrementAndGet()
    }

    /**
     * Stops the egress thread, flushes what is still queued — durable records with confirmed
     * sends, depth best-effort — and closes the producer. The durable flush is bounded by
     * [shutdownFlush]: a healthy broker drains the backlog well within it, but a broker that is
     * down at shutdown can't hold the process hostage — each send is capped at the time left in
     * the budget, and once it is spent every remaining durable record is counted [lost] rather
     * than attempted. Never interrupts: an interrupt landing inside `producer.send()` kills the
     * send and loses the event; the drain loop notices [running] within its idle interval
     * instead, and `producer.close` waits for in-flight acks.
     *
     * The flush only runs once the drain thread has actually stopped. [closeTimeout] is shorter
     * than [confirmTimeout], so the join can return with the drain thread still blocked inside a
     * confirmed send — and that thread has `peek`ed the head without removing it. Flushing anyway
     * would put two threads on the same queue: the drain thread's `poll` on success removes
     * whatever is at the head *now*, which after a concurrent flush poll is a different record
     * that nobody sent, discarded without being counted. So a still-running drain thread keeps
     * ownership of the queue and the remainder is counted [lost] instead.
     */
    override fun close() {
        running = false
        egress.join(closeTimeout.toMillis())
        if (egress.isAlive) countRemainderLost() else flushDurable()
        while (true) sendAsync(depthRecord(depths.poll() ?: break))
        producer.close(closeTimeout)
    }

    private fun flushDurable() {
        val deadline = System.nanoTime() + shutdownFlush.toNanos()
        while (true) {
            val event = durable.poll() ?: break
            // Cap each send at the time left, so the whole flush can't outlast the budget; once it
            // is gone, the process is exiting and an unsent record is honestly lost, not discarded.
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0 || !sendConfirmed(event, Duration.ofNanos(remaining))) lostCount.incrementAndGet()
        }
    }

    // Reports what the flush is declining to send rather than removing it, so the drain thread's
    // own head stays where that thread expects it. The record it is mid-send may still land and be
    // counted [published], which makes [lost] an upper bound by at most one at a hard shutdown —
    // an over-report of a shutdown the broker was already failing, and the safe direction to err
    // for a counter whose job is to say the log has a gap.
    private fun countRemainderLost() {
        lostCount.addAndGet(durable.size.toLong())
    }

    private fun drain() {
        try {
            while (running) {
                while (true) sendAsync(depthRecord(depths.poll() ?: break))
                val head = durable.peek()
                when {
                    head == null -> Thread.sleep(IDLE_INTERVAL_MILLIS)
                    sendConfirmed(head) -> durable.poll()
                    else -> sleep(retryBackoff)
                }
            }
        } catch (_: InterruptedException) {
            // Nothing interrupts this thread on purpose; treat a stray interrupt as shutdown.
        }
    }

    /** True when the broker acknowledged the record; a failed attempt is counted and retried by the caller. */
    private fun sendConfirmed(
        event: Pending,
        timeout: Duration = confirmTimeout,
    ): Boolean =
        try {
            producer.send(durableRecord(event)).get(timeout.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
            publishedCount.incrementAndGet()
            true
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            failedCount.incrementAndGet()
            false
        }

    private fun sendAsync(record: ProducerRecord<String, String>) {
        try {
            producer.send(record) { _, exception ->
                if (exception == null) publishedCount.incrementAndGet() else failedCount.incrementAndGet()
            }
        } catch (_: RuntimeException) {
            // send() can also fail synchronously (metadata timeout, serialization); a broken
            // egress must count the loss and keep draining, never die silently.
            failedCount.incrementAndGet()
        }
    }

    private fun durableRecord(event: Pending): ProducerRecord<String, String> =
        when (event) {
            is PendingFill -> ProducerRecord(fillsTopic, event.symbol, fillJson(event))
            is PendingCommand -> ProducerRecord(commandsTopic, event.symbol, commandJson(event))
        }

    private fun depthRecord(pending: PendingDepth): ProducerRecord<String, String> =
        ProducerRecord(l2Topic, pending.symbol, depthJson(pending))

    private fun fillJson(fill: PendingFill): String {
        val trade = fill.trade
        return """{"v":1,"execId":${quote(fill.execId)},"symbol":${quote(fill.symbol)},""" +
            """"price":${quote(trade.price.toString())},"size":${trade.size},""" +
            """"makerOrderId":${trade.restingOrderId},"takerOrderId":${trade.incomingOrderId},""" +
            """"aggressor":${quote(trade.incomingSide.name)},"ts":${fill.timeMillis}}"""
    }

    private fun commandJson(pending: PendingCommand): String {
        val command = pending.command
        return """{"v":1,"symbol":${quote(pending.symbol)},"side":${quote(command.side.name)},""" +
            """"price":${quote(command.price.toString())},"size":${command.size},"ts":${command.timeMillis}}"""
    }

    // The book body comes from the view layer's serialisation; the egress adds only its envelope.
    private fun depthJson(pending: PendingDepth): String =
        """{"v":1,"symbol":${quote(pending.symbol)},""" + pending.snapshot.depthJson().drop(1)

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        const val DEFAULT_FILLS_TOPIC = "orderbook.fills"
        const val DEFAULT_COMMANDS_TOPIC = "orderbook.commands"
        const val DEFAULT_L2_TOPIC = "orderbook.l2"
        const val DEFAULT_DURABLE_CAPACITY = 4096
        const val DEFAULT_DEPTH_CAPACITY = 4096

        /** Longer than `delivery.timeout.ms`, so a send resolves rather than racing its own ack. */
        val DEFAULT_CONFIRM_TIMEOUT: Duration = Duration.ofSeconds(15)
        val DEFAULT_RETRY_BACKOFF: Duration = Duration.ofSeconds(1)

        /** Bounds the durable flush at shutdown: long enough to drain a backlog against a healthy
         * broker, short enough that a dead broker can't stall the process past it. */
        val DEFAULT_SHUTDOWN_FLUSH: Duration = Duration.ofSeconds(5)
        private const val IDLE_INTERVAL_MILLIS = 100L
        val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** A started egress over a real [KafkaProducer]. The timeouts are tightened from the
         * defaults so a dead broker surfaces as counted failures within seconds instead of
         * buffering silently for two minutes. With [scram] the producer authenticates over
         * SASL_PLAINTEXT/SCRAM-SHA-256; without it the connection is unauthenticated plaintext. */
        fun create(
            bootstrapServers: String,
            fillsTopic: String = DEFAULT_FILLS_TOPIC,
            commandsTopic: String = DEFAULT_COMMANDS_TOPIC,
            l2Topic: String = DEFAULT_L2_TOPIC,
            scram: ScramCredentials? = null,
        ): KafkaMarketEgress {
            val producer = KafkaProducer(producerProperties(bootstrapServers, scram), StringSerializer(), StringSerializer())
            return KafkaMarketEgress(producer, fillsTopic, commandsTopic, l2Topic).also { it.start() }
        }

        internal fun producerProperties(
            bootstrapServers: String,
            scram: ScramCredentials?,
        ): Properties =
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ProducerConfig.CLIENT_ID_CONFIG, "orderbook-egress")
                put(ProducerConfig.LINGER_MS_CONFIG, 5)
                put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000)
                put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000)
                put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000)
                // 8 MB, not the 32 MB default — the producer lives inside a 256 MB live-server heap.
                put(ProducerConfig.BUFFER_MEMORY_CONFIG, 8L * 1024 * 1024)
                if (scram != null) {
                    put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
                    put(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-256")
                    put(
                        SaslConfigs.SASL_JAAS_CONFIG,
                        "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                            "username=${jaasQuote(scram.username)} password=${jaasQuote(scram.password)};",
                    )
                }
            }

        // JAAS values are double-quoted strings; escape the two characters that break out of one.
        private fun jaasQuote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/**
 * The plain [FillListener]/[CommandListener]/[DepthListener] seam a symbol-agnostic
 * [com.damianhoward.orderbook.market.MarketSession] is wired to — closes over one symbol so
 * the shared [egress] tags every record with it. Obtain one via [KafkaMarketEgress.forSymbol].
 */
class SymbolEgress(
    private val symbol: String,
    private val egress: KafkaMarketEgress,
) : FillListener,
    CommandListener,
    DepthListener {
    override fun onFill(
        trade: Trade,
        timeMillis: Long,
    ) = egress.fill(symbol, trade, timeMillis)

    override fun onSubmit(command: SubmitCommand) = egress.submit(symbol, command)

    override fun onDepth(snapshot: MarketSnapshot) = egress.depth(symbol, snapshot)
}
