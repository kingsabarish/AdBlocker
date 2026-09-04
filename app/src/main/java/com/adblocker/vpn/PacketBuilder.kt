package com.adblocker.vpn

import java.net.Inet4Address

/**
 * Builds IPv4/UDP packets with correct header checksums, plus DNS response payloads.
 */
object PacketBuilder {

    /** Wrap [payload] in an IPv4 + UDP packet addressed src -> dst. */
    fun buildUdp(srcIp: Inet4Address, dstIp: Inet4Address, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val packet = ByteArray(totalLen)

        // IPv4 header
        packet[0] = 0x45
        packet[1] = 0
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0
        packet[5] = 0
        packet[6] = 0
        packet[7] = 0
        packet[8] = 64 // TTL
        packet[9] = 17 // protocol UDP
        packet[10] = 0
        packet[11] = 0
        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)
        val ipSum = checksum16(packet, 0, 20)
        packet[10] = (ipSum ushr 8).toByte()
        packet[11] = (ipSum and 0xFF).toByte()

        // UDP header
        val u = 20
        packet[u] = (srcPort ushr 8).toByte()
        packet[u + 1] = (srcPort and 0xFF).toByte()
        packet[u + 2] = (dstPort ushr 8).toByte()
        packet[u + 3] = (dstPort and 0xFF).toByte()
        packet[u + 4] = (udpLen ushr 8).toByte()
        packet[u + 5] = (udpLen and 0xFF).toByte()
        packet[u + 6] = 0
        packet[u + 7] = 0
        System.arraycopy(payload, 0, packet, u + 8, payload.size)

        val udpSum = udpChecksum(srcIp, dstIp, packet, u, udpLen)
        packet[u + 6] = (udpSum ushr 8).toByte()
        packet[u + 7] = (udpSum and 0xFF).toByte()
        return packet
    }

    /** Build an A-record response answering [query] with 0.0.0.0 (blocks the domain). */
    fun buildBlockedResponse(query: DnsQuery): ByteArray {
        val q = query.questionBytes
        val out = ByteArray(12 + q.size + 16)
        var p = 0
        writeShort(out, p, query.id); p += 2
        writeShort(out, p, 0x8180); p += 2 // QR=1, AA=1, RD=1, RA=1
        writeShort(out, p, 1); p += 2 // QDCOUNT
        writeShort(out, p, 1); p += 2 // ANCOUNT
        writeShort(out, p, 0); p += 2 // NSCOUNT
        writeShort(out, p, 0); p += 2 // ARCOUNT
        System.arraycopy(q, 0, out, p, q.size); p += q.size
        // Answer: name pointer back to the question (0xC00C)
        out[p] = 0xC0.toByte(); out[p + 1] = 0x0C; p += 2
        writeShort(out, p, 1); p += 2 // TYPE A
        writeShort(out, p, 1); p += 2 // CLASS IN
        writeInt(out, p, 0); p += 4 // TTL 0
        writeShort(out, p, 4); p += 2 // RDLENGTH
        out[p] = 0; out[p + 1] = 0; out[p + 2] = 0; out[p + 3] = 0; p += 4 // 0.0.0.0
        return out
    }

    /**
     * Build a NOERROR response with no answer records. Used for AAAA queries so apps fall
     * back to IPv4: the VPN routes only the fake DNS server and blackholes all IPv6 data
     * (`::/0 unreachable`), so handing out real IPv6 addresses would break connectivity.
     */
    fun buildEmptyResponse(query: DnsQuery): ByteArray {
        val q = query.questionBytes
        val out = ByteArray(12 + q.size)
        var p = 0
        writeShort(out, p, query.id); p += 2
        writeShort(out, p, 0x8180); p += 2 // QR=1, AA=1, RD=1, RA=1
        writeShort(out, p, 1); p += 2 // QDCOUNT
        writeShort(out, p, 0); p += 2 // ANCOUNT = 0
        writeShort(out, p, 0); p += 2 // NSCOUNT
        writeShort(out, p, 0); p += 2 // ARCOUNT
        System.arraycopy(q, 0, out, p, q.size); p += q.size
        return out
    }

    private fun writeShort(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun checksum16(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun udpChecksum(src: Inet4Address, dst: Inet4Address, buf: ByteArray, udpOff: Int, udpLen: Int): Int {
        var sum = 0
        val pseudo = ByteArray(12)
        System.arraycopy(src.address, 0, pseudo, 0, 4)
        System.arraycopy(dst.address, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = 17 // protocol
        pseudo[10] = (udpLen ushr 8).toByte()
        pseudo[11] = (udpLen and 0xFF).toByte()
        for (i in pseudo.indices step 2) {
            sum += ((pseudo[i].toInt() and 0xFF) shl 8) or (pseudo[i + 1].toInt() and 0xFF)
        }
        var i = udpOff
        val end = udpOff + udpLen
        while (i < end) {
            val hi = buf[i].toInt() and 0xFF
            val lo = if (i + 1 < end) buf[i + 1].toInt() and 0xFF else 0
            sum += (hi shl 8) or lo
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        val r = sum.inv() and 0xFFFF
        return if (r == 0) 0xFFFF else r
    }
}
