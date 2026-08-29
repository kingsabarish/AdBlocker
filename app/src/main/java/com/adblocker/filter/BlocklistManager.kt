package com.adblocker.filter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

/**
 * Loads blocklists (AdAway/hosts format: `0.0.0.0 domain` or bare `domain`) into the [DomainTrie].
 *
 * A small curated list is bundled in `assets/blocklist.txt` so the app works offline. Full lists
 * (StevenBlack, AdGuard, EasyList) can be fetched at runtime via [loadFromText].
 */
object BlocklistManager {
    private val trie = DomainTrie()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        runCatching {
            context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                for (line in lines) addLine(line)
            }
        }
        loaded = true
    }

    private fun addLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return
        val parts = trimmed.split(Regex("\\s+"))
        val domain = if (parts.size >= 2) parts[1] else parts[0]
        trie.add(domain)
    }

    fun isBlocked(domain: String): Boolean = trie.matches(domain)

    suspend fun loadFromText(text: String) = withContext(Dispatchers.IO) {
        BufferedReader(StringReader(text)).useLines { lines ->
            for (line in lines) addLine(line)
        }
    }
}
