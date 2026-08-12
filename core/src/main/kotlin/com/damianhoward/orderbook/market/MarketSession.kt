package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.book.OrderBook
import com.damianhoward.orderbook.engine.Matcher
import com.damianhoward.orderbook.engine.MatchingEngine
import com.damianhoward.orderbook.model.Order
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import com.damianhoward.orderbook.view.MarketSnapshot
import com.damianhoward.orderbook.view.TapeEntry
import com.lmax.disruptor.BlockingWaitStrategy
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.WaitStrategy
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Fills produced by an order, plus the resulting book. */
data class SubmitOutcome(
    val matched: Int,
    val snapshot: MarketSnapshot,
)

/**
 * A submit was rejected because the book already holds [capacity] resting orders. The book only
 * ever grows from non-marketable limits, so without a ceiling one symbol's book is unbounded
 * memory; the book is resettable, so a saturated one recovers on session eviction or restart.
 */
class BookAtCapacityException(
    val capacity: Int,
) : RuntimeException("order book at capacity: $capacity resting orders")

/** A live market: accept orders, expose the book. The seam the web layer depends on, not the impl. */
interface Market {
    fun submit(
        side: Side,
        price: Price,
        size: Long,
    ): SubmitOutcome

    fun snapshot(): MarketSnapshot
}

/**
 * The default [Market]: a shared session over an [OrderBook] + [MatchingEngine].
 *
 * Neither the book nor the engine is thread-safe, so one owning thread runs every operation
 * serially — the single-writer principle — and callers hand work to it over an LMAX Disruptor ring
 * buffer. A caller publishes to a preallocated slot and waits on a per-call result holder until the
 * consumer has run it; no lock guards the book on either side.
 *
 * The hand-off carries a *whole session operation*, not an individual book call. [submit] checks
 * capacity, matches, appends to the tape, replenishes a swept side and takes a snapshot, and those
 * steps have to be one indivisible unit — serialising each book call instead would let two concurrent
 * submits interleave mid-match. It is also the cheaper arrangement: one hand-off per submit rather
 * than one per book operation, and a match makes many.
 *
 * Keeps a bounded tape and replenishes a swept side so the shared book never looks empty.
 */
class MarketSession(
    private val seed: SeedLiquidity = SeedLiquidity.default(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val tapeLimit: Int = 30,
    private val maxRestingOrders: Int = DEFAULT_MAX_RESTING_ORDERS,
    private val fills: FillListener = FillListener.NONE,
    private val commands: CommandListener = CommandListener.NONE,
    private val depth: DepthListener = DepthListener.NONE,
    // Blocking by default, deliberately. A busy-spin strategy is faster under sustained load, but a
    // process holds one session per open symbol and each owns a thread, so spinning would burn every
    // core the host has on books that are idle most of the time. Benchmarks pass BusySpinWaitStrategy.
    waitStrategy: WaitStrategy = BlockingWaitStrategy(),
) : Market,
    AutoCloseable {
    private val book = OrderBook()
    private val engine: Matcher = MatchingEngine(book)
    private val nextId = AtomicLong(1000)
    private val tape = ArrayDeque<TapeEntry>()
    private val disruptor =
        Disruptor(
            EventFactory { Slot() },
            RING_BUFFER_SIZE,
            ThreadFactory { runnable -> Thread(runnable, "market-session").apply { isDaemon = true } },
            ProducerType.MULTI,
            waitStrategy,
        )
    private val ringBuffer =
        disruptor.let {
            it.handleEventsWith(CommandHandler())
            it.start()
            it.ringBuffer
        }

    init {
        onWriter {
            place(seed.orders)
            depth.onDepth(snapshotAt(clock()))
        }
    }

    override fun submit(
        side: Side,
        price: Price,
        size: Long,
    ): SubmitOutcome =
        onWriter {
            val now = clock()
            // Reject before matching so nothing is mutated: at the cap even a marketable order is
            // turned away rather than left to rest its remainder. The book only grows from resting
            // limits, and the cap sits far above any legitimate hand-driven use.
            if (book.size >= maxRestingOrders) throw BookAtCapacityException(maxRestingOrders)
            val trades = engine.submit(Order(nextId.getAndIncrement(), price, side, size))
            trades.forEach { trade ->
                tape.addFirst(TapeEntry(trade.price, trade.size, trade.incomingSide, now))
                if (tape.size > tapeLimit) tape.removeLast()
                fills.onFill(trade, now)
            }
            replenishEmptySides()
            // Logged only once the submit has been applied: a rejected order threw above and
            // never reaches the command log, so a replayed log contains no failing submits.
            commands.onSubmit(SubmitCommand(side, price, size, now))
            val snapshot = snapshotAt(now)
            depth.onDepth(snapshot)
            SubmitOutcome(trades.size, snapshot)
        }

    override fun snapshot(): MarketSnapshot = onWriter { snapshotAt(clock()) }

    override fun close() {
        disruptor.shutdown()
    }

    private fun snapshotAt(now: Long): MarketSnapshot =
        MarketSnapshot.of(book.getOrders(Side.BID), book.getOrders(Side.OFFER), tape.toList(), now)

    private fun replenishEmptySides() {
        if (book.bestResting(Side.OFFER) == null) place(seed.forSide(Side.OFFER))
        if (book.bestResting(Side.BID) == null) place(seed.forSide(Side.BID))
    }

    private fun place(orders: List<SeedOrder>) =
        orders.forEach { book.addOrder(Order(nextId.getAndIncrement(), it.price, it.side, it.size)) }

    /**
     * Runs [block] on the owning thread and returns its result. The block's own exception propagates
     * unwrapped, so the web layer's error mapping still recognises e.g. `Order`'s size validation.
     */
    private fun <T> onWriter(block: () -> T): T {
        // Allocated per call, and deliberately NOT stored in the ring-buffer slot. The slot is a
        // transient handle: once the handler releases it, Disruptor may hand that same slot object to
        // another producer, which would race a caller still reading its result out of it. Only this
        // call's thread ever references its own Command.
        val command = Command(block)
        val sequence = ringBuffer.next()
        try {
            ringBuffer.get(sequence).command = command
        } finally {
            ringBuffer.publish(sequence)
        }

        awaitCompletion(command)
        command.error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return command.result as T
    }

    /**
     * Spin briefly, then park. The consumer usually completes a command in well under the spin
     * budget, and spinning avoids the microseconds a park/unpark pair costs there. Parking after that
     * matters more: a host runs one session per open symbol, so an unbounded spin would burn a core
     * per waiting caller whenever the consumer is slow or descheduled.
     */
    private fun awaitCompletion(command: Command) {
        var spins = 0
        while (!command.completed) {
            if (spins < SPINS_BEFORE_PARK) {
                Thread.onSpinWait()
                spins++
            } else {
                command.waiter = Thread.currentThread()
                // Re-check after publishing the waiter: without it the handler could complete between
                // the loop test and the park, and its unpark would arrive before this thread slept.
                if (!command.completed) LockSupport.park(command)
                command.waiter = null
            }
        }
    }

    private class Command(
        val task: () -> Any?,
    ) {
        var result: Any? = null
        var error: Throwable? = null

        /** The parked caller, if it stopped spinning. Volatile: the handler reads it from its thread. */
        @Volatile
        var waiter: Thread? = null

        // Written last, after result and error, so its volatile write is the happens-before edge a
        // waiting caller relies on to read those safely once it observes true.
        @Volatile
        var completed: Boolean = false
    }

    /** The reused ring-buffer slot: a handle to the per-call [Command], never the command state itself. */
    private class Slot {
        var command: Command? = null
    }

    private class CommandHandler : EventHandler<Slot> {
        override fun onEvent(
            event: Slot,
            sequence: Long,
            endOfBatch: Boolean,
        ) {
            val command = event.command!!
            event.command = null
            try {
                command.result = command.task.invoke()
            } catch (t: Throwable) {
                command.error = t
            } finally {
                command.completed = true
                command.waiter?.let(LockSupport::unpark)
            }
        }
    }

    companion object {
        /** Resting-order ceiling per book. Far above hand-driven use; a guard against unbounded growth. */
        const val DEFAULT_MAX_RESTING_ORDERS = 1000

        private const val RING_BUFFER_SIZE = 1024

        /** Chosen to cover a typical command's latency without letting an idle caller hold a core. */
        private const val SPINS_BEFORE_PARK = 256
    }
}
