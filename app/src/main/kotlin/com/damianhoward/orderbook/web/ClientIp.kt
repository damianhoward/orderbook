package com.damianhoward.orderbook.web

import java.net.InetAddress

/**
 * Resolves the address rate limiting keys on. `X-Forwarded-For` is believed only when the
 * connecting peer is loopback — i.e. the request arrived through the local reverse proxy, which
 * always appends the address it accepted from. A direct (non-loopback) peer could write anything
 * into the header to rotate keys or impersonate another client, so its own address is the only
 * trustworthy key it gets.
 *
 * Within a trusted header the entries are scanned right to left, skipping loopback hops (the desk
 * proxy appends itself before forwarding); the rightmost non-loopback entry is the address the
 * local proxy actually accepted, and everything left of it is client-supplied and untrusted.
 * Entries are compared textually — resolving candidate strings here would hand a client a DNS
 * lookup per request.
 */
object ClientIp {
    fun of(
        peer: InetAddress,
        forwardedFor: String?,
    ): String {
        val peerAddress = peer.hostAddress
        if (!peer.isLoopbackAddress || forwardedFor.isNullOrBlank()) return peerAddress
        return forwardedFor
            .split(",")
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && !isLoopback(it) }
            ?: peerAddress
    }

    private fun isLoopback(address: String): Boolean = address.startsWith("127.") || address == "::1" || address == "0:0:0:0:0:0:0:1"
}
