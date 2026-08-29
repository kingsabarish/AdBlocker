package com.adblocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.annotation.SuppressLint
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.adblocker.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import com.adblocker.R
import com.adblocker.data.VpnState

/**
 * Local, no-root DNS VPN. Only the fake DNS server (10.10.10.2) is routed through the TUN; all
 * other traffic bypasses the VPN (split tunnel). DNS queries that enter the tunnel are filtered by
 * [DnsResolver].
 */
class AdBlockVpnService : VpnService() {
    private var running = false
    private var thread: Thread? = null
    private val resolver = DnsResolver(this)

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            VpnState.setActive(true)
            VpnState.resetBlocked()
            thread = Thread({ runVpn() }, "adblock-vpn").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        thread = null
        VpnState.setActive(false)
        super.onDestroy()
    }

    private fun runVpn() {
        val fd = establishVpn() ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) continue
            val ip = PacketParser.parse(buf, n) ?: continue
            if (ip.dstPort != 53) continue
            val query = DnsParser.parse(ip.payload) ?: continue
            val response = runCatching { resolver.handle(ip, query) }.getOrNull() ?: continue
            try {
                output.write(response)
            } catch (_: Exception) {
                // Tunnel closed; loop will exit on next read.
            }
        }
    }

    private fun establishVpn(): ParcelFileDescriptor? = runCatching {
        Builder().apply {
            setSession("AdBlocker")
            addAddress("10.10.10.1", 32)
            addRoute("10.10.10.2", 32) // only the fake DNS server is routed through the TUN
            addDnsServer("10.10.10.2")
            addDisallowedApplication(packageName) // exclude ourselves
            setMtu(1500)
        }.establish()
    }.getOrNull()

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            getString(R.string.notification_channel_id),
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .build()
    }
}
