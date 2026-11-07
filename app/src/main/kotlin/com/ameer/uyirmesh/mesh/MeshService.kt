package com.ameer.uyirmesh.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.Roles
import com.ameer.uyirmesh.core.Triggers
import com.ameer.uyirmesh.core.EmergencyKind
import com.ameer.uyirmesh.sos.FallDetector
import com.ameer.uyirmesh.sos.ShakeDetector
import com.ameer.uyirmesh.sos.VoiceTrigger
import com.ameer.uyirmesh.ui.L
import com.ameer.uyirmesh.util.Notifier
import com.ameer.uyirmesh.util.Perms
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the mesh running with the screen off: radio links, relaying,
 * voice and shake SOS, GPS for an open SOS or a rescuer on duty, and the base dashboard server.
 */
class MeshService : LifecycleService() {
    private var voice: VoiceTrigger? = null
    private var shake: ShakeDetector? = null
    private var fall: FallDetector? = null
    private var sensors: SensorScanner? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTypes = -1
    private var foregroundStart = false

    override fun onCreate() {
        super.onCreate()
        running = true
        goForeground(fromForeground = false)
        Graph.mesh.start()
        lifecycleScope.launch {
            combine(Graph.mesh.stats, Graph.prefs.settings) { st, s -> Triple(st.peers, st.waitingOwn, s.role) }
                .distinctUntilChanged()
                .collect { (peers, waiting, role) ->
                    try {
                        updateNotification(peers, waiting, role)
                    } catch (e: Exception) {
                        Graph.note("notification", e)
                    }
                }
        }
        lifecycleScope.launch {
            Graph.prefs.settings.collect {
                try {
                    applySettings()
                } catch (e: Exception) {
                    Graph.note("apply settings", e)
                }
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(60_000)
                try {
                    Graph.base.webServerCheck()
                } catch (e: Exception) {
                    Graph.note("web check", e)
                }
            }
        }
        // Internet relay: whenever this phone has internet, exchange packets with the relay server.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            delay(15_000)
            while (true) {
                try {
                    Graph.gateway.cycle()
                } catch (e: Exception) {
                    Graph.note("internet relay", e)
                }
                delay(30_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val fromForeground = intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true
        if (fromForeground) foregroundStart = true
        goForeground(fromForeground)
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER -> Graph.trigger.start(intent.getStringExtra(EXTRA_TRIGGER) ?: Triggers.NOTIFICATION)
        }
        applySettings()
        return START_STICKY
    }

    /** Starts (or upgrades) the foreground state with only the service types this phone can use now. */
    private fun goForeground(fromForeground: Boolean) {
        val canWhileInUse = fromForeground || foregroundStart
        var types = 0
        if (Build.VERSION.SDK_INT >= 29) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (canWhileInUse && Perms.hasLocation(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (Build.VERSION.SDK_INT >= 30 && canWhileInUse && Perms.hasMic(this) && voiceWanted()) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        // startForeground must be called after every startForegroundService(), even when nothing changed.
        val n = Notifier.mesh(this, L.t("notif_mesh_title"), lastText.ifBlank { L.t("notif_mesh_starting") })
        val basic = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val attempts = listOf(types, currentTypes, basic).filter { it >= 0 }.distinct()
        for (t in attempts) {
            try {
                ServiceCompat.startForeground(this, Notifier.ID_MESH, n, t)
                currentTypes = t
                return
            } catch (e: Exception) {
                if (t == attempts.last()) Graph.note("startForeground", e)
            }
        }
    }

    private var lastText = ""

    private fun micAllowed(): Boolean =
        Build.VERSION.SDK_INT < 30 || (currentTypes != -1 && currentTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)

    private fun voiceWanted(): Boolean {
        val s = Graph.prefs.now
        return s.voice && !s.batterySaver && s.role != Roles.BASE
    }

    private fun applySettings() {
        val s = Graph.prefs.now
        Graph.mesh.applyRole()
        // Voice SOS. If it was switched on after the service started (settings, leaving base mode),
        // add the microphone type to the running service first; this works while the app is open.
        if (voiceWanted() && Perms.hasMic(this) && !micAllowed() && foregroundStart) goForeground(fromForeground = true)
        if (voiceWanted() && Perms.hasMic(this) && micAllowed()) {
            if (voice == null) voice = VoiceTrigger(this) { Graph.trigger.start(Triggers.VOICE) }.also { it.start() }
        } else {
            voice?.stop()
            voice = null
        }
        // Shake SOS
        val wantShake = s.shake && !s.batterySaver && s.role != Roles.BASE
        if (wantShake) {
            if (shake == null) shake = ShakeDetector(this) { Graph.trigger.start(Triggers.SHAKE) }
            shake?.start()
        } else {
            shake?.stop()
            shake = null
        }
        // Fall / crash detection ("man down" for rescuers).
        val wantFall = s.fallDetect && s.role != Roles.BASE
        if (wantFall) {
            if (fall == null) fall = FallDetector(this) { onFall() }
            fall?.start()
        } else {
            fall?.stop()
            fall = null
        }
        // Bluetooth sensor nodes (water level, rain, gas) heard nearby are passed into the mesh.
        if (s.sensorScan) {
            if (sensors == null) sensors = SensorScanner(this)
            sensors?.start()
        } else {
            sensors?.stop()
            sensors = null
        }
        // Keep the CPU awake for shake and fall detection with the screen off (voice listening keeps it awake by itself).
        setWakeLock(wantShake || wantFall || s.role == Roles.BASE)
        Graph.base.webServerCheck()
    }

    /** A hard fall: 30-second countdown (easy to cancel if the phone was only dropped), then an SOS. */
    private fun onFall() {
        val s = Graph.prefs.now
        val rescuer = if (s.role == Roles.RESCUER) Graph.rescuer.bundle() else null
        val note = if (rescuer != null) {
            L.t("man_down_note").replace("{n}", rescuer.cert.name).replace("{t}", rescuer.cert.team)
        } else {
            L.t("fall_note")
        }
        val last = Graph.victim.lastForm()
        val form = last.copy(kind = EmergencyKind.MEDICAL, injured = true, note = note, people = if (rescuer != null) 1 else last.people)
        Graph.log("SOS", if (rescuer != null) "Man-down alarm (fall detected)" else "Fall detected")
        Graph.trigger.start(Triggers.FALL, seconds = 30, form = form)
    }

    @SuppressLint("WakelockTimeout")
    private fun setWakeLock(on: Boolean) {
        if (on && wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UyirMesh:mesh").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                Graph.note("wakelock", e)
            }
        } else if (!on && wakeLock != null) {
            try {
                wakeLock?.release()
            } catch (_: Exception) {
            }
            wakeLock = null
        }
    }

    private fun updateNotification(peers: Int, waiting: Int, role: String) {
        val title = when (role) {
            Roles.BASE -> L.t("notif_mesh_title_base")
            Roles.RESCUER -> L.t("notif_mesh_title_rescuer")
            else -> L.t("notif_mesh_title")
        }
        val text = buildString {
            append(L.t("notif_peers").replace("{n}", peers.toString()))
            if (waiting > 0) append(" · ").append(L.t("notif_waiting").replace("{n}", waiting.toString()))
        }
        lastText = text
        Notifier.updateMesh(this, title, text)
    }

    private fun stopEverything() {
        voice?.stop()
        voice = null
        shake?.stop()
        shake = null
        fall?.stop()
        fall = null
        sensors?.stop()
        sensors = null
        setWakeLock(false)
        Graph.base.stopWebServer()
        Graph.mesh.stop()
    }

    override fun onDestroy() {
        stopEverything()
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.ameer.uyirmesh.START"
        const val ACTION_STOP = "com.ameer.uyirmesh.STOP"
        const val ACTION_TRIGGER = "com.ameer.uyirmesh.TRIGGER"
        const val EXTRA_FOREGROUND = "foreground"
        const val EXTRA_TRIGGER = "trigger"

        @Volatile var running = false
            private set

        /** [fromForeground] = called while the app is on screen (allows GPS and microphone service types). */
        fun start(ctx: Context, fromForeground: Boolean) {
            val i = Intent(ctx, MeshService::class.java).setAction(ACTION_START).putExtra(EXTRA_FOREGROUND, fromForeground)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                Graph.note("start service", e)
            }
        }

        fun ensureRunning(ctx: Context) {
            if (!running) start(ctx, fromForeground = false)
        }

        fun trigger(ctx: Context, trigger: String) {
            val i = Intent(ctx, MeshService::class.java).setAction(ACTION_TRIGGER).putExtra(EXTRA_TRIGGER, trigger)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Service could not start from here; run the countdown in-process instead.
                Graph.trigger.start(trigger)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, MeshService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
            }
        }
    }
}
