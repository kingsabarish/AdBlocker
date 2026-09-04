package com.adblocker

import android.app.Application
import com.adblocker.data.AppSettings
import com.adblocker.data.VpnState
import com.adblocker.filter.BlocklistManager

class AdBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.appContext = this
        VpnState.init(this)
        BlocklistManager.init(this)
    }
}
