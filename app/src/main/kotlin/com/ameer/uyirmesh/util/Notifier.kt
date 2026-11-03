package com.ameer.uyirmesh.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ameer.uyirmesh.MainActivity
import com.ameer.uyirmesh.R
import com.ameer.uyirmesh.trigger.ActionReceiver
import com.ameer.uyirmesh.ui.L

object Notifier {
    const val CH_MESH = "mesh"
    const val CH_SOS = "sos"
    const val CH_ALERT = "alerts"
    const val CH_MSG = "messages"
    const val CH_RESCUE = "rescue"

    const val ID_MESH = 1
    const val ID_COUNTDOWN = 2
    const val ID_SOS_STATUS = 3
    const val ID_MSG = 4
    const val ID_TEAM = 5
    const val ID_FIND = 6
    private const val ID_ALERT_BASE = 1000
    private const val ID_CASE_BASE = 3000
    private const val ID_TASK_BASE = 5000

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        nm.createNotificationChannel(
            NotificationChannel(CH_MESH, "Mesh network status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the offline mesh running"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SOS, "SOS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "SOS countdown and replies from rescuers"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                enableLights(true)
                lightColor = Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Emergency alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Signed alerts from the rescue base"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                setSound(alarmSound, alarmAttrs)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Group chat, I'm safe messages and reports"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESCUE, "Rescue work", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New SOS cases and rescue tasks"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun openApp(ctx: Context, screen: String?, requestCode: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (screen != null) i.putExtra(MainActivity.EXTRA_OPEN, screen)
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(ctx: Context, action: String, requestCode: Int): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: Exception) {
            // Notifications not allowed; the screens still show everything.
        }
    }

    fun cancel(ctx: Context, id: Int) {
        try {
            NotificationManagerCompat.from(ctx).cancel(id)
        } catch (_: Exception) {
        }
    }

    /** The always-on notification of the mesh service, with a one-tap SOS button that works on the lock screen. */
    fun mesh(ctx: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_MESH)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx, null, 1))
            .addAction(0, L.t("notif_sos_button"), action(ctx, ActionReceiver.ACTION_SOS, 2))
            .addAction(0, L.t("notif_safe_button"), action(ctx, ActionReceiver.ACTION_SAFE, 3))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun updateMesh(ctx: Context, title: String, text: String) = post(ctx, ID_MESH, mesh(ctx, title, text))

    fun countdown(ctx: Context, seconds: Int, triggerName: String) {
        val n = NotificationCompat.Builder(ctx, CH_SOS)
            .setSmallIcon(R.drawable.ic_sos)
            .setColor(Color.RED)
            .setContentTitle(L.t("countdown_title").replace("{n}", seconds.toString()))
            .setContentText(L.t("countdown_text").replace("{t}", triggerName))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(seconds < 5)
            .setContentIntent(openApp(ctx, "sos_status", 10))
            .addAction(0, L.t("cancel"), action(ctx, ActionReceiver.ACTION_CANCEL_COUNTDOWN, 11))
            .addAction(0, L.t("send_now"), action(ctx, ActionReceiver.ACTION_SEND_NOW, 12))
            .build()
        post(ctx, ID_COUNTDOWN, n)
    }

    fun sosStatus(ctx: Context, title: String, text: String, loud: Boolean) {
        val n = NotificationCompat.Builder(ctx, CH_SOS)
            .setSmallIcon(R.drawable.ic_sos)
            .setColor(Color.RED)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (loud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(!loud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx, "sos_status", 20))
            .setAutoCancel(false)
            .build()
        post(ctx, ID_SOS_STATUS, n)
    }

    fun alert(ctx: Context, alertId: String, title: String, text: String, critical: Boolean) {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_sos)
            .setColor(if (critical) Color.RED else Color.rgb(255, 143, 0))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx, "alerts", 30))
            .setAutoCancel(true)
            .addAction(0, L.t("stop_siren"), action(ctx, ActionReceiver.ACTION_STOP_SIREN, 31))
            .build()
        post(ctx, ID_ALERT_BASE + (alertId.hashCode() and 0x3FF), n)
    }

    fun message(ctx: Context, id: Int, title: String, text: String, screen: String) {
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, screen, 40 + id))
            .setAutoCancel(true)
            .build()
        post(ctx, id, n)
    }

    fun rescue(ctx: Context, key: String, title: String, text: String, screen: String, task: Boolean) {
        val n = NotificationCompat.Builder(ctx, CH_RESCUE)
            .setSmallIcon(R.drawable.ic_sos)
            .setColor(Color.RED)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openApp(ctx, screen, 50))
            .setAutoCancel(true)
            .build()
        post(ctx, (if (task) ID_TASK_BASE else ID_CASE_BASE) + (key.hashCode() and 0x3FF), n)
    }
}
