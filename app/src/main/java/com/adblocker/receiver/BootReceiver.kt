package com.adblocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.adblocker.data.AppSettings
import com.adblocker.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (AppSettings.autoStart.first()) {
                    VpnController.prepare(context) ?: VpnController.start(context)
                }
            } catch (e: Exception) {
                Log.w("AdBlock/Boot", "auto-start failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
