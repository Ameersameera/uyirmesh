package com.ameer.uyirmesh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ameer.uyirmesh.mesh.MeshService
import com.ameer.uyirmesh.ui.Nav
import com.ameer.uyirmesh.ui.UyirRoot

class MainActivity : ComponentActivity() {
    /** The screen back stack (the on-device smoke test reads it). */
    lateinit var nav: Nav
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nav = Nav(if (Graph.prefs.now.onboarded) "home" else "onboarding")
        handleIntent(intent)
        setContent { UyirRoot(nav) }
    }

    override fun onResume() {
        super.onResume()
        if (Graph.prefs.now.onboarded) MeshService.start(this, fromForeground = true)
        Graph.rescuer.checkAutoLock()
    }

    override fun onPause() {
        super.onPause()
        Graph.store.flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        Graph.rescuer.touch()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null || !Graph.prefs.now.onboarded) return
        if (i.action == ACTION_SAFE) {
            nav.go("safe")
            return
        }
        val open = i.getStringExtra(EXTRA_OPEN)
        if (open != null && open.startsWith("poll/")) nav.go(open)
        when (open) {
            "team_chat" -> nav.go("team_chat")
            "sos_status" -> nav.go("sos_status")
            "alerts" -> nav.go("alerts")
            "chat" -> nav.go("chat")
            "missing" -> nav.go("missing")
            "checkin" -> nav.go("checkin")
            "base", "rescuer" -> nav.replace("home")
        }
        i.removeExtra(EXTRA_OPEN)
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val ACTION_SAFE = "com.ameer.uyirmesh.action.SAFE"
    }
}
