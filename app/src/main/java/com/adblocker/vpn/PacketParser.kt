package com.adblocker.vpn

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Parses an IPv4/UDP packet read from the TUN interface.
 *
 * This iteration handles IPv4 + UDP only (DNS is UDP). Other protocols/packet types are ignored
 * by the caller. IPv6 is a follow-up.
 */
data class IpPacket(
    val srcIp: InetAddress,
    val dstIp: InetAddress,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

object PacketParser {
    fun parse(buffer: ByteArray, length: Int): IpPacket? {
        if (length < 20) return null
        val versionIhl = buffer[0].toInt() and 0xFF
        if (versionIhl shr 4 != 4) return null // IPv4 only
        val ihl = (versionIhl and 0x0F) * 4
        if (buffer[9].toInt() and 0xFF != 17) return null // UDP only
        val srcIp = inet4(buffer, 12)
        val dstIp = inet4(buffer, 16)
        val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
        val udpLen = ((buffer[ihl + 4].toInt() and 0xFF) shl 8) or (buffer[ihl + 5].toInt() and 0xFF)
        val payloadStart = ihl + 8
        val payloadEnd = minOf(length, payloadStart + (udpLen - 8))
        if (payloadEnd <= payloadStart) return null
        val payload = buffer.copyOfRange(payloadStart, payloadEnd)
        return IpPacket(srcIp, dstIp, srcPort, dstPort, payload)
    }

    private fun inet4(b: ByteArray, off: Int): InetAddress =
        Inet4Address.getByAddress(b.copyOfRange(off, off + 4))
}
