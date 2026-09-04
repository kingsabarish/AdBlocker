package com.adblocker.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.adblocker.MainActivity
import com.adblocker.R
import com.adblocker.data.AppSettings
import com.adblocker.data.VpnState
import com.adblocker.filter.BlocklistManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class AdBlockVpnService : VpnService() {
    @Volatile private var running = false
    @Volatile private var reloadRequested = false
    @Volatile private var tunFd: ParcelFileDescriptor? = null
    private val oomHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is OutOfMemoryError) {
            Log.e(TAG, "OOM in coroutine, recovering")
            System.gc()
        }
    }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + oomHandler)
    private var vpnJob: Job? = null
    private val resolver = DnsResolver(this)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null

    companion object {
        const val ACTION_STOP = "com.adblocker.action.STOP"
        const val ACTION_RELOAD = "com.adblocker.action.RELOAD"
        const val ACTION_RELOAD_BLOCKLISTS = "com.adblocker.action.RELOAD_BLOCKLISTS"
        private const val TAG = "AdBlock/Vpn"
    }

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "onStartCommand STOP")
            cleanup()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELOAD) {
            Log.i(TAG, "onStartCommand RELOAD")
            reloadRequested = true
            runCatching { tunFd?.close() }
            return START_STICKY
        }
        if (intent?.action == ACTION_RELOAD_BLOCKLISTS) {
            Log.i(TAG, "onStartCommand RELOAD_BLOCKLISTS")
            if (running) {
                scope.launch {
                    val snap = AppSettings.snapshot(this@AdBlockVpnService)
                    try {
                        BlocklistManager.load(this@AdBlockVpnService, snap.customUrls, snap.customRules)
                        Log.i(TAG, "blocklists reloaded dynamically")
                    } catch (_: OutOfMemoryError) {
                        Log.e(TAG, "OOM reloading blocklists")
                        System.gc()
                    } catch (_: Exception) {
                        Log.e(TAG, "dynamic blocklist reload failed")
                    }
                }
            }
            return START_STICKY
        }
        if (!running) {
            running = true
            VpnState.setConnecting(this@AdBlockVpnService, true)
            VpnState.resetBlocked()
            // Cancel old scope and wait for old job to finish on a background thread
            // so memory from the old blocklist load is freed before the new one starts.
            val oldScope = scope
            val oldJob = vpnJob
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + oomHandler)
            vpnJob = scope.launch {
                // Wait for old job to complete (with timeout to avoid deadlock)
                if (oldJob?.isActive == true) {
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(5000) { oldJob.join() }
                    } catch (_: Exception) { }
                }
                oldScope.cancel()
                try {
                    runVpn()
                } catch (e: CancellationException) {
                    Log.i(TAG, "runVpn cancelled")
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "runVpn OOM: ${e.message}")
                    System.gc()
                    VpnState.setActive(this@AdBlockVpnService, false)
                    VpnState.setConnecting(this@AdBlockVpnService, false)
                } catch (e: Exception) {
                    Log.e(TAG, "runVpn crashed: ${e.message}", e)
                    VpnState.setActive(this@AdBlockVpnService, false)
                    VpnState.setConnecting(this@AdBlockVpnService, false)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        cleanup()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke")
        cleanup()
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved")
        cleanup()
        super.onTaskRemoved(rootIntent)
    }

    private fun cleanup() {
        running = false
        VpnState.setActive(this, false)
        VpnState.setConnecting(this, false)
        runCatching { tunFd?.close() }
        tunFd = null
        vpnJob?.cancel()
        scope.cancel()
        unregisterNetworkCallback()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == currentNetwork) {
                    Log.i(TAG, "Underlying network lost, requesting VPN reload")
                    reloadRequested = true
                    runCatching { tunFd?.close() }
                }
            }

            override fun onUnavailable() {
                Log.i(TAG, "Network unavailable, requesting VPN reload")
                reloadRequested = true
                runCatching { tunFd?.close() }
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
            } catch (_: Exception) { }
            networkCallback = null
        }
        currentNetwork = null
    }

    private suspend fun runVpn() {
        while (running) {
            yield()
            val snap = AppSettings.snapshot(this)
            val fd = establishVpn(snap.bypassApps) ?: run {
                VpnState.setConnecting(this@AdBlockVpnService, false)
                VpnState.setActive(this@AdBlockVpnService, false)
                delay(1000)
                continue
            }
            tunFd = fd
            currentNetwork = getCurrentNetwork()
            VpnState.setActive(this@AdBlockVpnService, true)
            Log.i(TAG, "tunnel established, bypassApps=${snap.bypassApps.size}, network=${currentNetwork}")

            try {
                BlocklistManager.load(this, snap.customUrls, snap.customRules)
                Log.i(TAG, "blocklists loaded")
            } catch (_: OutOfMemoryError) {
                Log.e(TAG, "OOM loading blocklists, VPN stays up with partial/empty blocklist")
                System.gc()
            } catch (_: Exception) {
                Log.e(TAG, "blocklist load failed, VPN stays up")
            }

            var input: FileInputStream? = null
            var output: FileOutputStream? = null
            try {
                input = FileInputStream(fd.fileDescriptor)
                output = FileOutputStream(fd.fileDescriptor)
                val buf = ByteArray(32767)

                while (running) {
                    if (reloadRequested || hasNetworkChanged()) {
                        reloadRequested = false
                        break
                    }
                    val n = try { input.read(buf) } catch (_: Exception) { -1 }
                    if (n <= 0) {
                        if (reloadRequested) { reloadRequested = false; break }
                        delay(50)
                        continue
                    }
                    val ip = try {
                        PacketParser.parse(buf, n)
                    } catch (e: Exception) {
                        Log.w(TAG, "parse error: ${e.message?.take(60)}")
                        null
                    } ?: continue
                    if (ip.dstPort != 53) continue
                    val query = try {
                        DnsParser.parse(ip.payload)
                    } catch (e: Exception) {
                        Log.w(TAG, "dns parse error: ${e.message?.take(60)}")
                        null
                    } ?: continue
                    val response = try {
                        resolver.handle(ip, query)
                    } catch (e: Exception) {
                        Log.w(TAG, "dns handle error: ${e.message?.take(60)}")
                        resolver.buildErrorResponse(ip, query)
                    }
                    if (response.isNotEmpty()) {
                        runCatching { output.write(response) }
                    }
                }
            } finally {
                runCatching { input?.close() }
                runCatching { output?.close() }
                runCatching { fd.close() }
            }
        }
    }

    private fun establishVpn(bypassApps: Set<String>): ParcelFileDescriptor? = runCatching {
        Log.i(TAG, "establishVpn bypassApps=${bypassApps.size}: $bypassApps")
        Builder().apply {
            setSession("AdBlocker")
            addAddress("10.10.10.1", 32)
            addRoute("10.10.10.2", 32)
            addDnsServer("10.10.10.2")
            addDisallowedApplication(packageName)
            for (pkg in bypassApps) {
                try { addDisallowedApplication(pkg) }
                catch (_: PackageManager.NameNotFoundException) { Log.w(TAG, "bypass app not found: $pkg") }
            }
            setMtu(1500)
        }.establish()
    }.getOrNull()

    private fun getCurrentNetwork(): Network? {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.activeNetwork
        } catch (_: Exception) {
            null
        }
    }

    private fun hasNetworkChanged(): Boolean {
        val newNetwork = getCurrentNetwork()
        return currentNetwork != newNetwork
    }

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
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .build()
    }
}
