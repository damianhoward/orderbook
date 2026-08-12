package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.view.MarketSnapshot

/**
 * Replays an ordered command log into a fresh session seeded like the original and returns the
 * resulting snapshot. The session's clock is driven from each command's recorded timestamp, so
 * the tape prints with the original times and the snapshot matches the one the live session
 * produced after its last command.
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
            session.submit(command.side, command.price, command.size)
        }
        return session.snapshot()
    }
}
