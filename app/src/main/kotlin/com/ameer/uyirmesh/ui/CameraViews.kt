package com.ameer.uyirmesh.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object Qr {
    /** Black-on-white QR bitmap, one pixel per module (scaled up without smoothing when drawn). */
    fun bitmap(text: String): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}

@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) { Qr.bitmap(text).asImageBitmap() }
    Image(bmp, contentDescription = "QR", modifier = modifier, filterQuality = FilterQuality.None)
}

/** Camera preview that reports the first QR code it reads. */
@Composable
fun QrScanner(modifier: Modifier = Modifier, onResult: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            try {
                ProcessCameraProvider.getInstance(ctx).get().unbindAll()
            } catch (_: Exception) {
            }
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            val view = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 960))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { image -> decode(image, reader, done, onResult, view) }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    com.ameer.uyirmesh.Graph.note("qr camera", e)
                }
            }, ContextCompat.getMainExecutor(c))
            view
        },
    )
}

private fun decode(image: ImageProxy, reader: MultiFormatReader, done: AtomicBoolean, onResult: (String) -> Unit, view: PreviewView) {
    try {
        if (done.get()) return
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val data = ByteArray(buf.remaining())
        buf.get(data)
        // Copy the Y plane into a tight w*h array (row stride can be wider than the image).
        val y = if (rowStride == w) data else ByteArray(w * h).also { out ->
            for (row in 0 until h) System.arraycopy(data, row * rowStride, out, row * w, w)
        }
        val text = tryDecode(reader, y, w, h) ?: tryDecode(reader, invert(y), w, h)
        if (text != null && done.compareAndSet(false, true)) {
            view.post { onResult(text) }
        }
    } catch (_: Exception) {
    } finally {
        image.close()
    }
}

private fun tryDecode(reader: MultiFormatReader, y: ByteArray, w: Int, h: Int): String? = try {
    val src = PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
    reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text
} catch (e: Exception) {
    null
} finally {
    reader.reset()
}

private fun invert(y: ByteArray): ByteArray = ByteArray(y.size) { (255 - (y[it].toInt() and 0xFF)).toByte() }

/** Camera preview with a capture function; the photo is compressed to fit one mesh packet. */
class PhotoCapture {
    var capture: ImageCapture? = null
}

@Composable
fun CameraPreview(modifier: Modifier, holder: PhotoCapture) {
    val owner = LocalLifecycleOwner.current
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        onDispose {
            try {
                ProcessCameraProvider.getInstance(ctx).get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                val view = PreviewView(c)
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val cap = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetResolution(Size(1280, 960))
                            .build()
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
                        holder.capture = cap
                    } catch (e: Exception) {
                        com.ameer.uyirmesh.Graph.note("photo camera", e)
                    }
                }, ContextCompat.getMainExecutor(c))
                view
            },
        )
    }
}

object PhotoUtil {
    fun take(holder: PhotoCapture, dir: File, onDone: (ByteArray?) -> Unit) {
        val cap = holder.capture ?: return onDone(null)
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(f).build()
        cap.takePicture(opts, Executors.newSingleThreadExecutor(), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val bytes = try {
                    compress(f, 26_000)
                } catch (e: Exception) {
                    null
                }
                f.delete()
                onDone(bytes)
            }

            override fun onError(exception: ImageCaptureException) {
                f.delete()
                onDone(null)
            }
        })
    }

    /** Scales and re-encodes until the JPEG is at most [maxBytes]. */
    fun compress(f: File, maxBytes: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val src = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = try {
            when (ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        val rotated = if (rotation != 0f) Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotation) }, true) else src
        for (side in intArrayOf(800, 640, 480, 360, 280)) {
            val scale = side.toFloat() / maxOf(rotated.width, rotated.height)
            val b = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true) else rotated
            for (q in intArrayOf(70, 55, 40, 30)) {
                val out = ByteArrayOutputStream()
                b.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= maxBytes) return out.toByteArray()
            }
        }
        return null
    }
}
