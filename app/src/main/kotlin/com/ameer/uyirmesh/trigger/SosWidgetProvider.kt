package com.ameer.uyirmesh.trigger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ameer.uyirmesh.R
import com.ameer.uyirmesh.SosActivity
import com.ameer.uyirmesh.core.Triggers

/** Home-screen SOS button. Tapping it opens the 5-second countdown screen. */
class SosWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, SosActivity::class.java)
            .setAction("com.ameer.uyirmesh.action.SOS")
            .putExtra(SosActivity.EXTRA_TRIGGER, Triggers.WIDGET)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_sos)
            views.setOnClickPendingIntent(R.id.widget_button, pi)
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(id, views)
        }
    }
}
