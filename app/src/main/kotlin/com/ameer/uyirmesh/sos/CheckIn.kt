package com.ameer.uyirmesh.sos

import android.content.Context
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.EmergencyKind
import com.ameer.uyirmesh.core.Triggers
import com.ameer.uyirmesh.mesh.MeshService
import com.ameer.uyirmesh.ui.L
import com.ameer.uyirmesh.util.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Check-in timer ("dead-man switch") for trekkers, fishermen, people living alone: the person picks a time,
 * and if they do not tap "I'm OK" before it runs out, the phone starts a 60-second SOS countdown by itself.
 * A reminder with an "I'm OK" button comes 5 minutes before. The deadline survives an app restart.
 */
object CheckIn {
    data class State(val deadline: Long, val intervalMin: Int, val reminded: Boolean = false)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    val CHOICES = listOf(15, 30, 60, 120, 240, 480)

    fun load() {
        val deadline = Graph.prefs.getLong(KEY_DEADLINE)
        val interval = Graph.prefs.getLong(KEY_INTERVAL).toInt()
        _state.value = if (deadline > 0 && interval > 0) State(deadline, interval) else null
    }

    fun start(ctx: Context, intervalMin: Int) {
        val st = State(System.currentTimeMillis() + intervalMin * 60_000L, intervalMin)
        save(st)
        MeshService.ensureRunning(ctx)
        Notifier.cancel(ctx, Notifier.ID_CHECKIN)
        Graph.log("SOS", "Check-in timer started: every $intervalMin min")
    }

    /** "I'm OK": the timer starts again from now. */
    fun ok(ctx: Context) {
        val st = _state.value ?: return
        save(State(System.currentTimeMillis() + st.intervalMin * 60_000L, st.intervalMin))
        Notifier.cancel(ctx, Notifier.ID_CHECKIN)
        Graph.log("SOS", "Checked in (I'm OK)")
    }

    fun stop(ctx: Context) {
        save(null)
        Notifier.cancel(ctx, Notifier.ID_CHECKIN)
        Graph.log("SOS", "Check-in timer stopped")
    }

    /** Called about every 30 seconds by the victim loop. */
    fun tick(ctx: Context, now: Long = System.currentTimeMillis()) {
        val st = _state.value ?: return
        val left = st.deadline - now
        if (left <= 0) {
            save(null)
            Notifier.cancel(ctx, Notifier.ID_CHECKIN)
            Graph.log("SOS", "Check-in missed: starting the SOS countdown")
            val last = Graph.victim.lastForm()
            val note = L.t("checkin_missed_note").replace("{t}", timeOf(st.deadline))
            val form = last.copy(note = listOf(note, last.note).filter { it.isNotBlank() }.joinToString(" · ").take(300))
            Graph.trigger.start(Triggers.CHECKIN, seconds = 60, form = if (last.kind.isBlank()) form.copy(kind = EmergencyKind.TRAPPED) else form)
        } else if (left <= REMIND_MS && !st.reminded) {
            save(st.copy(reminded = true))
            Notifier.checkIn(ctx, L.t("checkin_reminder_title"), L.t("checkin_reminder_text").replace("{t}", timeOf(st.deadline)))
            Graph.alarm.beep()
            Graph.alarm.vibrateTick()
        }
    }

    fun timeOf(at: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))

    private fun save(st: State?) {
        _state.value = st
        Graph.prefs.putLong(KEY_DEADLINE, st?.deadline ?: 0L)
        Graph.prefs.putLong(KEY_INTERVAL, (st?.intervalMin ?: 0).toLong())
    }

    private const val KEY_DEADLINE = "checkin_deadline"
    private const val KEY_INTERVAL = "checkin_interval"
    private const val REMIND_MS = 5 * 60_000L
}
