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
        context.stopService(Intent(context, AdBlockVpnService::class.java))
    }
}
