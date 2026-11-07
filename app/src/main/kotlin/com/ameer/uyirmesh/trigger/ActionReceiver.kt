package com.ameer.uyirmesh.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.Triggers
import com.ameer.uyirmesh.mesh.MeshService
import com.ameer.uyirmesh.sos.CheckIn
import com.ameer.uyirmesh.ui.L

/** Buttons inside notifications (they also work on the lock screen). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        when (intent.action) {
            ACTION_SOS -> MeshService.trigger(context, Triggers.NOTIFICATION)
            ACTION_CANCEL_COUNTDOWN -> Graph.trigger.cancel()
            ACTION_SEND_NOW -> Graph.trigger.sendNow()
            ACTION_STOP_SIREN -> Graph.alarm.stopAll()
            ACTION_CHECKIN_OK -> CheckIn.ok(context)
            ACTION_SAFE -> {
                MeshService.ensureRunning(context)
                Graph.public.sendSafe(L.t("safe_default_message"), "")
            }
        }
    }

    companion object {
        const val ACTION_SOS = "com.ameer.uyirmesh.notif.SOS"
        const val ACTION_CANCEL_COUNTDOWN = "com.ameer.uyirmesh.notif.CANCEL"
        const val ACTION_SEND_NOW = "com.ameer.uyirmesh.notif.SEND_NOW"
        const val ACTION_STOP_SIREN = "com.ameer.uyirmesh.notif.STOP_SIREN"
        const val ACTION_SAFE = "com.ameer.uyirmesh.notif.SAFE"
        const val ACTION_CHECKIN_OK = "com.ameer.uyirmesh.notif.CHECKIN_OK"
    }
}
