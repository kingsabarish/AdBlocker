package com.adblocker.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import java.io.File

/**
 * Quick Settings tile to toggle the ad blocker.
 *
 * Follows the Extra-Dim pattern:
 * - [onClick] toggles the VPN directly; if the direct start is rejected it
 *   falls back to [VpnToggleActivity] via [PendingIntent].
 * - Tile state is read from a marker file (vpn_active) — works across processes.
 * - The marker file is written/deleted here so the tile updates instantly.
 */
class AdBlockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { toggle() }
        } else {
            toggle()
        }
    }

    private fun toggle() {
        val marker = File(filesDir, "vpn_active")
        val active = try { marker.exists() } catch (_: Exception) { false }

        if (active) {
            // Delete marker FIRST so tile reads "Off" immediately
            marker.delete()
            try {
                VpnController.stop(this)
            } catch (_: Exception) { }
            updateTile()
        } else {
            val prep = VpnController.prepare(this)
            if (prep != null) {
                launchToggleActivity()
                return
            }
            // Try direct start
            try {
                VpnController.start(this)
                // Create marker
                try { marker.writeText("1") } catch (_: Exception) {}
                updateTile()
                return
            } catch (_: Exception) { }
            // Fallback: launch activity
            launchToggleActivity()
        }
    }

    private fun launchToggleActivity() {
        val intent = Intent(this, VpnToggleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val t = qsTile ?: return
        val active = readActive()
        t.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (active) "AdBlock: On" else "AdBlock: Off"
        try {
            t.updateTile()
        } catch (e: Exception) {
            Log.w("AdBlock/Tile", "updateTile failed: ${e.message}")
        }
    }

    private fun readActive(): Boolean {
        return try {
            File(filesDir, "vpn_active").exists()
        } catch (_: Exception) {
            false
        }
    }
}
