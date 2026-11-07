package com.ameer.uyirmesh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.Roles

val LocalLang = staticCompositionLocalOf { "en" }

@Composable
fun tr(key: String): String = L.get(LocalLang.current, key)

/** Very small navigator: a back stack of route strings like "case/abc123". */
class Nav(start: String) {
    val stack = mutableStateListOf(start)
    val current: String get() = stack.last()

    fun go(route: String) {
        if (stack.lastOrNull() != route) stack.add(route)
    }

    fun replace(route: String) {
        stack.clear()
        stack.add(route)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    fun arg(): String = current.substringAfter('/', "")
}

@Composable
fun UyirRoot(nav: Nav) {
    val s by Graph.prefs.settings.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalLang provides s.lang) {
        UyirTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                LaunchedEffect(s.onboarded) {
                    if (!s.onboarded && nav.current != "onboarding") nav.replace("onboarding")
                }
                BackHandler(enabled = nav.stack.size > 1) { nav.back() }
                Box(Modifier.fillMaxSize()) { Route(nav, s.role) }
            }
        }
    }
}

@Composable
private fun Route(nav: Nav, role: String) {
    val r = nav.current
    val name = r.substringBefore('/')
    when (name) {
        "onboarding" -> OnboardingScreen(nav)
        "home" -> when (role) {
            Roles.BASE -> BaseGate(nav)
            Roles.RESCUER -> RescuerGate(nav)
            else -> PublicHome(nav)
        }
        "public" -> PublicHome(nav)
        "sos_form" -> SosFormScreen(nav)
        "sos_status" -> SosStatusScreen(nav)
        "findme" -> FindMeScreen(nav)
        "photo" -> PhotoScreen(nav)
        "chat" -> ChatScreen(nav)
        "alerts" -> AlertsScreen(nav)
        "safe" -> SafeScreen(nav)
        "missing" -> MissingScreen(nav)
        "report" -> ReportScreen(nav)
        "volunteer" -> VolunteerScreen(nav)
        "profile" -> ProfileScreen(nav)
        "firstaid" -> FirstAidScreen(nav)
        "shelters" -> SheltersScreen(nav)
        "map" -> PublicMapScreen(nav)
        "network" -> NetworkScreen(nav)
        "log" -> LogScreen(nav)
        "selftest" -> SelfTestScreen(nav)
        "settings" -> SettingsScreen(nav)
        "about" -> AboutScreen(nav)
        "modes" -> ModesScreen(nav)
        "scan" -> ScanScreen(nav, nav.arg())
        "rescuer_login" -> RescuerLoginScreen(nav)
        "rescuer" -> RescuerGate(nav)
        "task" -> TaskScreen(nav, nav.arg())
        "base_setup" -> BaseSetupScreen(nav)
        "base" -> BaseGate(nav)
        "case" -> CaseScreen(nav, nav.arg())
        "issue" -> IssueRescuerScreen(nav)
        "base_shelters" -> BaseSheltersScreen(nav)
        "web" -> WebDashboardScreen(nav)
        "base_alert" -> BaseAlertScreen(nav)
        "team_chat" -> TeamChatScreen(nav)
        "picture_sos" -> PictureSosScreen(nav)
        "checkin" -> CheckInScreen(nav)
        "cpr" -> CprScreen(nav)
        "go_shelter" -> GoShelterScreen(nav, nav.arg())
        "poll" -> PollScreen(nav, nav.arg())
        "hazard" -> HazardScreen(nav)
        "base_polls" -> BasePollsScreen(nav)
        "base_ids" -> BaseRescuerIdsScreen(nav)
        "relay" -> RelayModeScreen(nav)
        else -> PublicHome(nav)
    }
}
