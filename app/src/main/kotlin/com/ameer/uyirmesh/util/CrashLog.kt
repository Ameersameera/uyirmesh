package com.ameer.uyirmesh.util

import android.content.Context
import android.os.Build
import com.ameer.uyirmesh.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the reason of an app crash so it can be shared from Settings > Diagnostics. */
object CrashLog {
    private lateinit var file: File

    fun install(ctx: Context) {
        file = File(ctx.filesDir, "crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("UyirMesh ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) crashed at ")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    append("\nPhone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    append("\nThread: ${thread.name}\n\n")
                    append(sw.toString().take(20_000))
                }
                file.writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun read(): String? = if (::file.isInitialized && file.exists()) file.readText() else null

    fun clear() {
        if (::file.isInitialized) file.delete()
    }

    /** Records a problem that did not crash the app. */
    fun note(ctx: Context, where: String, e: Throwable) {
        try {
            val f = File(ctx.filesDir, "errors.txt")
            if (f.length() > 100_000) f.delete()
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            f.appendText(
                "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} $where: ${sw.toString().take(3000)}\n"
            )
        } catch (_: Throwable) {
        }
    }

    fun readErrors(ctx: Context): String = try {
        File(ctx.filesDir, "errors.txt").takeIf { it.exists() }?.readText().orEmpty()
    } catch (e: Exception) {
        ""
    }
}
