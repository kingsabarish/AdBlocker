package com.adblocker.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide VPN/UI state. The [AdBlockVpnService] writes here; the UI reads
 * it. Kept as a plain object (no DI) because it is global singleton state.
 */
object VpnState {
    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    private val _blockedCount = MutableStateFlow(0L)
    val blockedCount = _blockedCount.asStateFlow()

    fun setActive(active: Boolean) {
        _isActive.value = active
    }

    fun incrementBlocked() {
        _blockedCount.value += 1
    }

    fun resetBlocked() {
        _blockedCount.value = 0L
    }
}
