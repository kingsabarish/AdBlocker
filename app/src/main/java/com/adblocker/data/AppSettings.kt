package com.adblocker.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "adblocker_settings")

object AppSettings {
    /** Set once from [com.adblocker.AdBlockerApp.onCreate] before any flow is collected. */
    lateinit var appContext: Context

    private val AUTO_START = booleanPreferencesKey("auto_start")

    // Per-app bypass: package names that should NOT be routed through the DNS VPN.
    private val BYPASS_APPS = stringSetPreferencesKey("bypass_apps")

    // Custom blocklist sources (raw hosts/AdAway formatted URLs) fetched at VPN start.
    // Each entry is encoded as "<0|1>\u0001<url>" where the first char is the enabled flag,
    // so users can enable/disable individual lists. Legacy url-only set is migrated on read.
    private val CUSTOM_LISTS = stringSetPreferencesKey("custom_lists")

    // Enabled state for default (built-in) blocklist sources, keyed by URL.
    // Each entry is encoded as "<0|1>\u0001<url>" just like custom lists.
    private val DEFAULT_LISTS = stringSetPreferencesKey("default_lists")

    // Legacy key kept only for one-time migration into [CUSTOM_LISTS].
    private val CUSTOM_LIST_URLS = stringSetPreferencesKey("custom_list_urls")

    // Custom blocklist rules typed by the user (one domain per line, hosts format also ok).
    private val CUSTOM_RULES = stringPreferencesKey("custom_rules")

    /** Built-in blocklist sources seeded on first launch. */
    val BUILTIN_LISTS = listOf(
        BuiltInBlocklist(
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            label = "StevenBlack Unified",
            description = "Adware + malware consolidated from 82K+ domains",
        ),
        BuiltInBlocklist(
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists-legacy/main/hosts/pro.txt",
            label = "Hagezi Multi PRO",
            description = "Ads, trackers, phishing, malware, scams, telemetry",
        ),
        BuiltInBlocklist(
            url = "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt",
            label = "AdAway",
            description = "Open-source Android ad blocker hosts",
        ),
        BuiltInBlocklist(
            url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&mimetype=plaintext&useip=0.0.0.0",
            label = "Peter Lowe's Ad Servers",
            description = "Curated ad and tracking server list",
        ),
    )

    // Lazily built so they only read [appContext] after AdBlockerApp sets it.
    val autoStart: Flow<Boolean> by lazy {
        appContext.dataStore.data.map { it[AUTO_START] ?: false }
    }

    val bypassApps: Flow<Set<String>> by lazy {
        appContext.dataStore.data.map { it[BYPASS_APPS] ?: emptySet() }
    }

    /**
     * All blocklists: built-in (default) lists merged with user custom lists.
     * Built-in lists appear first, followed by custom lists, both sorted alphabetically.
     */
    val allLists: Flow<List<BlocklistEntry>> by lazy {
        appContext.dataStore.data.map { prefs ->
            // --- built-in defaults ---
            val savedDefaults = prefs[DEFAULT_LISTS]
            val defaults = if (savedDefaults != null) {
                val map = savedDefaults.associate { decodeDefaultEntry(it) }
                BUILTIN_LISTS.map { bi ->
                    BlocklistEntry(
                        url = bi.url,
                        label = bi.label,
                        description = bi.description,
                        isDefault = true,
                        enabled = map[bi.url] ?: true,
                    )
                }
            } else {
                // First launch: all enabled by default.
                BUILTIN_LISTS.map { bi ->
                    BlocklistEntry(url = bi.url, label = bi.label, description = bi.description, isDefault = true, enabled = true)
                }
            }

            // --- custom user lists ---
            val encoded = prefs[CUSTOM_LISTS]
            val custom = if (!encoded.isNullOrEmpty()) {
                encoded.map { decodeEntry(it) }.map {
                    BlocklistEntry(url = it.url, label = null, description = null, isDefault = false, enabled = it.enabled)
                }
            } else {
                prefs[CUSTOM_LIST_URLS]?.map {
                    BlocklistEntry(url = it, label = null, description = null, isDefault = false, enabled = true)
                } ?: emptyList()
            }

            defaults + custom
        }
    }

    val customLists: Flow<List<CustomBlocklist>> by lazy {
        appContext.dataStore.data.map { prefs ->
            val encoded = prefs[CUSTOM_LISTS]
            val raw = if (!encoded.isNullOrEmpty()) {
                encoded.map { decodeEntry(it) }
            } else {
                prefs[CUSTOM_LIST_URLS]?.map { CustomBlocklist(it, true) } ?: emptyList()
            }
            raw.sortedBy { it.url.lowercase() }
        }
    }

    val customRules: Flow<String> by lazy {
        appContext.dataStore.data.map { it[CUSTOM_RULES] ?: "" }
    }

    suspend fun setAutoStart(value: Boolean) {
        appContext.dataStore.edit { it[AUTO_START] = value }
    }

    suspend fun setBypassApps(value: Set<String>) {
        appContext.dataStore.edit { it[BYPASS_APPS] = value }
    }

    suspend fun setCustomLists(value: List<CustomBlocklist>) {
        appContext.dataStore.edit { it[CUSTOM_LISTS] = value.map { encodeEntry(it) }.toSet() }
    }

    suspend fun setCustomRules(value: String) {
        appContext.dataStore.edit { it[CUSTOM_RULES] = value }
    }

    /** Enable/disable a built-in default blocklist. Persists the state across restarts. */
    suspend fun setDefaultListEnabled(url: String, enabled: Boolean) {
        appContext.dataStore.edit { prefs ->
            val existing = (prefs[DEFAULT_LISTS]?.map { decodeDefaultEntry(it) }?.toMap() ?: emptyMap()).toMutableMap()
            existing[url] = enabled
            prefs[DEFAULT_LISTS] = existing.entries.map { (u, e) -> encodeDefaultEntry(u, e) }.toSet()
        }
    }

    /** One-shot read of all tunnel-affecting settings. */
    suspend fun snapshot(context: Context): SettingsSnapshot {
        val prefs = context.dataStore.data.first()

        // Default lists (seeded from BUILTIN_LISTS, enabled state from DataStore).
        val defaultEnabled = prefs[DEFAULT_LISTS]?.map { decodeDefaultEntry(it) }?.toMap() ?: emptyMap()
        val defaultUrls = BUILTIN_LISTS
            .filter { bi -> (defaultEnabled[bi.url] ?: true) }
            .map { it.url }
            .toSet()

        // Custom lists.
        val customLists = if (!prefs[CUSTOM_LISTS].isNullOrEmpty()) {
            prefs[CUSTOM_LISTS]!!.map { decodeEntry(it) }
        } else {
            prefs[CUSTOM_LIST_URLS]?.map { CustomBlocklist(it, true) } ?: emptyList()
        }
        val customUrls = customLists.filter { it.enabled }.map { it.url }.toSet()

        return SettingsSnapshot(
            bypassApps = prefs[BYPASS_APPS] ?: emptySet(),
            customUrls = defaultUrls + customUrls,
            customRules = prefs[CUSTOM_RULES] ?: "",
        )
    }
}

data class CustomBlocklist(val url: String, val enabled: Boolean)

data class BuiltInBlocklist(val url: String, val label: String, val description: String)

data class BlocklistEntry(
    val url: String,
    val label: String?,
    val description: String?,
    val isDefault: Boolean,
    val enabled: Boolean,
)

fun encodeEntry(b: CustomBlocklist): String = (if (b.enabled) "1" else "0") + "\u0001" + b.url

fun decodeEntry(s: String): CustomBlocklist {
    val idx = s.indexOf('\u0001')
    return if (idx < 0) CustomBlocklist(s, true) else CustomBlocklist(s.substring(idx + 1), s[0] == '1')
}

/** Decode a persisted default-list entry (url, enabled). */
fun decodeDefaultEntry(s: String): Pair<String, Boolean> {
    val idx = s.indexOf('\u0001')
    return if (idx < 0) s to true else s.substring(idx + 1) to (s[0] == '1')
}

/** Encode a default-list entry for persistence. */
fun encodeDefaultEntry(url: String, enabled: Boolean): String =
    (if (enabled) "1" else "0") + "\u0001" + url

data class SettingsSnapshot(
    val bypassApps: Set<String>,
    val customUrls: Set<String>,
    val customRules: String,
)
