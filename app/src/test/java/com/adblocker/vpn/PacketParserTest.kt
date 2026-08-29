package com.adblocker.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address

private fun buildDnsQuery(name: String, type: Int, id: Int = 0x1234): ByteArray {
    val labels = name.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray(Charsets.US_ASCII).toList() }
    val nameBytes = labels + 0.toByte()
    val out = ByteArray(12 + nameBytes.size + 4)
    out[0] = (id ushr 8).toByte()
    out[1] = (id and 0xFF).toByte()
    out[2] = 0x01.toByte() // RD=1
    out[3] = 0x00
    out[4] = 0x00
    out[5] = 0x01 // QDCOUNT=1
    var p = 12
    for (b in nameBytes) { out[p] = b; p += 1 }
    out[p] = (type ushr 8).toByte(); out[p + 1] = (type and 0xFF).toByte()
    out[p + 2] = 0x00; out[p + 3] = 0x01 // CLASS IN
    return out
}

class DnsParserTest {

    @Test
    fun parsesSimpleQuery() {
        val q = DnsParser.parse(buildDnsQuery("example.com", 1))
        assertNotNull(q)
        assertEquals("example.com", q!!.domain)
        assertEquals(1, q.type)
        assertEquals(0x1234, q.id)
    }

    @Test
    fun rejectsResponses() {
        val resp = buildDnsQuery("example.com", 1)
        resp[2] = (resp[2].toInt() or 0x80).toByte() // set QR
        assertNull(DnsParser.parse(resp))
    }
}

class PacketParserTest {

    @Test
    fun roundTripDnsPacket() {
        val query = buildDnsQuery("ads.doubleclick.net", 1)
        val pkt = PacketBuilder.buildUdp(
            Inet4Address.getByName("10.10.10.1") as Inet4Address,
            Inet4Address.getByName("10.10.10.2") as Inet4Address,
            12345,
            53,
            query,
        )
        val ip = PacketParser.parse(pkt, pkt.size)
        assertNotNull(ip)
        assertEquals(53, ip!!.dstPort)
        assertEquals(12345, ip.srcPort)
        val dq = DnsParser.parse(ip.payload)
        assertNotNull(dq)
        assertEquals("ads.doubleclick.net", dq!!.domain)
    }
}

class PacketBuilderTest {

    @Test
    fun blockedResponseHasAnswer() {
        val query = DnsParser.parse(buildDnsQuery("ads.doubleclick.net", 1))!!
        val resp = PacketBuilder.buildBlockedResponse(query)
        // Header: id(2) flags(2)=0x8180 QD(2)=1 AN(2)=1 NS(2)=0 AR(2)=0
        assertEquals(0x1234, ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF))
        assertEquals(0x8180, ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF))
        assertEquals(1, ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)) // ANCOUNT
        // Answer A record: 0.0.0.0
        assertTrue(resp.size >= 12 + query.questionBytes.size + 16)
    }
}
