package com.ameer.uyirmesh.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.mesh.MeshService
import com.ameer.uyirmesh.util.Perms

/** Restarts the mesh after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Graph.init(context)
        if (!Graph.prefs.now.onboarded || !Perms.meshReady(context)) return
        Graph.log("APP", if (a == Intent.ACTION_BOOT_COMPLETED) "Phone restarted: mesh started" else "App updated: mesh started")
        MeshService.start(context, fromForeground = false)
    }
}
