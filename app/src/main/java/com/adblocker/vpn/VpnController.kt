package com.adblocker.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService

object VpnController {
    /** Returns the consent Intent if VPN permission is not yet granted, else null. */
    fun prepare(context: Context): Intent? = VpnService.prepare(context)

    fun start(context: Context) {
        context.startForegroundService(Intent(context, AdBlockVpnService::class.java))
    }

    fun stop(context: Context) {
        // Send STOP action — the service handles cleanup in onStartCommand.
        // Do NOT call stopService() as it can race with foreground service lifecycle.
        try {
            context.startService(
                Intent(context, AdBlockVpnService::class.java)
                    .apply { action = AdBlockVpnService.ACTION_STOP },
            )
        } catch (_: Exception) {
            // Service may already be stopped — that's fine
        }
    }

    /** Full reload: closes TUN, rebuilds tunnel, reloads blocklists. For bypass app changes. */
    fun reload(context: Context) {
        try {
            context.startService(
                Intent(context, AdBlockVpnService::class.java)
                    .apply { action = AdBlockVpnService.ACTION_RELOAD },
            )
        } catch (_: Exception) { }
    }

    /** Lightweight reload: only reloads blocklists without disrupting the VPN tunnel. */
    fun reloadBlocklists(context: Context) {
        try {
            context.startService(
                Intent(context, AdBlockVpnService::class.java)
                    .apply { action = AdBlockVpnService.ACTION_RELOAD_BLOCKLISTS },
            )
        } catch (_: Exception) { }
    }
}
