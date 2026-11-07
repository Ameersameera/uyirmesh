package com.ameer.uyirmesh.sos

import com.ameer.uyirmesh.util.CrashGuard
import android.content.Context
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.Triggers
import com.ameer.uyirmesh.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class Countdown(val active: Boolean = false, val seconds: Int = 0, val trigger: String = "")

/**
 * Hands-free SOS: every trigger (voice, shake, widget, tile, shortcut, notification button) starts the same
 * 5-second countdown with Cancel / Send now, with beeps and vibration so the person knows it is coming.
 * When the voice trigger fires, a 15-second voice note is recorded and sent after the SOS.
 */
class SosTrigger(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashGuard)
    private var job: Job? = null

    private val _state = MutableStateFlow(Countdown())
    val state: StateFlow<Countdown> = _state

    private var pendingForm: SosForm? = null

    /**
     * Starts the countdown. Returns false when one is already running or an SOS was sent seconds ago.
     * [form] replaces the last-used form (picture SOS, fall, missed check-in); a silent form counts down with
     * vibration only, no beeps.
     */
    @Synchronized
    fun start(trigger: String, seconds: Int = 5, form: SosForm? = null): Boolean {
        if (_state.value.active) return false
        if (Graph.victim.recentlySent()) {
            Graph.log("SOS", "Trigger ${trigger.lowercase()} ignored: SOS was sent seconds ago")
            return false
        }
        Graph.log("SOS", "SOS countdown started by ${trigger.lowercase()} (${seconds} s)")
        pendingForm = form
        val silent = form?.silent == true
        job = scope.launch {
            for (s in seconds downTo 1) {
                _state.value = Countdown(true, s, trigger)
                Notifier.countdown(ctx, s, Victim.triggerName(trigger))
                Graph.alarm.vibrateTick()
                if (!silent) Graph.alarm.beep()
                delay(1_000)
            }
            fire(trigger)
        }
        return true
    }

    @Synchronized
    fun cancel() {
        if (!_state.value.active) return
        job?.cancel()
        job = null
        _state.value = Countdown()
        pendingForm = null
        Notifier.cancel(ctx, Notifier.ID_COUNTDOWN)
        Graph.log("SOS", "SOS countdown cancelled")
    }

    @Synchronized
    fun sendNow() {
        val t = _state.value.trigger.ifBlank { Triggers.BUTTON }
        job?.cancel()
        job = null
        scope.launch { fire(t) }
    }

    private fun fire(trigger: String) {
        _state.value = Countdown()
        val form = pendingForm
        pendingForm = null
        Notifier.cancel(ctx, Notifier.ID_COUNTDOWN)
        try {
            if (form != null) Graph.victim.sendSos(form, trigger) else Graph.victim.quickSos(trigger)
            if (trigger == Triggers.VOICE) VoiceNote.recordAndSend(ctx)
        } catch (e: Exception) {
            Graph.note("sos trigger", e)
        }
    }
}
