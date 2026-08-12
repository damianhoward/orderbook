package com.damianhoward.orderbook.view

import com.damianhoward.orderbook.model.Order
import com.damianhoward.orderbook.model.Price
import com.damianhoward.orderbook.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketSnapshotTest {
    private fun order(
        price: String,
        side: Side,
        size: Long,
        id: Long = 1,
    ) = Order(id, Price.of(price), side, size)

    @Test
    fun `aggregate of nothing is empty`() {
        assertEquals(emptyList<DepthLevel>(), MarketSnapshot.aggregate(emptyList()))
    }

    @Test
    fun `aggregate runs a cumulative total down the levels`() {
        val levels =
            MarketSnapshot.aggregate(
                listOf(
                    order("101.00", Side.OFFER, 5, id = 1),
                    order("101.50", Side.OFFER, 8, id = 2),
                    order("102.00", Side.OFFER, 12, id = 3),
                ),
            )

        assertEquals(listOf(5L, 13L, 25L), levels.map { it.cumulative })
        assertEquals(listOf(5L, 8L, 12L), levels.map { it.size })
        assertEquals(Price.of("101.00"), levels.first().price)
    }

    @Test
    fun `aggregate merges orders sharing a price into one level, preserving order`() {
        val levels =
            MarketSnapshot.aggregate(
                listOf(
                    order("99.50", Side.BID, 6, id = 1),
                    order("99.50", Side.BID, 4, id = 2),
                    order("99.00", Side.BID, 10, id = 3),
                ),
            )

        assertEquals(2, levels.size)
        assertEquals(DepthLevel(Price.of("99.50"), 10, 10), levels[0])
        assertEquals(DepthLevel(Price.of("99.00"), 10, 20), levels[1])
    }

    @Test
    fun `of builds both sides and carries the timestamp`() {
        val snapshot =
            MarketSnapshot.of(
                bids = listOf(order("99.00", Side.BID, 10)),
                asks = listOf(order("101.00", Side.OFFER, 5)),
                tape = listOf(TapeEntry(Price.of("101.00"), 5, Side.BID, 1_700L)),
                timeMillis = 42L,
            )

        assertEquals(42L, snapshot.timeMillis)
        assertEquals(1, snapshot.bids.size)
        assertEquals(1, snapshot.asks.size)
        assertEquals(1, snapshot.tape.size)
    }

    @Test
    fun `toJson emits the wire contract the front end consumes`() {
        val json =
            MarketSnapshot(
                timeMillis = 7L,
                bids = listOf(DepthLevel(Price.of("99.00"), 10, 10)),
                asks = listOf(DepthLevel(Price.of("101.00"), 5, 5)),
                tape = listOf(TapeEntry(Price.of("101.00"), 5, Side.BID, 1_700L)),
            ).toJson()

        assertEquals(
            """{"ts":7,"bids":[{"price":"99.00000000","size":10,"cumulative":10}],""" +
                """"asks":[{"price":"101.00000000","size":5,"cumulative":5}],""" +
                """"tape":[{"price":"101.00000000","size":5,"side":"BID","time":1700}]}""",
            json,
        )
    }

    @Test
    fun `toJson of an empty book is well-formed with empty arrays`() {
        val json = MarketSnapshot(1L, emptyList(), emptyList(), emptyList()).toJson()
        assertEquals("""{"ts":1,"bids":[],"asks":[],"tape":[]}""", json)
        assertTrue(json.startsWith("{") && json.endsWith("}"))
    }

    @Test
    fun `depthJson is the book without the tape`() {
        val snapshot =
            MarketSnapshot(
                timeMillis = 7L,
                bids = listOf(DepthLevel(Price.of("99.00"), 10, 10)),
                asks = listOf(DepthLevel(Price.of("101.00"), 5, 5)),
                tape = listOf(TapeEntry(Price.of("101.00"), 5, Side.BID, 1_700L)),
            )

        assertEquals(
            """{"ts":7,"bids":[{"price":"99.00000000","size":10,"cumulative":10}],""" +
                """"asks":[{"price":"101.00000000","size":5,"cumulative":5}]}""",
            snapshot.depthJson(),
        )
    }
}
