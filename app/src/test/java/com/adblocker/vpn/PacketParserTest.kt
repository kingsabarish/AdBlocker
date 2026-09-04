package com.adblocker.vpn

import com.adblocker.data.CustomBlocklist
import com.adblocker.data.decodeEntry
import com.adblocker.data.encodeEntry
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

    @Test
    fun aaaaQueryReturnsEmptyNoError() {
        // This is the fix for "internet not working when the blocker is on": the DNS-only
        // tunnel blackholes all IPv6 (`::/0 unreachable`), so AAAA queries must answer empty
        // (NOERROR, 0 answers) to make apps fall back to IPv4 instead of dead IPv6.
        val query = DnsParser.parse(buildDnsQuery("example.com", 28))!!
        assertEquals(28, query.type)
        val resp = PacketBuilder.buildEmptyResponse(query)
        assertEquals(12 + query.questionBytes.size, resp.size)
        assertEquals(0x8180, ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF))
        assertEquals(0, ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)) // ANCOUNT = 0
        // question echoed back verbatim so the client matches the response
        assertTrue(resp.copyOfRange(12, 12 + query.questionBytes.size).contentEquals(query.questionBytes))
    }

    @Test
    fun emptyResponseWrapsIntoValidUdp() {
        val query = DnsParser.parse(buildDnsQuery("example.com", 28))!!
        val resp = PacketBuilder.buildEmptyResponse(query)
        val pkt = PacketBuilder.buildUdp(
            Inet4Address.getByName("10.10.10.2") as Inet4Address,
            Inet4Address.getByName("10.10.10.1") as Inet4Address,
            53,
            12345,
            resp,
        )
        assertEquals(4, pkt[0].toInt() ushr 4)
        assertEquals(17, pkt[9].toInt() and 0xFF)
        assertEquals(20 + 8 + resp.size, pkt.size)
    }

    @Test
    fun customBlocklistEncodeDecodeRoundTrips() {
        // The separator must survive URLs that contain characters like '#', '=' and ':'.
        val lists = listOf(
            CustomBlocklist("https://example.com/ads.txt#anchor", enabled = true),
            CustomBlocklist("https://block.demo/list?a=1&b=2", enabled = false),
            CustomBlocklist("http://1.2.3.4:8080/hosts", enabled = true),
        )
        for (b in lists) {
            val enc = encodeEntry(b)
            val dec = decodeEntry(enc)
            assertEquals(b.url, dec.url)
            assertEquals(b.enabled, dec.enabled)
        }
    }

    @Test
    fun customBlocklistDisabledFlagDecodes() {
        assertEquals(CustomBlocklist("x", false), decodeEntry("0\u0001x"))
        assertEquals(CustomBlocklist("y", true), decodeEntry("1\u0001y"))
    }
}
