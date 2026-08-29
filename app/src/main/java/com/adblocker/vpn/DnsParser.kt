package com.adblocker.vpn

/**
 * Parses a DNS query (RFC 1035) from a UDP payload.
 *
 * @param id transaction id (echoed back in the response)
 * @param domain the QNAME (already lower-cased by the matcher on use)
 * @param type QTYPE (1 = A, 28 = AAAA, ...)
 * @param questionBytes the raw question section (name + type + class) to echo into the response
 * @param raw the original query payload bytes, forwarded unchanged to the upstream resolver
 */
data class DnsQuery(
    val id: Int,
    val domain: String,
    val type: Int,
    val questionBytes: ByteArray,
    val raw: ByteArray,
)

object DnsParser {
    fun parse(payload: ByteArray): DnsQuery? {
        if (payload.size < 12) return null
        val id = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val flags = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return null // skip responses
        val qdcount = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        if (qdcount < 1) return null

        var pos = 12
        val sb = StringBuilder()
        while (pos < payload.size) {
            val len = payload[pos].toInt() and 0xFF
            if (len == 0) { pos += 1; break }
            if (len >= 0xC0) { pos += 2; break } // compression pointer (unexpected in queries)
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(payload, pos + 1, len, Charsets.US_ASCII))
            pos += len + 1
        }
        if (pos + 4 > payload.size) return null
        val type = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
        val questionBytes = payload.copyOfRange(12, pos + 4)
        return DnsQuery(id, sb.toString(), type, questionBytes, payload.copyOf())
    }
}
