package com.adblocker.vpn

import android.app.Activity
import android.os.Bundle
import java.io.File

/**
 * Invisible activity launched by the Quick Settings tile to toggle the VPN
 * from a foreground context.  Android 12+ blocks starting foreground services
 * from a background tile tap, so the tile launches this transparent activity
 * instead, which has the required foreground context.
 *
 * The activity writes/deletes the vpn_active marker file directly using its
 * own [filesDir], ensuring the tile (which reads this file) updates correctly
 * regardless of VPN service process isolation.
 */
class VpnToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val marker = File(filesDir, "vpn_active")
        val active = try { marker.exists() } catch (_: Exception) { false }

        if (active) {
            // Delete marker FIRST so tile reads "Off" immediately
            marker.delete()
            VpnController.stop(this)
        } else {
            val prep = VpnController.prepare(this)
            if (prep != null) {
                startActivity(prep)
            } else {
                // Create marker BEFORE starting so tile reads "On" immediately
                try { marker.writeText("1") } catch (_: Exception) {}
                VpnController.start(this)
            }
        }
        finish()
    }
}
