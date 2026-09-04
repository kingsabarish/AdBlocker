package com.adblocker.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.adblocker.data.AppSettings
import com.adblocker.data.VpnState
import com.adblocker.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BypassReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("package") ?: return
        val goAsync = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                try { AppSettings.appContext } catch (_: UninitializedPropertyAccessException) {
                    AppSettings.appContext = context.applicationContext
                }
                val cur = AppSettings.bypassApps.first().toMutableSet()
                val wasPresent = cur.contains(pkg)
                if (wasPresent) cur.remove(pkg) else cur.add(pkg)
                AppSettings.setBypassApps(cur)
                Log.i("AdBlock/Bypass", "toggled $pkg: wasBypassed=$wasPresent, now=${cur.contains(pkg)}")
                if (VpnState.isActive.value) VpnController.reload(context)
            } catch (e: Exception) {
                Log.e("AdBlock/Bypass", "failed: ${e.message}")
            } finally {
                goAsync.finish()
            }
        }
    }
}
