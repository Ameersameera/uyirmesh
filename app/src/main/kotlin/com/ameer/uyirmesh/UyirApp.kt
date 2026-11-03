package com.ameer.uyirmesh

import android.app.Application
import com.ameer.uyirmesh.util.CrashLog
import com.ameer.uyirmesh.util.Notifier

class UyirApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Graph.init(this)
        Notifier.createChannels(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            Graph.store.flush()
        } catch (_: Exception) {
        }
    }
}
