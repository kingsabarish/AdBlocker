package com.adblocker

import android.app.Application
import com.adblocker.filter.BlocklistManager

class AdBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load the bundled default blocklist (hosts format) into the trie.
        BlocklistManager.init(this)
    }
}
