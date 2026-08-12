package com.damianhoward.orderbook.web

import com.damianhoward.orderbook.market.Market

/**
 * Thrown for a symbol no book can be created for: either it fails basic shape validation, or (see
 * [main]'s registry factory) it shapes up fine but no real quote can be found for it.
 */
class UnknownSymbolException(
    symbol: String,
) : IllegalArgumentException("unknown symbol '$symbol'")

/** A live [Market] plus the [Broadcaster] feeding its SSE stream — the unit [SessionRegistry] owns. */
class ManagedSession(
    val session: Market,
    val broadcaster: Broadcaster,
) : AutoCloseable {
    override fun close() {
        (session as? AutoCloseable)?.close()
        (broadcaster as? AutoCloseable)?.close()
    }
}

/**
 * Lazily creates a [ManagedSession] per symbol via [factory] on first request, and evicts the
 * least-recently-used one once [maxSessions] is exceeded — so free-text symbol entry can't grow
 * memory unboundedly. Backed by an access-ordered [LinkedHashMap]: [sessionFor] both reads and
 * (on a miss) writes, either of which counts as "used" and bumps the entry to the recent end.
 *
 * [factory] runs outside the registry lock — it fetches a live quote, and one slow provider call
 * must not block every other symbol's access. The lock guards only the O(1) map operations.
 */
class SessionRegistry(
    private val maxSessions: Int = 20,
    private val factory: (symbol: String) -> ManagedSession,
) : AutoCloseable {
    private val sessions =
        object : LinkedHashMap<String, ManagedSession>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ManagedSession>): Boolean {
                val evict = size > maxSessions
                if (evict) eldest.value.close()
                return evict
            }
        }

    /** Number of currently open sessions — for tests asserting the eviction cap holds. */
    val size: Int @Synchronized get() = sessions.size

    /** Symbols with a currently open session — for a periodic quote refresh to iterate over. */
    @Synchronized
    fun symbols(): Set<String> = sessions.keys.toSet()

    fun sessionFor(rawSymbol: String): ManagedSession {
        val symbol = normalize(rawSymbol)
        // Fast path under the lock: the get also bumps recency. The lock is held only for O(1) map
        // work, never across [factory].
        synchronized(this) { sessions[symbol] }?.let { return it }
        // Slow path outside the lock: the factory fetches a live quote, and holding the monitor
        // across a slow provider call would block every other symbol's access (and the periodic
        // refresh). A concurrent request for the same new symbol may build in parallel; whoever
        // loses the re-check closes its now-redundant session.
        val created = factory(symbol)
        return synchronized(this) {
            val existing = sessions[symbol]
            if (existing != null) {
                created.close()
                existing
            } else {
                sessions[symbol] = created
                created
            }
        }
    }

    @Synchronized
    override fun close() = sessions.values.forEach { it.close() }

    companion object {
        // Uppercase alnum plus '.' (share classes, e.g. BRK.B), 1-10 characters: enough to reject
        // obvious junk before ever attempting a quote fetch for it. Whether it's a real,
        // listed instrument is checked afterwards, by the factory that actually fetches one.
        private val VALID_SYMBOL = Regex("^[A-Z][A-Z0-9.]{0,9}$")

        fun normalize(raw: String): String {
            val symbol = raw.trim().uppercase()
            if (!VALID_SYMBOL.matches(symbol)) throw UnknownSymbolException(raw)
            return symbol
        }
    }
}
