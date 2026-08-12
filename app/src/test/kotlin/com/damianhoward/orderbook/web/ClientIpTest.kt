package com.damianhoward.orderbook.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress

class ClientIpTest {
    // Literal addresses — InetAddress.getByName performs no lookup for them.
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val direct: InetAddress = InetAddress.getByName("203.0.113.7")

    @Test
    fun `a non-loopback peer is keyed by its own address, ignoring any forwarded header`() {
        assertEquals("203.0.113.7", ClientIp.of(direct, "198.51.100.1"))
        assertEquals("203.0.113.7", ClientIp.of(direct, null))
    }

    @Test
    fun `a loopback peer is keyed by the rightmost non-loopback forwarded entry`() {
        assertEquals("203.0.113.9", ClientIp.of(loopback, "203.0.113.9"))
        assertEquals("203.0.113.9", ClientIp.of(loopback, "203.0.113.9, 127.0.0.1"))
        assertEquals("203.0.113.9", ClientIp.of(loopback, "10.9.9.9, 203.0.113.9, 127.0.0.1, ::1"))
    }

    @Test
    fun `entries left of the rightmost non-loopback hop are untrusted and ignored`() {
        assertEquals("203.0.113.9", ClientIp.of(loopback, "spoofed-by-client, 203.0.113.9"))
    }

    @Test
    fun `IPv6 loopback hops are skipped like IPv4 ones`() {
        assertEquals("2001:db8::5", ClientIp.of(loopback, "2001:db8::5, 0:0:0:0:0:0:0:1"))
        val ipv6Loopback = InetAddress.getByName("::1")
        assertEquals("203.0.113.9", ClientIp.of(ipv6Loopback, "203.0.113.9"))
    }

    @Test
    fun `an absent, blank, or all-loopback header falls back to the peer address`() {
        assertEquals("127.0.0.1", ClientIp.of(loopback, null))
        assertEquals("127.0.0.1", ClientIp.of(loopback, "  "))
        assertEquals("127.0.0.1", ClientIp.of(loopback, "127.0.0.1, ::1"))
    }
}
