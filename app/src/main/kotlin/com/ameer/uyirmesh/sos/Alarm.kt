package com.ameer.uyirmesh.sos

import com.ameer.uyirmesh.core.AckStatus
import com.ameer.uyirmesh.util.CrashGuard
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ameer.uyirmesh.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * "Find me" tools: loud siren on the alarm channel (plays even when the ringer is silent),
 * flashlight blinking SOS in Morse code, vibration patterns and countdown beeps.
 */
class Alarm(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashGuard)
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _siren = MutableStateFlow(false)
    val siren: StateFlow<Boolean> = _siren
    private val _torch = MutableStateFlow(false)
    val torch: StateFlow<Boolean> = _torch

    private var track: AudioTrack? = null
    private var savedVolume = -1
    private var torchJob: Job? = null
    private var sirenStopJob: Job? = null

    @Synchronized
    fun startSiren(maxMinutes: Int = 10) {
        if (_siren.value) return
        try {
            val rate = 22_050
            val seconds = 1.6
            val frames = (rate * seconds).toInt()
            val pcm = ShortArray(frames)
            var phase = 0.0
            for (i in 0 until frames) {
                // Rising and falling wail between 650 Hz and 1550 Hz.
                val t = i.toDouble() / frames
                val f = 650 + 900 * (if (t < 0.5) t * 2 else (1 - t) * 2)
                phase += 2 * PI * f / rate
                pcm[i] = (sin(phase) * 0.95 * Short.MAX_VALUE).toInt().toShort()
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(frames * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, frames)
            t.setLoopPoints(0, frames, -1)
            try {
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (_: Exception) {
                savedVolume = -1
            }
            t.play()
            track = t
            _siren.value = true
            Graph.log("ALARM", "Siren on")
            sirenStopJob?.cancel()
            sirenStopJob = scope.launch {
                delay(maxMinutes * 60_000L)
                stopSiren()
            }
        } catch (e: Exception) {
            Graph.note("siren", e)
            stopSiren()
        }
    }

    @Synchronized
    fun stopSiren() {
        sirenStopJob?.cancel()
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        if (savedVolume >= 0) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: Exception) {
            }
            savedVolume = -1
        }
        if (_siren.value) Graph.log("ALARM", "Siren off")
        _siren.value = false
    }

    private fun torchCamera(cm: CameraManager): String? = try {
        cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        null
    }

    fun hasTorch(): Boolean = torchCamera(ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager) != null

    /** Blinks · · · — — — · · · until stopped. */
    @Synchronized
    fun startTorchSos() {
        if (torchJob?.isActive == true) return
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = torchCamera(cm) ?: run {
            Graph.log("ALARM", "This phone has no flashlight")
            return
        }
        _torch.value = true
        torchJob = scope.launch {
            val unit = 200L
            val letters = listOf(listOf(1, 1, 1), listOf(3, 3, 3), listOf(1, 1, 1))
            try {
                while (isActive) {
                    for ((li, letter) in letters.withIndex()) {
                        for ((si, len) in letter.withIndex()) {
                            cm.setTorchMode(id, true)
                            delay(unit * len)
                            cm.setTorchMode(id, false)
                            if (si < letter.size - 1) delay(unit)
                        }
                        if (li < letters.size - 1) delay(unit * 3)
                    }
                    delay(unit * 7)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    cm.setTorchMode(id, false)
                } catch (_: Exception) {
                }
            }
        }
        Graph.log("ALARM", "Flashlight SOS on")
    }

    @Synchronized
    fun stopTorch() {
        torchJob?.cancel()
        torchJob = null
        if (_torch.value) Graph.log("ALARM", "Flashlight SOS off")
        _torch.value = false
    }

    fun stopAll() {
        stopSiren()
        stopTorch()
    }

    private fun vibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (e: Exception) {
        null
    }

    fun vibrate(pattern: LongArray) {
        try {
            vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    /** Short buzz patterns rescuers and victims can tell apart. */
    fun vibrateSent() = vibrate(longArrayOf(0, 120, 80, 120))
    fun vibrateTick() = vibrate(longArrayOf(0, 180))
    fun vibrateReply() = vibrate(longArrayOf(0, 500, 200, 500, 200, 500))

    /**
     * Vibration codes, so a person who cannot look at the screen (or must stay silent) still knows what happened:
     * one long = reached the base, two short = team assigned, three short = help on the way,
     * long-short-long = the rescuer is very close, four short = rescued.
     */
    fun vibrateCode(status: String) = vibrate(
        when (status) {
            AckStatus.RECEIVED -> longArrayOf(0, 700)
            AckStatus.ASSIGNED -> longArrayOf(0, 200, 150, 200)
            AckStatus.ON_THE_WAY -> longArrayOf(0, 200, 150, 200, 150, 200)
            AckStatus.RESCUED -> longArrayOf(0, 200, 150, 200, 150, 200, 150, 200)
            NEAR -> longArrayOf(0, 600, 150, 200, 150, 600)
            else -> longArrayOf(0, 500, 200, 500, 200, 500)
        }
    )
    fun vibrateSos() = vibrate(longArrayOf(0, 150, 100, 150, 100, 150, 300, 450, 100, 450, 100, 450, 300, 150, 100, 150, 100, 150))

    fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            scope.launch {
                delay(400)
                tg.release()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Vibration code for "the rescuer is within about 150 m". */
        const val NEAR = "NEAR"
    }
}
