package com.adblocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adblocker.data.AppSettings
import com.adblocker.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        CoroutineScope(Dispatchers.IO).launch {
            if (AppSettings.autoStart(context).first()) {
                VpnController.prepare(context) ?: VpnController.start(context)
            }
        }
    }
}
