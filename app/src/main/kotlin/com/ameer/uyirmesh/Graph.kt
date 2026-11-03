package com.ameer.uyirmesh

import com.ameer.uyirmesh.util.CrashGuard
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.ameer.uyirmesh.base.BaseBrain
import com.ameer.uyirmesh.core.PinnedBaseKeys
import com.ameer.uyirmesh.data.LogEntry
import com.ameer.uyirmesh.data.Prefs
import com.ameer.uyirmesh.data.Store
import com.ameer.uyirmesh.data.Vault
import com.ameer.uyirmesh.mesh.Mesh
import com.ameer.uyirmesh.mesh.PublicHub
import com.ameer.uyirmesh.mesh.Router
import com.ameer.uyirmesh.rescuer.Rescuer
import com.ameer.uyirmesh.sos.Alarm
import com.ameer.uyirmesh.sos.SosTrigger
import com.ameer.uyirmesh.sos.Victim
import com.ameer.uyirmesh.util.CrashLog
import com.ameer.uyirmesh.util.Loc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Simple service locator: one instance of every part of the app, created at start-up. */
object Graph {
    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var vault: Vault
        private set
    lateinit var store: Store
        private set
    lateinit var pinned: PinnedBaseKeys
        private set
    lateinit var loc: Loc
        private set
    lateinit var victim: Victim
        private set
    lateinit var public: PublicHub
        private set
    lateinit var base: BaseBrain
        private set
    lateinit var rescuer: Rescuer
        private set
    lateinit var router: Router
        private set
    lateinit var mesh: Mesh
        private set
    lateinit var trigger: SosTrigger
        private set
    lateinit var alarm: Alarm
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashGuard)

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount

    @Volatile private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        prefs = Prefs(app)
        vault = Vault(app)
        store = Store(vault, scope)
        store.load()
        pinned = PinnedBaseKeys.fromJson(app.assets.open("base_keys.json").use { String(it.readBytes(), Charsets.UTF_8) })
        loc = Loc(app)
        alarm = Alarm(app)
        victim = Victim(app)
        public = PublicHub(app)
        base = BaseBrain(app)
        rescuer = Rescuer(app)
        router = Router()
        mesh = Mesh(app, prefs.now.deviceId)
        trigger = SosTrigger(app)
        ready = true
        base.start()
        rescuer.start()
        victim.start()
        // Settings that change what the mesh does (role, hops, drill, battery saver) apply at once,
        // even while the background service is stopped.
        scope.launch {
            prefs.settings.collect {
                try {
                    mesh.applyRole()
                } catch (e: Exception) {
                    note("apply settings", e)
                }
            }
        }
    }

    fun log(tag: String, text: String) {
        if (!::store.isInitialized) return
        store.log.add(LogEntry(System.currentTimeMillis(), tag, text.take(300)))
    }

    fun note(where: String, e: Throwable) {
        if (::app.isInitialized) CrashLog.note(app, where, e)
        log("ERROR", "$where: ${e.javaClass.simpleName} ${e.message ?: ""}")
    }

    fun onPeersChanged(n: Int) {
        _peerCount.value = n
    }

    /**
     * Only for the on-device smoke test: trust a generated test base instead of the real one, so the test can
     * play the base, rescuers and victims with keys it made itself (the real base private key is never in the repo).
     */
    @VisibleForTesting
    fun usePinnedForTest(keys: PinnedBaseKeys) {
        pinned = keys
    }
}
