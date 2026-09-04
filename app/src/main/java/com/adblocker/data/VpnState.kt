package com.adblocker.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide VPN/UI state. The [AdBlockVpnService] writes here; the UI reads it.
 *
 * Active/connecting state is persisted as a simple marker file ("vpn_active" / "vpn_connecting")
 * in the app's filesDir.  File existence = true, absence = false.
 * Works reliably across processes because every process reads the same inode directly.
 */
object VpnState {
    private const val FILE_ACTIVE = "vpn_active"
    private const val FILE_CONNECTING = "vpn_connecting"

    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    private val _blockedCount = AtomicLong(0)
    val blockedCount: kotlinx.coroutines.flow.StateFlow<Long>
        get() = _blockedCountFlow.asStateFlow()
    private val _blockedCountFlow = MutableStateFlow(0L)

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        // No-op: file reads use the passed-in context directly.
    }

    fun setActive(context: Context, active: Boolean) {
        _isActive.value = active
        if (active) _isConnecting.value = false
        val dir = context.applicationContext.filesDir ?: return
        if (active) {
            File(dir, FILE_ACTIVE).writeText("1")
            File(dir, FILE_CONNECTING).delete()
        } else {
            File(dir, FILE_ACTIVE).delete()
        }
    }

    fun setConnecting(context: Context, connecting: Boolean) {
        _isConnecting.value = connecting
        val dir = context.applicationContext.filesDir ?: return
        if (connecting) {
            File(dir, FILE_CONNECTING).writeText("1")
        } else {
            File(dir, FILE_CONNECTING).delete()
        }
    }

    /** Read persisted active state — safe from any process. */
    fun readPersistedActive(context: Context): Boolean {
        return try {
            File(context.applicationContext.filesDir, FILE_ACTIVE).exists()
        } catch (_: Exception) {
            _isActive.value
        }
    }

    fun readPersistedConnecting(context: Context): Boolean {
        return try {
            File(context.applicationContext.filesDir, FILE_CONNECTING).exists()
        } catch (_: Exception) {
            _isConnecting.value
        }
    }

    fun incrementBlocked() {
        val new = _blockedCount.incrementAndGet()
        _blockedCountFlow.value = new
    }

    fun resetBlocked() {
        _blockedCount.set(0)
        _blockedCountFlow.value = 0
    }
}
