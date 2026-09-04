package com.adblocker

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.adblocker.data.VpnState
import com.adblocker.ui.AdBlockerTheme
import com.adblocker.ui.AppsScreen
import com.adblocker.ui.BlocklistsScreen
import com.adblocker.ui.HomeScreen
import com.adblocker.ui.SettingsViewModel
import com.adblocker.vpn.VpnController

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_START = "start"
        private const val PREFS_NAME = "adblocker_prefs"
        private const val KEY_TILE_PROMPTED = "tile_prompted"
    }

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { if (it.resultCode == RESULT_OK) VpnController.start(this) }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedStart = intent?.getBooleanExtra(EXTRA_START, false) == true
        setContent {
            AdBlockerTheme {
                val vm = remember { SettingsViewModel(this@MainActivity) }
                var tab by remember { mutableStateOf(Tab.Home) }
                val active by VpnState.isActive.collectAsState()
                val connecting by VpnState.isConnecting.collectAsState()
                val blocked by VpnState.blockedCount.collectAsState()
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == Tab.Home,
                                onClick = { tab = Tab.Home },
                                icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                label = { Text("Home") },
                            )
                            NavigationBarItem(
                                selected = tab == Tab.Apps,
                                onClick = { tab = Tab.Apps },
                                icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
                                label = { Text("Apps") },
                            )
                            NavigationBarItem(
                                selected = tab == Tab.Blocklists,
                                onClick = { tab = Tab.Blocklists },
                                icon = { Icon(Icons.Filled.List, contentDescription = null) },
                                label = { Text("Blocklists") },
                            )
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (tab) {
                            Tab.Home -> HomeScreen(active, connecting, blocked, onToggle = { toggle() })
                            Tab.Apps -> AppsScreen(vm = vm)
                            Tab.Blocklists -> BlocklistsScreen(vm = vm)
                        }
                    }
                }
            }
        }
        promptToAddTile()
        if (requestedStart && !VpnState.isActive.value && !VpnState.isConnecting.value) toggle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START, false) && !VpnState.isActive.value && !VpnState.isConnecting.value) toggle()
    }

    private fun toggle() {
        if (VpnState.isActive.value || VpnState.isConnecting.value) {
            VpnController.stop(this)
        } else {
            val prep = VpnController.prepare(this)
            if (prep != null) vpnPermission.launch(prep) else VpnController.start(this)
        }
    }

    /**
     * On Android 13+ prompt the user to add the Quick Settings tile on first launch.
     */
    private fun promptToAddTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (prefs().getBoolean(KEY_TILE_PROMPTED, false)) return
        prefs().edit().putBoolean(KEY_TILE_PROMPTED, true).apply()
        try {
            val cn = ComponentName(this, com.adblocker.vpn.AdBlockTileService::class.java)
            val icon = Icon.createWithResource(this, R.drawable.ic_tile)
            val sbm = getSystemService("statusbar")
            val method = sbm?.javaClass?.getMethod(
                "requestAddTileService",
                ComponentName::class.java,
                CharSequence::class.java,
                Icon::class.java,
                java.util.concurrent.Executor::class.java,
                java.util.function.Consumer::class.java,
            )
            method?.invoke(
                sbm,
                cn,
                getString(R.string.tile_label),
                icon,
                mainExecutor,
                java.util.function.Consumer<Int> { result ->
                    Log.d(TAG, "requestAddTileService result=$result")
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestAddTileService failed: ${e.message}")
        }
    }
}

private const val TAG = "AdBlock/Main"

enum class Tab { Home, Apps, Blocklists }
