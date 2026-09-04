package com.adblocker.filter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel

object BlocklistManager {
    private const val TAG = "AdBlock/Trie"
    private val loadMutex = Mutex()

    @Volatile
    private var currentMatcher: DomainMatcher = EmptyMatcher

    @Volatile
    private var lastLoadedUrls: Set<String> = emptySet()

    @Volatile
    private var lastLoadedRules: String = ""

    fun init(context: Context) {
        currentMatcher = EmptyMatcher
    }

    private fun blocklistDir(context: Context): File =
        File(context.filesDir, "blocklists").also { it.mkdirs() }

    suspend fun load(context: Context, customUrls: Set<String>, customRules: String) {
        if (customUrls == lastLoadedUrls && customRules == lastLoadedRules && currentMatcher.size() > 0) {
            Log.i(TAG, "blocklists unchanged (${currentMatcher.size()} domains), skipping reload")
            return
        }
        try {
            loadMutex.withLock {
                withContext(Dispatchers.IO) {
                    val dir = blocklistDir(context)

                    // Phase 1: Download all URLs to disk (HTTP freed between each)
                    val cachedFiles = mutableListOf<File>()
                    for (url in customUrls) {
                        ensureActive()
                        try {
                            val file = downloadToDisk(dir, url)
                            if (file != null) cachedFiles.add(file)
                        } catch (_: OutOfMemoryError) {
                            Log.e(TAG, "OOM downloading $url, stopping")
                            System.gc()
                            break
                        }
                    }

                    // Phase 2: Build matcher from disk files (no HTTP in memory)
                    // Parse all domains into temp list, then mmap-sort
                    val tempDomains = ArrayList<String>(80000)
                    try {
                        customRules.lineSequence().forEach { line ->
                            val d = extractDomain(line) ?: return@forEach
                            tempDomains.add(d)
                        }
                    } catch (_: OutOfMemoryError) {
                        Log.e(TAG, "OOM parsing custom rules")
                    }

                    for (file in cachedFiles) {
                        ensureActive()
                        try {
                            readDomainsFromFile(tempDomains, file)
                        } catch (_: OutOfMemoryError) {
                            Log.e(TAG, "OOM reading ${file.name}, using partial")
                            break
                        }
                        file.delete()
                    }

                    // Phase 3: Sort and mmap (temp list freed after build)
                    if (tempDomains.isNotEmpty()) {
                        val matcher = DiskMatcher.build(dir, tempDomains)
                        currentMatcher = matcher
                    }
                    lastLoadedUrls = customUrls
                    lastLoadedRules = customRules
                    Log.i(TAG, "blocklists loaded, ${currentMatcher.size()} domains")
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM in load: ${e.message}")
            System.gc()
        } catch (e: Exception) {
            Log.e(TAG, "blocklist load error: ${e.message}")
        }
    }

    private fun downloadToDisk(dir: File, url: String): File? {
        var conn: HttpURLConnection? = null
        val filename = url.hashCode().toString(16) + ".txt"
        val file = File(dir, filename)
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            Log.i(TAG, "downloaded $url -> ${file.length()} bytes")
            return file
        } catch (e: Exception) {
            Log.w(TAG, "failed to download $url: ${e.message}")
            file.delete()
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readDomainsFromFile(out: MutableList<String>, file: File) {
        BufferedReader(InputStreamReader(FileInputStream(file))).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { l ->
                    extractDomain(l)?.let { out.add(it) }
                }
            }
        }
        Log.i(TAG, "parsed ${file.name} -> ${out.size} domains total")
    }

    fun isBlocked(domain: String): Boolean = currentMatcher.matches(domain)

    internal fun extractDomain(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed[0] == '#') return null
        val spaceIdx = trimmed.indexOf(' ', 1)
        val domain = if (spaceIdx > 0) trimmed.substring(spaceIdx + 1) else trimmed
        if (domain.isEmpty() || domain[0] == '#' || domain[0] == '!') return null
        val lower = domain.lowercase().trim()
        if (lower.isEmpty() || lower == "localhost" || lower.startsWith("127.")) return null
        val raw = if (lower.startsWith("*.")) lower.substring(2) else lower
        val dotCount = raw.count { it == '.' }
        return if (dotCount >= 1) raw else null
    }
}

interface DomainMatcher {
    fun matches(domain: String): Boolean
    fun size(): Int
}

object EmptyMatcher : DomainMatcher {
    override fun matches(domain: String) = false
    override fun size() = 0
}
