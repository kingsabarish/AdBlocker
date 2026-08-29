package com.adblocker.vpn

import android.net.VpnService
import android.util.Log
import com.adblocker.data.VpnState
import com.adblocker.filter.BlocklistManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Decides what to do with a DNS query: block it locally (NXDOMAIN-style A 0.0.0.0 response) or
 * forward it to the upstream resolver and relay the answer back.
 *
 * The upstream [DatagramSocket] is created once and excluded from the VPN via [VpnService.protect]
 * so its traffic does not loop back into the tunnel.
 */
class DnsResolver(private val service: VpnService) {
    private val upstream = InetSocketAddress("1.1.1.1", 53)
    private var socket: DatagramSocket? = null
    private val cache = LinkedHashMap<String, ByteArray>(512, 0.75f, true)

    @Synchronized
    private fun socket(): DatagramSocket {
        if (socket == null) {
            socket = DatagramSocket().also {
                it.soTimeout = 3000
                service.protect(it)
            }
        }
        return socket!!
    }

    /** Returns the IPv4/UDP packet to write back to the TUN, or null to drop. */
    fun handle(ip: IpPacket, query: DnsQuery): ByteArray? {
        if (BlocklistManager.isBlocked(query.domain)) {
            VpnState.incrementBlocked()
            Log.d(TAG, "BLOCK ${query.domain}")
            val response = PacketBuilder.buildBlockedResponse(query)
            return PacketBuilder.buildUdp(
                ip.dstIp as Inet4Address,
                ip.srcIp as Inet4Address,
                ip.dstPort,
                ip.srcPort,
                response,
            )
        }

        val key = "${query.domain.lowercase()}:${query.type}"
        val payload = synchronized(cache) { cache[key] } ?: run {
            try {
                val s = socket()
                val out = DatagramPacket(query.raw, query.raw.size, upstream)
                s.send(out)
                val recv = DatagramPacket(ByteArray(4096), 4096)
                s.receive(recv)
                val bytes = recv.data.copyOf(recv.length)
                synchronized(cache) { cache[key] = bytes }
                Log.d(TAG, "FORWARD ok ${query.domain} (${recv.length} bytes)")
                bytes
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "FORWARD timeout ${query.domain}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "FORWARD error ${query.domain}: ${e.message}")
                null
            }
        }
        if (payload == null) return null
        return PacketBuilder.buildUdp(
            ip.dstIp as Inet4Address,
            ip.srcIp as Inet4Address,
            ip.dstPort,
            ip.srcPort,
            payload,
        )
    }

    companion object {
        private const val TAG = "AdBlock/Dns"
    }
}
