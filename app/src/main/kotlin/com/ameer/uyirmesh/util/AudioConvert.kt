package com.ameer.uyirmesh.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File

/** Decodes an AMR voice note to WAV so a laptop browser can play it (browsers cannot play AMR). */
object AudioConvert {
    fun amrToWav(amr: ByteArray, cacheDir: File): ByteArray? {
        val f = File.createTempFile("voice", ".amr", cacheDir)
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            f.writeBytes(amr)
            val ex = MediaExtractor().also { extractor = it }
            ex.setDataSource(f.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 8000
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val c = MediaCodec.createDecoderByType(mime).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 15_000
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = c.getInputBuffer(inIndex)
                        val n = if (buf != null) ex.readSampleData(buf, 0) else -1
                        if (n < 0) {
                            c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val out = c.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        out.position(info.offset)
                        out.get(chunk, 0, info.size)
                        pcm.write(chunk)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            val data = pcm.toByteArray()
            if (data.isEmpty()) return null
            return wavHeader(data.size, sampleRate, channels) + data
        } catch (e: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
            f.delete()
        }
    }

    private fun wavHeader(dataLen: Int, rate: Int, channels: Int): ByteArray {
        val byteRate = rate * channels * 2
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataLen).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(byteRate).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataLen)
        return b.array()
    }
}
