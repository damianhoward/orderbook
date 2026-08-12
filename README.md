# Order Book

[![CI](https://github.com/damianhoward/orderbook/actions/workflows/ci.yml/badge.svg)](https://github.com/damianhoward/orderbook/actions/workflows/ci.yml)
[![CodeQL](https://github.com/damianhoward/orderbook/actions/workflows/codeql.yml/badge.svg)](https://github.com/damianhoward/orderbook/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/damianhoward/orderbook/graph/badge.svg)](https://codecov.io/gh/damianhoward/orderbook)

A small **limit order book** and **price-time-priority matching engine** in Kotlin. Add / modify / remove orders, query the book by side and level, preserving time priority across modifications — then submit crossing orders and watch them match. The book is single-threaded by design; `MarketSession` owns it on one writer thread and serves concurrent callers over a ring buffer.

**▶ Try it live:** https://orderbook.damianhoward.com — pick a real instrument (or type any ticker), submit an order, and watch it match resting liquidity seeded around that instrument's actual last price, printing to the trade tape. Real quotes come from [`market-data`](https://github.com/damianhoward/market-data).

## Problem

Build an `OrderBook` that supports:

1. **Add** an `Order(id, price, side, size)` to the book. Order additions are expected to be the most frequent operation.
2. **Remove** an order by `id`. Removals run at roughly 60% of the add rate.
3. **Modify** the size of an existing order. **Size modifications must not affect time priority.**
4. **Get price** for `(side, level)` — `level=1` returns the best price on that side, `level=2` the second-best, etc.
5. **Get total size** for `(side, level)` — sum of sizes at that price level.
6. **Get orders** for a side, ordered by level then time of arrival.

Sides: `Side.BID` (buy) and `Side.OFFER` (sell), with `'B'` / `'O'` retained as serialization codes via `Side.fromCode(...)`. Best bid = highest price; best offer = lowest price.

## Design

- **Prices are integer ticks.** `Price` is a `@JvmInline value class` over a scaled `Long` (`SCALE = 8` decimal places). Scaled integers keep prices exact — no binary floating-point drift, so two logically equal prices always share a map key — and make comparison a primitive `Long` op. `BigDecimal` is touched only when parsing or formatting a decimal string, never on the matching path.
- **`OrderBook`** holds the data structure and algorithms, with no concurrency control of its own:
  - **`buyOrders`**: `TreeMap<Price, ArrayDeque<Order>>`, reverse ordering — highest bid first.
  - **`sellOrders`**: `TreeMap<Price, ArrayDeque<Order>>`, natural ordering — lowest offer first.
  - **`ordersMap`**: `HashMap<Long, Order>` for O(1) lookup by id (needed for remove / modify).
  - Per-price queues are `ArrayDeque<Order>` — contiguous and cache-friendly, with `addLast` = arrival order = time priority and the head the next to fill.
- **One thread owns the book.** `MarketSession` runs every operation on a single writer thread and callers reach it over an LMAX Disruptor ring buffer, so no lock guards the data structures. See [Concurrency](#concurrency).
- **Time priority preserved on modify**: a size change mutates the resting order **in place** (the `ordersMap` and the queue hold the same `Order`), so it keeps its queue position rather than moving to the tail. This is a simplification: real venues keep queue priority on a size _decrease_ but send a size _increase_ to the back of the queue; here any size change retains its place.
- **Duplicate order ids are rejected at the matcher.** An id names live liquidity, so reusing one has no safe reading: replacing the resting order silently cancels size its owner still believes is working, and a later fill reported against that id is ambiguous between the two orders. `MatchingEngine.submit` checks `OrderBook.contains` before printing any fill and throws `DuplicateOrderIdException`, so a rejected submit leaves the book untouched rather than half-matched. An id becomes free again once nothing rests under it. `addOrder` itself stays a storage primitive and still replaces (so duplicate ids never leave stale orders at old price levels) — callers admitting client-supplied ids guard with `contains`.

## Complexity

| Operation                   | Cost                                                     | Notes                                                                                                              |
| --------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `addOrder`                  | **O(log P)**                                             | `P` = distinct price levels on that side; replacing an existing id also removes its old queue entry                |
| `removeOrder`               | **O(log P + N_p)**                                       | id-lookup O(1); removing from the `ArrayDeque` is O(N) at that price level                                         |
| `modifyOrder`               | **O(1)**                                                 | id-lookup O(1); the map and queue share the `Order`, so the size mutates in place — no price-level scan            |
| `getPrice(side, level)`     | **O(log P)** for level 1, otherwise **O(log P + level)** | `TreeMap.firstKey()` walks the tree to the leftmost node for the best price; higher levels iterate keys from there |
| `getTotalSize(side, level)` | **O(level + N_p)**                                       | sums the `ArrayDeque` at that level                                                                                |
| `getOrders(side)`           | **O(P + N)**                                             | walks all per-price queues                                                                                         |

The remove cost could be O(log P) instead of O(log P + N_p) by tracking each order's position in its `ArrayDeque` with an intrusive index map. Trade-off is more bookkeeping for a rarely-hot path.

## Concurrency

The book is not thread-safe and does not try to be. One thread owns it, runs every read and write on it, and callers reach that thread through an LMAX Disruptor ring buffer — the single-writer principle, which is the design low-latency engines actually use. A lock-free `TreeMap` is not an available alternative; serialising access to a fast structure is.

`MarketSession` is what owns the thread. A caller publishes to a preallocated ring-buffer slot and waits on a per-call result holder until the consumer has run it.

Two details are load-bearing:

- **The hand-off carries a whole session operation, not one book call.** A submit checks capacity, matches, appends to the tape, replenishes a swept side and takes a snapshot. Those steps have to be one indivisible unit — serialising each individual book call instead would let two concurrent submits interleave mid-match and tear it. It is also cheaper: one hand-off per submit rather than one per book operation, and a match makes many.
- **The wait strategy is injectable, and it blocks by default.** A busy-spin strategy is faster under sustained load, but a process holds one session per open symbol and each owns a thread, so spinning would burn a core per idle book — on a shared 1 GB box that is an outage, not a benchmark. `BlockingWaitStrategy` is what the [live site](https://orderbook.damianhoward.com) runs; benchmarks pass `BusySpinWaitStrategy` to measure what that choice gives up.

On the caller's side the wait is a bounded spin, then a park, for the same reason: spinning covers the microseconds a command usually takes, and parking stops an unbounded number of waiting callers from holding cores when the consumer is slow.

## Design decisions

- **Prices are scaled `Long` ticks, not `BigDecimal`.** `BigDecimal` looks like the safe default — arbitrary precision, exact decimal arithmetic — but it's wrong for a hot path: every operation allocates, `compareTo`/`hashCode` walk variable-length internal state, and two numerically-equal `BigDecimal`s with different scale (`1.10` vs `1.100`) aren't `.equals()`-equal, which is a landmine for a map key. `Price`'s scaled `Long` (`SCALE = 8`) is a primitive comparison and an inline value class costs nothing beyond the `Long` itself; equal prices are always bit-identical. `BigDecimal` still exists exactly once, at the string parse/format boundary (`Price.of`, `toString`) — never on the matching path.
- **Book storage is `TreeMap<Price, ArrayDeque<Order>>`, not `ConcurrentHashMap` or an array.** A hash map is rejected outright, not just deprioritised: `getPrice(side, 1)` needs the _best_ price, and a hash map has no ordering, so you'd need a full scan or a parallel sorted index — at which point you've built a worse tree by hand. An array ladder indexed by tick offset is the real alternative, and it buys O(1) best-price access; it was measured and not kept, because it needs a tick size and a price band fixed at construction, and this book's price range is unbounded and sparse. A wide sparse band also makes `getOrders` and deep levels slower than the tree, not faster. Revisit it if a bounded band and best-price tail latency ever matter more than generality.
- **One writer over a ring buffer, chosen by measurement.** Three ways to serialise access were benchmarked against this book: a read/write lock, a single writer fed by a blocking queue, and a single writer fed by a Disruptor ring buffer. The blocking queue never beat the lock — it relocates locking rather than removing it. The ring buffer did, by roughly 1.5× on read-heavy and ~6× on write-heavy contended workloads, because serialising through one thread with no park and no lock beats a read/write lock's cross-core contention even on reads. Uncontended it loses: the hand-off costs more than a near-free read lock. Contention is the regime a matching engine runs in, so the ring buffer ships and the other two are gone rather than kept as alternatives.

## Matching engine

`MatchingEngine` adds price-time-priority matching on top of any `OrderBook`. A submitted order crosses the best opposite levels first, filling the oldest resting order at each level before moving on; each match prints a `Trade` at the **resting** order's price (so price improvement accrues to the taker), and any unfilled remainder rests on the book as a passive limit order.

It drives only the public `add` / `remove` / `modify` / query contract — never the book's internals — so the data structure stays an independently benchmarked component. `Matcher` is the seam: the strategy can be replaced without touching the book.

```kotlin
val engine = MatchingEngine(OrderBook())
engine.submit(Order(1L, Price.of("101"), Side.OFFER, 5))        // rests: nothing to cross
val fills = engine.submit(Order(2L, Price.of("101"), Side.BID, 8))
// fills = [Trade(101.00, size 5, resting=1, incoming=2, BID)]; the remaining 3 rests as the best bid
```

Scope: plain limit orders (cross, then rest the remainder). Richer types — market, IOC/FOK, stop, iceberg — build on this. The [live web front end](https://orderbook.damianhoward.com) wraps the engine in a dependency-free JDK `HttpServer`, pushing book and tape updates to the browser over Server-Sent Events.

## Kafka egress

The match loop never touches Kafka. `MarketSession` runs on one writer thread; each fill, each accepted command, and each depth change crosses a seam (`FillListener` / `CommandListener` / `DepthListener`) that costs the writer a bounded-queue enqueue, and a dedicated egress thread (`KafkaMarketEgress`) drains into one `KafkaProducer` across three topics.

The two channels carry different obligations, so they get different delivery policies. **Fills and commands are durable**: they share one queue (so their relative order holds), each record is sent with a confirmed ack and stays at the head until the broker accepts it — the queue is the retry buffer, so a broker outage shorter than its depth loses nothing, and depth pressure can never evict a fill. Only overflow loses: a full durable queue sheds the newest event, keeping the buffered history contiguous, and counts it under `lost` — the counter that says whether the command log still replays. **Depth is lossy by design**: each snapshot supersedes the last, so a full depth queue sheds the oldest (`dropped`) and sends are fire-and-forget with failures counted. A confirmed send that times out may still land later, so retries make delivery at-least-once — the fill's `execId` is what lets a consumer recognise the copy. Producer timeouts are tightened (`max.block.ms` 5s, `delivery.timeout.ms` 10s) so a dead broker surfaces as counted failures within seconds instead of buffering silently.

Records are versioned JSON keyed by symbol, so per-symbol ordering holds when multiple instruments exist downstream.

**`orderbook.fills`** — one record per fill, the seam downstream consumers subscribe to:

```json
{
  "v": 1,
  "execId": "1720620000000-1",
  "symbol": "SIM",
  "price": "101.00000000",
  "size": 5,
  "makerOrderId": 7,
  "takerOrderId": 9,
  "aggressor": "BID",
  "ts": 1000
}
```

`execId` is the fill's stable identity — epoch of the egress process plus a monotonic sequence —
carried in the payload so a downstream consumer can deduplicate the same economic fill wherever
a copy of the record lands (a redelivery, a dead-letter replay, a republication on another
topic). Stream coordinates identify a _record_; `execId` identifies the _execution_.

**`orderbook.commands`** — the ordered log of accepted submits (a rejected order never reaches it):

```json
{
  "v": 1,
  "symbol": "SIM",
  "side": "BID",
  "price": "101.00000000",
  "size": 5,
  "ts": 1000
}
```

The command log enables deterministic replay: seeding, replenishment, and order ids are all deterministic functions of the seed and the command sequence, so `replay(seed, commands)` rebuilds a fresh session whose snapshot equals the live one — asserted in CI by consuming the log back off a real broker and comparing books. A `lost` count above zero is the signal a log is no longer replayable.

**`orderbook.l2`** — the book's latest depth, published after every change: the seeded book once at startup, then one record per accepted submit, each carrying both sides' aggregated levels with cumulative totals (the same shape the front end renders, minus the tape):

```json
{ "v": 1, "symbol": "SIM", "ts": 1000, "bids": [{ "price": "99.00000000", "size": 10, "cumulative": 10 }], "asks": [...] }
```

L2 is snapshots, not deltas. The egress sheds the oldest event under pressure, and a delta stream cannot survive that — one shed delta corrupts every downstream book permanently — whereas each snapshot supersedes the last, so a drop costs staleness measured in one record, not correctness. Create the topic with `cleanup.policy=compact` and it behaves as a table: the newest record per symbol is the current book.

The egress is off unless the environment wires it:

- `KAFKA_BOOTSTRAP_SERVERS` — setting it enables the egress; unset, no producer exists
- `KAFKA_FILLS_TOPIC` / `KAFKA_COMMANDS_TOPIC` / `KAFKA_L2_TOPIC` — topic overrides (defaults `orderbook.fills`, `orderbook.commands`, `orderbook.l2`)
- The record key is whichever symbol's book produced the event — one shared producer serves every open session (`/api/{symbol}/...`)
- `KAFKA_SASL_USERNAME` / `KAFKA_SASL_PASSWORD` — set together, the producer authenticates over SASL_PLAINTEXT with SCRAM-SHA-256; unset, the connection is plaintext. Setting only one fails at startup rather than silently producing to nowhere.

`KafkaEgressIntegrationTest` exercises all three topics against a real broker (Testcontainers) on every CI run. `MarketSessionBenchmark` measures `submit()` with the egress attached vs absent; the producer I/O runs on the egress thread, so the submit path pays only the enqueues.

## Run

```bash
./gradlew test     # the book's behavioural contract + the session's concurrency stress tests
./gradlew jmh      # JMH micro-benchmarks (book and engine, plus the session hand-off contended)
./gradlew run      # the live order-book + matching web app on http://localhost:8080
```

## Benchmarks

JMH, JDK 25, book pre-populated with 10,000 orders across 50 price levels, on an 8-core development laptop. **Indicative, not publication-grade** — reproduce on your own hardware before drawing conclusions.

### Why one writer over a ring buffer

Three ways to serialise access to this book were measured before one was chosen: a read/write lock, a single writer fed by a blocking queue, and a single writer fed by a Disruptor ring buffer. 8 threads, throughput, higher is better.

| Workload                           |    read/write lock |  blocking queue |           ring buffer |
| ---------------------------------- | -----------------: | --------------: | --------------------: |
| read-heavy (best bid + best offer) |   2719 ± 94 ops/ms | 250 ± 11 ops/ms | **4192 ± 241** ops/ms |
| mixed (~90% reads)                 | 1378 ± 1058 ops/ms | 447 ± 11 ops/ms | **5578 ± 374** ops/ms |
| write-heavy (add + remove)         |   223 ± 135 ops/ms | 168 ± 27 ops/ms |  **1404 ± 74** ops/ms |

The ring buffer won on every contended workload — ~1.5× the lock on read-heavy, ~6× on write-heavy — and with tighter variance (±74 to ±374 against the lock's ±1058 on mixed), so both throughput and stability improved. Serialising through one thread beats concurrent reads under a lock here because the read lock still pays a per-acquisition memory barrier across 8 contending threads, while one owning thread synchronises on nothing at all. The blocking queue never beat the lock: it relocates locking rather than removing it, and pays thread parking and cross-core wakeup on top.

It is not free. Uncontended, the hand-off costs more than a near-free read lock — roughly 854 ns against 493 ns for an `addOrder`+`removeOrder` pair — because spinning has nothing to win when there is no contention to remove. Contention is the regime a matching engine runs in, so the ring buffer ships and the other two were deleted rather than kept as options.

The wait strategy is a separate choice from the mechanism, and the deployed default is `BlockingWaitStrategy`, not the busy-spin one these numbers were taken with. A process holds one session per open symbol and each owns a thread, so spinning would burn a core per idle book on a shared 1 GB host. `SessionHandoffBenchmark` runs both so the size of that trade stays measured rather than assumed.

### Book operations, single-threaded (average time per op)

3×2s warmup + 5×2s measurement, single fork, against a bare book with no session around it.

| Operation                               |   Avg time |
| --------------------------------------- | ---------: |
| `getPrice` best bid, level 1            |  **16 ns** |
| `getPrice` best offer, level 1          |  **18 ns** |
| `getTotalSize` at level 5               | **312 ns** |
| `modifyOrder` (existing id, same price) | **295 ns** |
| `addOrder` + `removeOrder` pair         | **493 ns** |

- Best-price lookup is ~16 ns. `TreeMap.firstKey()` is **O(log P)** — it walks the tree's left spine and the result isn't cached — but with ~50 price levels that's only a handful of pointer hops, so it measures effectively flat. Moving price from `Double` to a `Price(Long)` value class did not regress it.
- The add/remove pair under ~500 ns is a steady-state number — book size is stationary across the window. (A standalone `addOrder` row is omitted: the book grows unboundedly inside the measurement window, so its average mixes many book sizes.)

### Submission latency and allocation

End-to-end `MatchingEngine.submit()` — the path the live site runs — measured in JMH `SampleTime` (the µs-scale regime where the sampling timer is meaningful; the sub-µs raw-book ops above stay `AverageTime`, since ~25 ns of `nanoTime` overhead would swamp a ~16 ns lookup). The `gc` profiler reports allocation per operation.

| `submit()` (10k orders, 50 levels) |    p50 |    p90 |    p99 |   p99.9 |  alloc/op |
| ---------------------------------- | -----: | -----: | -----: | ------: | --------: |
| marketable (fills the top of book) | 800 ns | 900 ns | 1.1 µs |  ~11 µs | **401 B** |
| passive (rests on the book)        | 100 ns | 200 ns | 300 ns | ~2.7 µs | **384 B** |

The hot path is near-allocation-free by design: the matcher peeks the top of book in **O(log P)** (`bestResting`) rather than materialising the side, a partial fill decrements the resting order's remaining size **in place** rather than replacing it, and the per-price queues are cache-friendly `ArrayDeque`s. The few hundred bytes per submit are the incoming order, the detached snapshot handed back to the caller, and — when it crosses — the emitted `Trade`. The p99.9 tail is occasional GC / JIT / safepoint activity, not the algorithm.

An array ladder indexed by tick offset was measured against the `TreeMap` on the same population and came out statistically indistinguishable: `TreeMap.firstKey()` walks only ~6 nodes (`log2 50`) at this depth, already too cheap for an O(1) pointer lookup to beat measurably. It needed a tick size and price band fixed at construction to buy that, so it was not kept — see [Design decisions](#design-decisions).

Reproduce:

```bash
./gradlew jmhJar
java -jar core/build/libs/core-1.0.0-jmh.jar SessionHandoffBenchmark -f 2 -wi 5 -i 8
java -jar core/build/libs/core-1.0.0-jmh.jar MatchingEngineBenchmark -prof gc
```

## Use it

The book itself is single-threaded — one thread owns it. Embed it behind `MarketSession` (which
provides that thread) if more than one thread will reach it.

```kotlin
val book = OrderBook()

book.addOrder(Order(id = 1L, price = Price.of("19"), side = Side.OFFER, size = 8))
book.addOrder(Order(id = 2L, price = Price.of("21"), side = Side.OFFER, size = 16))
book.addOrder(Order(id = 3L, price = Price.of("15"), side = Side.BID,   size = 5))

book.getPrice(Side.OFFER, 1)       // 19.00000000  — best offer
book.getPrice(Side.BID,   1)       // 15.00000000  — best bid
book.getPrice(Side.OFFER, 9)       // null  — fewer than 9 levels exist
book.getTotalSize(Side.OFFER, 1)   // 8

book.modifyOrder(orderId = 1L, size = 12)   // size 8 -> 12, time priority preserved
book.removeOrder(orderId = 2L)
```

Sides are an enum (`Side.BID` / `Side.OFFER`) — typos are a compile error,
not a runtime exception. Prices are exact integer ticks via `Price`, so
`Price.of("3.45")` round-trips without floating-point drift; over-precise
(beyond `SCALE` decimals) or negative prices are rejected at construction, and
non-positive sizes are rejected at `Order` construction — a corrupt order can't
reach the book. `getPrice` returns `Price?`: `null` means "fewer than `level`
price levels on this side", distinct from a legitimate price of zero.

## Stack

- Kotlin 2.3.21 (JVM target 25)
- Java 25 toolchain
- Kafka clients 4.3 (fills egress)
- JUnit Jupiter 6.1
- Hamcrest 3
- Testcontainers 1.21 (real-broker egress test, tests only)
- Gradle 9.6.0

The core is a pure data-structure-and-algorithms exercise; a thin JDK-`HttpServer` front end (no web framework, no DB) wraps the matching engine for the live order book above.

## License

Apache 2.0 — see [LICENSE](LICENSE).
