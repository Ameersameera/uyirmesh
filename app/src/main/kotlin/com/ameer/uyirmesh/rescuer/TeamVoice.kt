package com.ameer.uyirmesh.rescuer

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.SosMedia
import com.ameer.uyirmesh.data.TeamMsg
import com.ameer.uyirmesh.sos.VoiceTrigger
import com.ameer.uyirmesh.ui.L
import com.ameer.uyirmesh.util.Notifier
import com.ameer.uyirmesh.util.Perms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Push-to-talk for rescue teams, like a walkie-talkie over the mesh: hold the button, speak up to 10 seconds,
 * release. The clip (AMR, about 1.5 KB a second) goes to the team encrypted with the team key, so only the team
 * and the base can hear it. Rescuers hear incoming clips at once; the base gets a play button in team chat.
 */
object TeamVoice {
    const val MAX_SECONDS = 10
    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var target: String = ""
    private var player: MediaPlayer? = null

    private val _seconds = MutableStateFlow(-1)
    val seconds: StateFlow<Int> = _seconds

    /** Starts recording a clip for [team] (rescuers: their own team; the base: the team chosen in team chat). */
    fun start(ctx: Context, team: String): Boolean {
        if (recorder != null || team.isBlank()) return false
        if (!Perms.hasMic(ctx)) return false
        VoiceTrigger.current?.pause()
        val f = File(ctx.cacheDir, "ptt_${System.currentTimeMillis()}.amr")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(8000)
            r.setAudioEncodingBitRate(
                try {
                    Graph.health.mediaLevel().voiceBitrate
                } catch (e: Exception) {
                    12_200
                },
            )
            r.setMaxDuration(MAX_SECONDS * 1000)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post { stop(send = true) }
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try {
                r.release()
            } catch (_: Exception) {
            }
            f.delete()
            VoiceTrigger.current?.resume()
            Graph.note("push to talk", e)
            return false
        }
        recorder = r
        file = f
        target = team
        startedAt = System.currentTimeMillis()
        _seconds.value = 0
        tick()
        return true
    }

    private fun tick() {
        if (recorder == null) return
        _seconds.value = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        main.postDelayed({ tick() }, 250)
    }

    /** Stops recording; with [send] the clip goes to the team (clips shorter than half a second are dropped). */
    fun stop(send: Boolean) {
        val r = recorder ?: return
        recorder = null
        _seconds.value = -1
        val duration = System.currentTimeMillis() - startedAt
        var ok = false
        try {
            r.stop()
            ok = true
        } catch (e: RuntimeException) {
        }
        try {
            r.release()
        } catch (_: Exception) {
        }
        val f = file
        file = null
        VoiceTrigger.current?.resume()
        if (ok && send && duration >= 500 && f != null && f.exists()) {
            val bytes = f.readBytes()
            if (bytes.size <= SosMedia.MAX_MEDIA_BYTES) sendClip(target, bytes, duration)
        }
        f?.delete()
    }

    private fun sendClip(team: String, bytes: ByteArray, durationMs: Long) {
        val now = System.currentTimeMillis()
        val bundle = Graph.rescuer.bundle()
        val (name, key) = if (bundle != null && bundle.cert.team == team) {
            bundle.cert.name to bundle.teamKey
        } else {
            L.t("rescue_base") to (Graph.base.teamKey(team, create = false) ?: return)
        }
        val clip = SosMedia(name, "audio/amr", durationMs, bytes, now)
        val p = Graph.mesh.outbox.team(PacketType.TEAM_VOICE, clip.toBytes(), team, key)
        Graph.mesh.send(p)
        val fileName = "ptt_${p.idHex}.amr"
        Graph.vault.write(fileName, bytes)
        val label = if (bundle != null) name else "$name → $team"
        Graph.store.teamChat.add(TeamMsg(p.idHex, Graph.prefs.now.deviceId, label, L.t("voice_clip"), now, true, fileName, durationMs))
        Graph.log("TEAM", "Push-to-talk clip sent to $team (${durationMs / 1000}s, ${bytes.size / 1024} KB)")
    }

    fun onClip(p: Packet, team: String, clip: SosMedia, toBase: Boolean, toRescuer: Boolean) {
        if (Graph.store.teamChat.value.any { it.id == p.idHex }) return
        if (!clip.mime.startsWith("audio") || clip.data.isEmpty()) return
        val fileName = "ptt_${p.idHex}.amr"
        Graph.vault.write(fileName, clip.data)
        val label = if (toBase && !toRescuer) "${clip.caseId} ($team)" else clip.caseId
        val msg = TeamMsg(p.idHex, p.src, label, L.t("voice_clip"), clip.sentAt.takeIf { it > 0 } ?: System.currentTimeMillis(), false, fileName, clip.durationMs)
        Graph.store.teamChat.add(msg)
        Notifier.message(Graph.app, Notifier.ID_TEAM, L.t("voice_clip") + ": " + label, "${clip.durationMs / 1000} s", if (toRescuer) "team_chat" else "base")
        // Rescuers hear clips at once, like a walkie-talkie.
        if (toRescuer) main.post { play(Graph.app, msg) }
    }

    fun play(ctx: Context, m: TeamMsg) {
        val bytes = Graph.vault.read(m.audio) ?: return
        try {
            player?.release()
            val f = File(ctx.cacheDir, "ptt_play.amr")
            f.writeBytes(bytes)
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Graph.note("play clip", e)
        }
    }
}
