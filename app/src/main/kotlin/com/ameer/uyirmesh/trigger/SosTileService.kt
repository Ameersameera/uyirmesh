package com.ameer.uyirmesh.trigger

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ameer.uyirmesh.SosActivity
import com.ameer.uyirmesh.core.Triggers

/** Quick-settings tile: pull down the notification shade and tap "SOS". */
class SosTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SosActivity::class.java)
            .setAction("com.ameer.uyirmesh.action.SOS")
            .putExtra(SosActivity.EXTRA_TRIGGER, Triggers.TILE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launch = Runnable {
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(this, 101, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(launch) else launch.run()
    }
}
