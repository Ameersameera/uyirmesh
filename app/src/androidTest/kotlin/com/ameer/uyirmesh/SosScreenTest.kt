package com.ameer.uyirmesh

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.ameer.uyirmesh.core.Triggers
import com.ameer.uyirmesh.ui.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The full-screen SOS countdown opened by the home-screen widget, the app shortcut and the quick-settings tile. */
@RunWith(AndroidJUnit4::class)
class SosScreenTest {

    @get:Rule(order = 0)
    val grant: GrantPermissionRule = GrantPermissionRule.grant(*smokePermissions())

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    private fun en(key: String) = L.get("en", key)

    private fun ready() {
        Graph.prefs.update { it.copy(onboarded = true, lang = "en") }
        Graph.victim.cancel()
        Graph.trigger.cancel()
        rule.waitUntil(timeoutMillis = 25_000) { !Graph.victim.recentlySent() }
    }

    private fun launch(): ActivityScenario<SosActivity> = ActivityScenario.launch(
        Intent(Graph.app, SosActivity::class.java).putExtra(SosActivity.EXTRA_TRIGGER, Triggers.WIDGET),
    )

    private fun press(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
        rule.waitForIdle()
    }

    @Test
    fun widgetCountdownCanBeCancelled() {
        ready()
        launch().use {
            rule.waitUntil(timeoutMillis = 10_000) { Graph.trigger.state.value.active }
            press(en("cancel").uppercase())
            rule.waitUntil(timeoutMillis = 10_000) { !Graph.trigger.state.value.active }
            assertNull(Graph.victim.active())
        }
    }

    @Test
    fun widgetSendNowSendsTheSos() {
        ready()
        launch().use {
            rule.waitUntil(timeoutMillis = 10_000) { Graph.trigger.state.value.active }
            press(en("send_now"))
            rule.waitUntil(timeoutMillis = 10_000) { Graph.victim.active() != null }
            assertEquals(Triggers.WIDGET, Graph.victim.active()!!.payload.trigger)
            rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText(en("sos_sent_title"))).fetchSemanticsNodes().isNotEmpty() }
            Graph.victim.cancel()
        }
    }
}
