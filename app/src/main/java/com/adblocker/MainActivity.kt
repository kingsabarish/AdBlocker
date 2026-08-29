package com.adblocker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.adblocker.data.VpnState
import com.adblocker.ui.AdBlockerTheme
import com.adblocker.ui.MainScreen
import com.adblocker.ui.MainViewModel
import com.adblocker.vpn.VpnController

class MainActivity : ComponentActivity() {
    private val vm = MainViewModel()

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { if (it.resultCode == RESULT_OK) VpnController.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdBlockerTheme {
                val active by vm.isActive.collectAsState()
                val blocked by vm.blockedCount.collectAsState()
                MainScreen(
                    active = active,
                    blocked = blocked,
                    onToggle = { toggle() },
                )
            }
        }
    }

    private fun toggle() {
        if (VpnState.isActive.value) {
            VpnController.stop(this)
        } else {
            val prep = VpnController.prepare(this)
            if (prep != null) vpnPermission.launch(prep) else VpnController.start(this)
        }
    }
}
