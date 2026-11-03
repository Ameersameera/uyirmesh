package com.ameer.uyirmesh.sos

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.util.Perms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Records a voice note of up to 15 seconds in AMR-NB (12.2 kbit/s, about 1.6 KB per second) so it fits
 * in a single mesh packet, then sends it with the open SOS.
 */
object VoiceNote {
    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var onFinished: ((Boolean) -> Unit)? = null
    private var waitedForMic = false

    private val _seconds = MutableStateFlow(-1)
    /** Seconds recorded so far, or -1 when not recording. */
    val seconds: StateFlow<Int> = _seconds

    val recording: Boolean get() = recorder != null

    fun recordAndSend(ctx: Context, maxSeconds: Int = 15, done: ((Boolean) -> Unit)? = null) {
        main.post { start(ctx.applicationContext, maxSeconds, done) }
    }

    fun stopNow() {
        main.post { finish(send = true) }
    }

    fun discard() {
        main.post { finish(send = false) }
    }

    private fun start(ctx: Context, maxSeconds: Int, done: ((Boolean) -> Unit)?) {
        if (recorder != null) return
        if (!Perms.hasMic(ctx)) {
            Graph.log("SOS", "Voice note skipped: microphone permission missing")
            done?.invoke(false)
            return
        }
        val voice = VoiceTrigger.current
        if (voice != null && !waitedForMic) {
            // Free the microphone first, then start recording a moment later.
            voice.pause()
            waitedForMic = true
            main.postDelayed({ start(ctx, maxSeconds, done) }, 500)
            return
        }
        waitedForMic = false
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.amr")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(8000)
            r.setAudioEncodingBitRate(12200)
            r.setMaxDuration(maxSeconds * 1000)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish(send = true)
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
            Graph.log("SOS", "Voice note failed: ${e.message}")
            done?.invoke(false)
            return
        }
        recorder = r
        file = f
        startedAt = System.currentTimeMillis()
        onFinished = done
        _seconds.value = 0
        Graph.log("SOS", "Recording voice note")
        tick(maxSeconds)
    }

    private fun tick(maxSeconds: Int) {
        if (recorder == null) return
        val s = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        _seconds.value = s
        if (s > maxSeconds + 1) {
            finish(send = true)
            return
        }
        main.postDelayed({ tick(maxSeconds) }, 500)
    }

    private fun finish(send: Boolean) {
        val r = recorder ?: return
        recorder = null
        val duration = System.currentTimeMillis() - startedAt
        var ok = false
        try {
            r.stop()
            ok = true
        } catch (e: RuntimeException) {
            // Stopped before any audio was written.
        }
        try {
            r.release()
        } catch (_: Exception) {
        }
        val f = file
        file = null
        _seconds.value = -1
        var sent = false
        if (ok && send && f != null && f.exists()) {
            val bytes = f.readBytes()
            sent = Graph.victim.sendMedia("audio/amr", duration, bytes)
        }
        f?.delete()
        VoiceTrigger.current?.resume()
        onFinished?.invoke(sent)
        onFinished = null
    }
}
