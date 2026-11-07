package com.ameer.uyirmesh.sos

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.Json
import com.ameer.uyirmesh.core.str
import com.ameer.uyirmesh.util.Perms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Speak-to-fill for the SOS form: listens for up to [MAX_SECONDS] seconds with the same offline speech model
 * as "help help" (Indian English, on the phone, no internet) and returns the words. The "help help" listener is
 * paused meanwhile so both do not fight over the microphone.
 */
object VoiceDictation {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Listening(val secondsLeft: Int, val text: String) : State
        data class Done(val text: String) : State
        data class Failed(val reason: String) : State
    }

    const val MAX_SECONDS = 12
    const val FAIL_PERMISSION = "no_permission"
    const val FAIL_MODEL = "no_model"
    const val FAIL_MIC = "mic_busy"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var running = false

    fun start(ctx: Context) {
        if (running) return
        if (!Perms.hasMic(ctx)) {
            _state.value = State.Failed(FAIL_PERMISSION)
            return
        }
        running = true
        _state.value = State.Loading
        VoiceTrigger.current?.pause()
        VoiceTrigger.withModel(ctx) { m ->
            if (m == null) {
                finish(State.Failed(FAIL_MODEL))
            } else if (!running) {
                finish(State.Idle)
            } else {
                Thread({ listen(m) }, "voice-form").start()
            }
        }
    }

    /** Stop listening early; the words heard so far are kept. */
    fun stop() {
        running = false
    }

    fun clear() {
        if (!running) _state.value = State.Idle
    }

    @SuppressLint("MissingPermission")
    private fun listen(m: Model) {
        var record: AudioRecord? = null
        var rec: Recognizer? = null
        val heard = StringBuilder()
        var result: State = State.Failed(FAIL_MIC)
        try {
            rec = Recognizer(m, RATE.toFloat())
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val chunk = RATE / 5
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk * 2) * 2)
            if (record.state != AudioRecord.STATE_INITIALIZED) return
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return
            val started = System.currentTimeMillis()
            val buf = ShortArray(chunk)
            var partial = ""
            while (running) {
                val elapsed = System.currentTimeMillis() - started
                if (elapsed >= MAX_SECONDS * 1000L) break
                val n = record.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                if (rec.acceptWaveForm(buf, n)) {
                    add(heard, textOf(rec.result, "text"))
                    partial = ""
                } else {
                    partial = textOf(rec.partialResult, "partial")
                }
                _state.value = State.Listening(MAX_SECONDS - (elapsed / 1000).toInt(), "$heard $partial".trim())
            }
            add(heard, textOf(rec.finalResult, "text"))
            result = State.Done(heard.toString().trim())
            Graph.log("VOICE", "Speak-to-fill heard \"${heard.toString().trim().take(120)}\"")
        } catch (t: Throwable) {
            result = State.Failed(FAIL_MIC)
        } finally {
            try {
                record?.stop()
            } catch (_: Throwable) {
            }
            try {
                record?.release()
            } catch (_: Throwable) {
            }
            try {
                rec?.close()
            } catch (_: Throwable) {
            }
            finish(result)
        }
    }

    private fun add(sb: StringBuilder, text: String) {
        if (text.isBlank()) return
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(text.trim())
    }

    private fun textOf(json: String?, field: String): String = try {
        if (json.isNullOrBlank()) "" else Json.parseObject(json).str(field)
    } catch (e: Exception) {
        ""
    }

    private fun finish(st: State) {
        running = false
        _state.value = st
        VoiceTrigger.current?.resume()
    }

    private const val RATE = 16_000
}
