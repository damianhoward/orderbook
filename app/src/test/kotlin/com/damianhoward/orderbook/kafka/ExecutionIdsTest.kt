package com.damianhoward.orderbook.kafka

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ExecutionIdsTest {
    @Test
    fun `ids are the epoch plus a monotonic sequence`() {
        val ids = ExecutionIds(epochMillis = 1_720_620_000_000)
        assertEquals("1720620000000-1", ids.next())
        assertEquals("1720620000000-2", ids.next())
    }

    @Test
    fun `concurrent callers never receive the same id`() {
        val ids = ExecutionIds()
        val threads = 8
        val perThread = 1_000
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val futures =
            (1..threads).map {
                executor.submit<List<String>> {
                    start.await()
                    (1..perThread).map { ids.next() }
                }
            }
        start.countDown()
        val all = futures.flatMap { it.get() }
        executor.shutdown()

        assertEquals(threads * perThread, all.toSet().size, "every id must be unique under contention")
        assertTrue(all.all { it.matches(Regex("\\d+-\\d+")) })
    }
}
