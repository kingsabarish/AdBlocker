package com.adblocker.vpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import com.adblocker.data.VpnState
import com.adblocker.filter.BlocklistManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides what to do with a DNS query: block it locally (A 0.0.0.0 response), answer AAAA
 * queries with an empty NOERROR, or forward to the upstream resolver and relay the answer back.
 *
 * A SERVFAIL response is always returned on failure so the client never hangs.
 */
class DnsResolver(private val service: VpnService) {
    // Bounded LRU cache with TTL — avoids OOM from unbounded growth
    private val cache = object : LinkedHashMap<String, CacheEntry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    private val cacheLock = Any()

    private data class CacheEntry(val data: ByteArray, val ts: Long)

    private val fallbacks = listOf(
        InetSocketAddress("1.1.1.1", 53),
        InetSocketAddress("8.8.8.8", 53),
        InetSocketAddress("9.9.9.9", 53),
    )

    private val failedUpstreams = ConcurrentHashMap<InetSocketAddress, Long>()

    private fun upstreams(): List<InetSocketAddress> {
        val sys = mutableListOf<InetSocketAddress>()
        try {
            val cm = service.getSystemService(ConnectivityManager::class.java)
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(net) ?: continue
                for (dns in lp.dnsServers) sys.add(InetSocketAddress(dns, 53))
            }
        } catch (_: Exception) {
            // fall through to public resolvers
        }
        val now = System.currentTimeMillis()
        val candidates = (sys.distinct() + fallbacks).take(6)
        return candidates.filter { addr ->
            val failedAt = failedUpstreams[addr]
            failedAt == null || (now - failedAt) > UPSTREAM_FAILURE_TTL_MS
        }
    }

    /**
     * Forward a DNS query to the first responding upstream.
     * Short timeouts (1.5s) so failing fast doesn't stall the client.
     */
    private fun forward(query: DnsQuery): ByteArray? {
        val upstreams = upstreams()
        if (upstreams.isEmpty()) {
            Log.w(TAG, "No available upstreams for ${query.domain}")
            return null
        }
        for (up in upstreams) {
            val s = DatagramSocket()
            try {
                s.soTimeout = 1500
                if (!service.protect(s)) {
                    Log.w(TAG, "protect() failed for upstream $up, skipping")
                    continue
                }
                s.send(DatagramPacket(query.raw, query.raw.size, up))
                val recv = DatagramPacket(ByteArray(4096), 4096)
                s.receive(recv)
                failedUpstreams.remove(up)
                return recv.data.copyOf(recv.length)
            } catch (e: Exception) {
                failedUpstreams[up] = System.currentTimeMillis()
                Log.w(TAG, "Upstream $up failed for ${query.domain}: ${e.message}")
            } finally {
                s.close()
            }
        }
        Log.w(TAG, "FORWARD all failed for ${query.domain}")
        return null
    }

    /** Build a SERVFAIL packet wrapped in IPv4/UDP so the client gets an answer. */
    fun buildErrorResponse(ip: IpPacket, query: DnsQuery): ByteArray {
        val src = ip.srcIp
        val dst = ip.dstIp
        if (src !is Inet4Address || dst !is Inet4Address) return ByteArray(0)
        val response = buildServFail(query)
        return PacketBuilder.buildUdp(dst, src, ip.dstPort, ip.srcPort, response)
    }

    private fun buildServFail(query: DnsQuery): ByteArray {
        val q = query.questionBytes
        val out = ByteArray(12 + q.size)
        var p = 0
        out[p] = (query.id ushr 8).toByte(); p++
        out[p] = (query.id and 0xFF).toByte(); p++
        out[p] = 0x81.toByte(); p++
        out[p] = 0x82.toByte(); p++ // QR=1, RCODE=2 (SERVFAIL)
        out[p] = 0; p++
        out[p] = 1; p++ // QDCOUNT
        out[p] = 0; p++
        out[p] = 0; p++ // ANCOUNT
        out[p] = 0; p++
        out[p] = 0; p++ // NSCOUNT
        out[p] = 0; p++
        out[p] = 0; p++ // ARCOUNT
        System.arraycopy(q, 0, out, p, q.size)
        return out
    }

    private fun buildUdpResponse(ip: IpPacket, payload: ByteArray): ByteArray {
        val src = ip.srcIp
        val dst = ip.dstIp
        if (src !is Inet4Address || dst !is Inet4Address) return ByteArray(0)
        return PacketBuilder.buildUdp(dst, src, ip.dstPort, ip.srcPort, payload)
    }

    /**
     * Returns the IPv4/UDP packet to write back to the TUN.
     * NEVER returns null — a SERVFAIL response is sent on failure.
     */
    fun handle(ip: IpPacket, query: DnsQuery): ByteArray {
        if (query.type == 28) {
            Log.i(TAG, "AAAA empty ${query.domain} id=${query.id.toDnsHex()}")
            return buildUdpResponse(ip, PacketBuilder.buildEmptyResponse(query))
        }

        if (BlocklistManager.isBlocked(query.domain)) {
            VpnState.incrementBlocked()
            Log.i(TAG, "BLOCKED ${query.domain} id=${query.id.toDnsHex()}")
            return buildUdpResponse(ip, PacketBuilder.buildBlockedResponse(query))
        }

        val key = "${query.domain.lowercase()}:${query.type}"
        val now = System.currentTimeMillis()

        // Check cache first
        synchronized(cacheLock) {
            val cached = cache[key]
            if (cached != null && (now - cached.ts) < CACHE_TTL_MS) {
                // CRITICAL: the cached bytes embed the transaction ID of the FIRST query that
                // populated the entry. A later query for the same domain:type carries a NEW id,
                // so serving the raw cached bytes makes the client drop the response as
                // mismatched (=> Chrome "DNS_PROBE_FINISHED_BAD_CONFIG"). Patch the id to match.
                val patched = rewriteTransactionId(cached.data, query.id)
                Log.i(TAG, "CACHE hit ${query.domain} qtype=${query.type} id=${query.id.toDnsHex()}")
                return buildUdpResponse(ip, patched)
            }
            cache.remove(key)
        }

        // Forward to upstream
        val t0 = System.nanoTime()
        val bytes = forward(query)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        if (bytes != null) {
            synchronized(cacheLock) { cache[key] = CacheEntry(bytes, now) }
            Log.i(TAG, "FORWARD ${query.domain} qtype=${query.type} id=${query.id.toDnsHex()} ${elapsedMs}ms")
        } else {
            Log.i(TAG, "SERVFAIL ${query.domain} qtype=${query.type} id=${query.id.toDnsHex()} after ${elapsedMs}ms")
        }

        val response = bytes ?: buildServFail(query)
        return buildUdpResponse(ip, response)
    }

    /**
     * Return a copy of a cached DNS response with the 16-bit transaction ID rewritten to
     * [id], so the response matches the query that triggered this cache hit.
     */
    private fun rewriteTransactionId(raw: ByteArray, id: Int): ByteArray {
        if (raw.size < 2) return raw
        return raw.copyOf().also { b ->
            b[0] = (id ushr 8).toByte()
            b[1] = (id and 0xFF).toByte()
        }
    }

    companion object {
        private const val TAG = "AdBlock/Dns"
        private const val MAX_CACHE_SIZE = 2048
        private const val CACHE_TTL_MS = 300_000L // 5 minutes
        private const val UPSTREAM_FAILURE_TTL_MS = 30_000L // 30 seconds
    }
}

/** Format a DNS transaction ID as a 4-char lowercase hex string (e.g. `abcd`). */
fun Int.toDnsHex(): String {
    fun nib(b: Int): String = HEX_DIGITS[b ushr 4].toString() + HEX_DIGITS[b and 0x0F].toString()
    val v = this and 0xFFFF
    return nib(v ushr 8) + nib(v and 0xFF)
}

private const val HEX_DIGITS = "0123456789abcdef"
