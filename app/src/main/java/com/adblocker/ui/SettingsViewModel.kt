package com.adblocker.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adblocker.data.AppSettings
import com.adblocker.data.BlocklistEntry
import com.adblocker.data.CustomBlocklist
import com.adblocker.data.VpnState
import com.adblocker.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AppInfo(val packageName: String, val label: String, val isSystem: Boolean)

class SettingsViewModel(private val context: Context) : ViewModel() {
    private val appContext = context.applicationContext

    val bypassApps: Flow<Set<String>> = AppSettings.bypassApps
    val allLists: Flow<List<BlocklistEntry>> = AppSettings.allLists
    val customLists: Flow<List<CustomBlocklist>> = AppSettings.customLists
    val customRules: Flow<String> = AppSettings.customRules

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps

    init { loadInstalledApps() }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = appContext.packageManager
            // Blokada pattern: getInstalledApplications + FLAG_SYSTEM.
            // No launch-intent filtering — it fails on Android 11+ without QUERY_ALL_PACKAGES.
            // The UI separates user apps (non-system) from system apps via the isSystem flag.
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != appContext.packageName }
                .mapNotNull {
                    try {
                        val label = pm.getApplicationLabel(it).toString().ifBlank { it.packageName }
                        val isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        AppInfo(it.packageName, label, isSystem)
                    } catch (_: Exception) { null }
                }
                .filter { it.label.isNotBlank() }
                .sortedBy { it.label.lowercase() }
            _installedApps.value = apps
        }
    }

    /**
     * Enable/disable ad blocking for a single app.
     * `blockingEnabled = true` routes the app through the VPN (not bypassed);
     * `false` excludes it from the tunnel so its ads/trackers are not filtered.
     * If the VPN is running, the tunnel is restarted so the change takes effect.
     */
    fun setAppBlocking(packageName: String, blockingEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val cur = AppSettings.bypassApps.first().toMutableSet()
            if (blockingEnabled) cur.remove(packageName) else cur.add(packageName)
            AppSettings.setBypassApps(cur)
            if (VpnState.isActive.value) VpnController.reload(appContext)
        }
    }

    /** Add a custom blocklist URL (enabled by default) and reload the tunnel if running. */
    fun addUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val u = url.trim()
            if (u.isEmpty()) return@launch
            val cur = AppSettings.customLists.first().toMutableList()
            if (cur.any { it.url == u }) return@launch
            cur.add(CustomBlocklist(u, enabled = true))
            AppSettings.setCustomLists(cur)
            if (VpnState.isActive.value) VpnController.reloadBlocklists(appContext)
        }
    }

    fun removeUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cur = AppSettings.customLists.first().toMutableList()
            cur.removeAll { it.url == url }
            AppSettings.setCustomLists(cur)
            if (VpnState.isActive.value) VpnController.reloadBlocklists(appContext)
        }
    }

    /** Enable/disable a built-in default blocklist and reload the tunnel if running. */
    fun setDefaultListEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            AppSettings.setDefaultListEnabled(url, enabled)
            if (VpnState.isActive.value) VpnController.reloadBlocklists(appContext)
        }
    }

    /** Enable/disable a single custom blocklist and reload the tunnel if running. */
    fun setBlocklistEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val cur = AppSettings.customLists.first().toMutableList()
            val idx = cur.indexOfFirst { it.url == url }
            if (idx < 0) return@launch
            cur[idx] = cur[idx].copy(enabled = enabled)
            AppSettings.setCustomLists(cur)
            if (VpnState.isActive.value) VpnController.reloadBlocklists(appContext)
        }
    }

    /** Save manual rules and reload the tunnel if the VPN is running. */
    fun saveRules(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AppSettings.setCustomRules(text)
            if (VpnState.isActive.value) VpnController.reloadBlocklists(appContext)
        }
    }
}
