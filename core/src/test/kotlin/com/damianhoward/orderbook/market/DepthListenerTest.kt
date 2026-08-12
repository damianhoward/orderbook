package com.damianhoward.orderbook.market

import com.damianhoward.orderbook.view.MarketSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DepthListenerTest {
    @Test
    fun `tee fans a snapshot out to every listener in order`() {
        val calls = mutableListOf<String>()
        val snapshot = MarketSnapshot.of(emptyList(), emptyList(), emptyList(), timeMillis = 1)

        DepthListener.tee(DepthListener { calls += "first" }, DepthListener { calls += "second" }).onDepth(snapshot)

        assertEquals(listOf("first", "second"), calls)
    }
}
