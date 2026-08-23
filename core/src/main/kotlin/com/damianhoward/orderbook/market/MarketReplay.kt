package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.view.MarketSnapshot

/**
 * Replays an ordered command log into a fresh session seeded like the original and returns the
 * resulting snapshot. The session's clock is driven from each command's recorded timestamp, so
 * the tape prints with the original times and the snapshot matches the one the live session
 * produced after its last command.
 *
 * Each command is replayed as the party that submitted it, which the log records for exactly this
 * reason: self-match prevention decides whether an order fills or cancels what it met, so replaying
 * a multi-party log under one identity would produce a different book and still look plausible.
 */
fun replay(
    seed: SeedLiquidity,
    commands: List<SubmitCommand>,
    tapeLimit: Int = 30,
): MarketSnapshot {
    var now = 0L
    MarketSession(seed = seed, clock = { now }, tapeLimit = tapeLimit).use { session ->
        commands.forEach { command ->
            now = command.timeMillis
            session.submit(command.side, command.price, command.size, command.owner)
        }
        return session.snapshot()
    }
}
