package com.damianhoward.orderbook.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestOrder {
    @Test
    fun snapshotIsDetachedCopyAtCurrentSize() {
        val order = Order(1L, Price.of("10"), Side.BID, 5)
        order.size = 8
        val snap = order.snapshot()
        assertEquals(8L, snap.size)

        order.size = 3
        assertEquals(8L, snap.size) // detached — a later mutation of the live order doesn't touch it
        assertEquals(order, snap) // ...but still the same order by identity
    }

    @Test
    fun identityIsById() {
        val order = Order(1L, Price.of("10"), Side.BID, 5)
        val sameIdDifferentState = Order(1L, Price.of("99"), Side.OFFER, 1)
        val differentId = Order(2L, Price.of("10"), Side.BID, 5)

        assertTrue(order == order)
        assertEquals(order, sameIdDifferentState)
        assertEquals(order.hashCode(), sameIdDifferentState.hashCode())
        assertFalse(order == differentId)
        assertFalse(order.equals(null))
        assertFalse(order.equals("not an order"))
    }

    @Test
    fun sizeMustStayPositive() {
        val order = Order(1L, Price.of("10"), Side.BID, 5)
        assertThrows(IllegalArgumentException::class.java) { order.size = 0 }
        assertThrows(IllegalArgumentException::class.java) { order.size = -1 }
        assertThrows(IllegalArgumentException::class.java) { Order(2L, Price.of("10"), Side.BID, 0) }
    }

    @Test
    fun toStringCarriesTheFields() {
        val text = Order(7L, Price.of("12"), Side.OFFER, 3).toString()
        assertTrue(text.contains("id=7"))
        assertTrue(text.contains("size=3"))
    }
}
