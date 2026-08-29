package com.adblocker.ui

import androidx.lifecycle.ViewModel
import com.adblocker.data.VpnState
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {
    val isActive: StateFlow<Boolean> = VpnState.isActive
    val blockedCount: StateFlow<Long> = VpnState.blockedCount
}
