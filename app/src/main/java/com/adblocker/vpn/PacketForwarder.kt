package com.adblocker.vpn

import android.net.VpnService
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes non-DNS IP packets from the TUN through protected sockets so all traffic flows
 * through the VPN. DNS (UDP port 53) is handled by [DnsResolver]; everything else is relayed.
 */
class PacketForwarder(private val service: VpnService) {
    private val tcpConnections = ConcurrentHashMap<String, SocketChannel>()
    private val udpSockets = ConcurrentHashMap<String, DatagramChannel>()

    fun hasTcpConnection(key: String): Boolean = tcpConnections.containsKey(key)

    fun getOrCreateTcp(key: String, dstIp: Inet4Address, dstPort: Int): SocketChannel? {
        return tcpConnections[key] ?: runCatching {
            val ch = SocketChannel.open()
            service.protect(ch.socket())
            ch.connect(InetSocketAddress(dstIp, dstPort))
            ch.configureBlocking(false)
            tcpConnections[key] = ch
            ch
        }.getOrNull()
    }

    fun forwardTcpPayload(key: String, payload: ByteArray) {
        tcpConnections[key]?.let { ch ->
            runCatching {
                val buf = ByteBuffer.wrap(payload)
                while (buf.hasRemaining()) ch.write(buf)
            }
        }
    }

    fun readTcpResponses(): List<Pair<String, ByteArray>> {
        val results = mutableListOf<Pair<String, ByteArray>>()
        val iterator = tcpConnections.entries.iterator()
        while (iterator.hasNext()) {
            val (key, ch) = iterator.next()
            if (!ch.isOpen) { iterator.remove(); continue }
            try {
                val buf = ByteBuffer.allocate(16384)
                val n = ch.read(buf)
                if (n > 0) {
                    buf.flip()
                    val data = ByteArray(n)
                    buf.get(data)
                    results.add(key to data)
                } else if (n == -1) {
                    ch.close()
                    iterator.remove()
                }
            } catch (_: Exception) {
                ch.close()
                iterator.remove()
            }
        }
        return results
    }

    fun getOrCreateUdp(key: String, dstIp: Inet4Address, dstPort: Int): DatagramChannel? {
        return udpSockets[key] ?: runCatching {
            val ch = DatagramChannel.open()
            service.protect(ch.socket())
            ch.connect(InetSocketAddress(dstIp, dstPort))
            ch.configureBlocking(false)
            udpSockets[key] = ch
            ch
        }.getOrNull()
    }

    fun forwardUdpPayload(key: String, payload: ByteArray) {
        udpSockets[key]?.let { ch ->
            runCatching { ch.send(ByteBuffer.wrap(payload), ch.remoteAddress) }
        }
    }

    fun readUdpResponses(): List<Pair<String, ByteArray>> {
        val results = mutableListOf<Pair<String, ByteArray>>()
        val iterator = udpSockets.entries.iterator()
        while (iterator.hasNext()) {
            val (key, ch) = iterator.next()
            if (!ch.isOpen) { iterator.remove(); continue }
            try {
                val buf = ByteBuffer.allocate(4096)
                val n = ch.read(buf)
                if (n > 0) {
                    buf.flip()
                    val data = ByteArray(n)
                    buf.get(data)
                    results.add(key to data)
                }
            } catch (_: Exception) {
                ch.close()
                iterator.remove()
            }
        }
        return results
    }

    fun closeAll() {
        tcpConnections.values.forEach { runCatching { it.close() } }
        tcpConnections.clear()
        udpSockets.values.forEach { runCatching { it.close() } }
        udpSockets.clear()
    }
}
