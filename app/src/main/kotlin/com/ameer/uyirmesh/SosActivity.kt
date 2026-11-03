package com.ameer.uyirmesh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.core.Triggers
import com.ameer.uyirmesh.mesh.MeshService
import com.ameer.uyirmesh.sos.Victim
import com.ameer.uyirmesh.ui.LocalLang
import com.ameer.uyirmesh.ui.tr

/**
 * Full-screen SOS countdown opened by the home-screen widget, the app shortcut or the quick-settings tile.
 * It also shows over the lock screen. Cancel stops it; otherwise the SOS goes out after 5 seconds.
 */
class SosActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeshService.start(this, fromForeground = true)
        if (savedInstanceState == null) startFrom(intent)
        setContent {
            CompositionLocalProvider(LocalLang provides Graph.prefs.now.lang) { SosCountdownUi() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startFrom(intent)
    }

    private fun startFrom(i: Intent?) {
        val trigger = i?.getStringExtra(EXTRA_TRIGGER) ?: Triggers.WIDGET
        if (!Graph.prefs.now.onboarded) {
            // Not set up yet: open the app instead.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        Graph.trigger.start(trigger)
    }

    @Composable
    private fun SosCountdownUi() {
        val cd by Graph.trigger.state.collectAsStateWithLifecycle()
        val mySos by Graph.store.mySos.flow.collectAsStateWithLifecycle()
        val active = mySos.lastOrNull { it.active }
        Column(
            Modifier.fillMaxSize().background(Color(0xFFD32F2F)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (cd.active) {
                Text(tr("sos_in"), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.size(180.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { Text(cd.seconds.toString(), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.height(12.dp))
                Text(Victim.triggerName(cd.trigger), color = Color.White.copy(alpha = 0.9f))
                Spacer(Modifier.height(40.dp))
                Button(
                    onClick = { Graph.trigger.cancel(); finish() },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFD32F2F)),
                ) { Text(tr("cancel").uppercase(), fontSize = 26.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { Graph.trigger.sendNow() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(tr("send_now"), color = Color.White, fontSize = 18.sp)
                }
            } else if (active != null) {
                Text("✓", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Black)
                Text(tr("sos_sent_title"), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(tr("sos_sent_text"), color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = {
                        startActivity(
                            Intent(this@SosActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(MainActivity.EXTRA_OPEN, "sos_status"),
                        )
                        finish()
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFD32F2F)),
                ) { Text(tr("open_status"), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(tr("close"), color = Color.White)
                }
            } else {
                Text(tr("sos_cancelled"), color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(tr("close"), color = Color.White)
                }
            }
        }
    }

    companion object {
        const val EXTRA_TRIGGER = "trigger"
    }
}
