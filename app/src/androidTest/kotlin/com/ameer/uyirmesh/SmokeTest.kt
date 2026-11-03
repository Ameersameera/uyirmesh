package com.ameer.uyirmesh

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.ameer.uyirmesh.base.Reports
import com.ameer.uyirmesh.core.*
import com.ameer.uyirmesh.data.CaseItem
import com.ameer.uyirmesh.data.CaseStatus
import com.ameer.uyirmesh.data.RescuerRecord
import com.ameer.uyirmesh.data.TaskItem
import com.ameer.uyirmesh.mesh.MeshService
import com.ameer.uyirmesh.ui.L
import com.ameer.uyirmesh.util.CrashLog
import com.ameer.uyirmesh.util.MapsLink
import com.ameer.uyirmesh.web.DashboardServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UyirSmoke"

/** Test rescue base. The real base private key is never in the repository, so the test makes its own. */
private object TestBase {
    val enc = Crypto.newEcKeyPair()
    val sig = Crypto.newEcKeyPair()
    val resc = Crypto.newEcKeyPair()
    val pinned = PinnedBaseKeys(enc.public, sig.public, resc.public)
    val keys = BasePrivateKeys(enc.private, sig.private, resc.private)
    const val PASSWORD = "Test@2026"
    val keyFile: String by lazy { BaseKeyFile.create(PASSWORD.toCharArray(), enc, sig, resc, iterations = 20_000) }
}

internal fun smokePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/**
 * On-device smoke test (runs on an Android emulator in CI). It uses the app like a person would, and plays the
 * other phones of the mesh by handing the app real encrypted and signed packets, exactly as Nearby would:
 *  1. setup and every public screen (English, Tamil, large text, dark theme, find-me tools)
 *  2. victim: hands-free countdown, SOS form, signed replies from the base and a rescuer, forged reply, cancel
 *  3. base: key file import, login, SOS arriving over a relay, AI priority, rescuer IDs, live tracking of the
 *     team (signed positions, forged ones ignored), alerts, shelters, laptop dashboard over HTTP, CSV/PDF
 *     reports, victim cancel
 *  4. rescuer: ID QR + PIN login, signed heartbeat position, SOS read and passed on, signed task from the base,
 *     status updates, Google Maps buttons, team chat, PIN lock, log out
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmokeTest {

    @get:Rule(order = 0)
    val grant: GrantPermissionRule = GrantPermissionRule.grant(*smokePermissions())

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun useTestBase() {
        Graph.usePinnedForTest(TestBase.pinned)
    }

    @After
    fun printWhatHappened() {
        try {
            val errors = CrashLog.readErrors(Graph.app)
            if (errors.length > errorsPrinted) {
                errors.substring(errorsPrinted).lines().filter { it.isNotBlank() }.forEach { Log.w(TAG, "noted: $it") }
                errorsPrinted = errors.length
            }
            val log = Graph.store.log.value
            log.filter { it.at > logPrinted }.forEach { Log.i(TAG, "log ${it.tag}: ${it.text}") }
            logPrinted = log.maxOfOrNull { it.at } ?: logPrinted
            Log.i(TAG, "counters ${Graph.mesh.engine.metrics.snapshot()} | ${Graph.mesh.stats.value}")
        } catch (e: Throwable) {
            Log.w(TAG, "could not print the app log", e)
        }
    }

    /* ======================= 1. setup and public screens ======================= */

    @Test
    fun a1_setupAndPublicScreens() {
        finishSetup()
        assertEquals("home", route())
        assertTrue(Graph.prefs.now.onboarded)

        // Every tile and button on the home screen opens its screen, and Back returns home.
        val tiles = listOf(
            "alerts" to "alerts", "group_chat" to "chat", "missing_people" to "missing", "shelters" to "shelters",
            "map" to "map", "first_aid" to "firstaid", "report_problem" to "report", "i_can_help" to "volunteer",
            "medical_card" to "profile", "network_status" to "network", "self_test" to "selftest",
            "rescue_modes" to "modes", "im_safe" to "safe", "find_me" to "findme",
        )
        for ((key, target) in tiles) {
            tap(en(key))
            waitRoute(target)
            back()
            waitRoute("home")
        }
        tapIcon(en("settings"))
        waitRoute("settings")
        back()

        // Screens opened from inside other screens (the test emulator has no camera: they must not crash).
        for (r in listOf("log", "about", "sos_status", "photo", "scan/rescuer", "scan/victim", "scan/shelter", "rescuer_login", "base_setup", "sos_form")) {
            visit(r)
        }

        // The phone's own security test.
        go("selftest")
        tap(en("run_crypto_test"))
        waitText("rejected by CRC: OK")
        assertFalse(has(hasText("FAILED", substring = true)))
        back()

        // Find me: siren, flashlight, flashing screen, vibration.
        go("findme")
        tap(en("start_siren"))
        if (eventually(5_000) { Graph.alarm.siren.value }) {
            tap(en("stop_siren"))
            waitUntil("siren off") { !Graph.alarm.siren.value }
        } else {
            Log.w(TAG, "the siren could not start on this device")
        }
        tap(en("start_flash"))
        Graph.alarm.stopTorch()
        tap(en("screen_strobe"))
        click(hasText(en("tap_to_stop")) and hasClickAction())
        tap(en("vibrate_sos"))
        back()

        // Settings: Tamil, large text, dark theme, hands-free switches and drill mode.
        go("settings")
        tap("தமிழ்")
        waitUntil("Tamil") { Graph.prefs.now.lang == "ta" }
        for (r in PUBLIC_ROUTES) visit(r)
        tap("English")
        waitUntil("English") { Graph.prefs.now.lang == "en" }
        tap(en("large_text"))
        waitUntil("large text") { Graph.prefs.now.largeText }
        for (r in listOf("sos_form", "selftest", "network", "firstaid", "about")) visit(r)
        tap(en("large_text"))
        waitUntil("normal text") { !Graph.prefs.now.largeText }
        tap(en("theme_dark"))
        waitUntil("dark theme") { Graph.prefs.now.theme == 2 }
        for (r in listOf("map", "shelters", "chat", "sos_form")) visit(r)
        tap(en("theme_auto"))
        waitUntil("automatic theme") { Graph.prefs.now.theme == 0 }
        tap(en("voice_sos"))
        waitUntil("voice SOS off") { !Graph.prefs.now.voice }
        tap(en("voice_sos"))
        waitUntil("voice SOS on") { Graph.prefs.now.voice }
        tap(en("drill_mode"))
        waitUntil("drill mode on") { Graph.prefs.now.drill && Graph.mesh.outbox.drill }
        tap(en("drill_mode"))
        waitUntil("drill mode off") { !Graph.prefs.now.drill && !Graph.mesh.outbox.drill }
        back()
        waitRoute("home")
        assertTrue("mesh service running", MeshService.running)
    }

    /* ======================= 2. victim ======================= */

    @Test
    fun a2_victimSos() {
        finishSetup()
        toPublicHome()
        Graph.victim.cancel()
        waitUntil("no recent SOS", 25_000) { !Graph.victim.recentlySent() }
        val me = Graph.prefs.now.deviceId

        // Hands-free trigger (voice, shake, widget, tile all use this countdown): Cancel stops it.
        assertTrue(Graph.trigger.start(Triggers.SHAKE))
        waitText(en("send_now"))
        tap(en("cancel"))
        waitUntil("countdown cancelled") { !Graph.trigger.state.value.active }
        assertNull(Graph.victim.active())

        // "Send now" sends the SOS at once; with no phone in range it waits in the store-and-forward buffer.
        assertTrue(Graph.trigger.start(Triggers.WIDGET))
        tap(en("send_now"))
        waitUntil("SOS sent") { Graph.victim.active() != null }
        val first = Graph.victim.active()!!
        assertEquals(Triggers.WIDGET, first.payload.trigger)
        waitUntil("SOS waiting for a phone") { Graph.mesh.engine.waitingOwnCount() > 0 }
        tap(en("sos_active"))
        waitRoute("sos_status")

        // Signed reply from the base, relayed by one phone: the timeline shows the SOS reached the base.
        val baseOut = Outbox(Ids.newDeviceId(), TestBase.pinned)
        val relay = Ids.newDeviceId()
        val received = AckPayload(first.caseId, AckStatus.RECEIVED, at = now(), hops = 2)
        inject(baseOut.signedByBase(TestBase.keys, PacketType.ACK, received.toBytes(), me).relayCopy(Ids.shortOf(relay)), relay)
        waitUntil("reply from the base") { (Graph.victim.active()?.baseAt ?: 0L) > 0L }
        assertEquals(2, Graph.victim.active()!!.baseHops)
        waitText(en("rescue_base") + " · " + en("verified"))

        // Signed reply straight from a rescuer (certificate issued by the base): "on the way".
        val karthik = Credentials.issue(TestBase.keys, "Karthik", "ALPHA", Crypto.random(32), now(), 5)
        val onWay = AckPayload(first.caseId, AckStatus.ON_THE_WAY, "ALPHA", 12, "Coming by boat", now(), "Karthik", karthik.cert.toBase64())
        inject(Outbox(Ids.newDeviceId(), TestBase.pinned).signedByRescuer(karthik, PacketType.ACK, onWay.toBytes(), me))
        waitUntil("reply from the rescuer") { Graph.victim.active()?.rescuer == "Karthik" }
        assertTrue(Graph.victim.active()!!.onWayAt > 0)
        waitText("Coming by boat")

        // A forged "rescued" reply (signed with an unknown key) is dropped and changes nothing.
        val forgedBefore = Graph.mesh.engine.metrics.forgedDrops
        val fake = Crypto.signPacket(
            Crypto.newEcKeyPair().private,
            Outbox(Ids.newDeviceId(), TestBase.pinned).plain(PacketType.ACK, AckPayload(first.caseId, AckStatus.RESCUED, at = now()).toBytes(), me),
        )
        inject(fake)
        waitUntil("forged reply dropped") { Graph.mesh.engine.metrics.forgedDrops > forgedBefore }
        assertEquals(0L, Graph.victim.active()!!.rescuedAt)

        // Cancel from the status screen, with the confirmation dialog.
        tap(en("cancel_sos"))
        tap(en("yes_cancel"))
        waitUntil("SOS cancelled") { Graph.victim.active() == null }
        waitText(en("sos_cancelled"))
        back()
        waitRoute("home")

        // The SOS form: kind, needs, send.
        tap("SOS")
        waitRoute("sos_form")
        tap(en("kind_flood"))
        tap(en("need_water"))
        tap(en("send_sos_now"))
        waitRoute("sos_status")
        val sos = Graph.victim.active()
        assertNotNull(sos)
        assertEquals(EmergencyKind.FLOOD, sos!!.payload.kind)
        assertTrue(Needs.WATER in sos.payload.needs)
        assertEquals(Triggers.BUTTON, sos.payload.trigger)
        assertNotEquals(first.caseId, sos.caseId)
        visit("photo")
        tap(en("update_location"))
        tap(en("cancel_sos"))
        tap(en("yes_cancel"))
        waitUntil("second SOS cancelled") { Graph.victim.active() == null }
        back()
        waitRoute("home")

        // A signed alert from the base shows on the home screen; an unsigned one is dropped.
        val alert = AlertPayload(Ids.newId(), AlertKind.INFO, 1, "Water at the school", "Drinking water at the Govt school from 4 pm", validUntil = now() + 3_600_000, at = now())
        inject(baseOut.signedByBase(TestBase.keys, PacketType.ALERT, alert.toBytes(), Destinations.ALL))
        waitText("Water at the school")
        val forgedAlerts = Graph.mesh.engine.metrics.forgedDrops
        val fakeAlert = AlertPayload(Ids.newId(), AlertKind.EVACUATE, 3, "FAKE evacuate", "fake", at = now())
        inject(Outbox(Ids.newDeviceId(), TestBase.pinned).plain(PacketType.ALERT, fakeAlert.toBytes(), Destinations.ALL))
        waitUntil("fake alert dropped") { Graph.mesh.engine.metrics.forgedDrops > forgedAlerts }
        assertFalse(Graph.store.alerts.value.any { it.title == "FAKE evacuate" })
        go("alerts")
        waitText("Drinking water at the Govt school")
        back()

        // Group chat: a message from another phone arrives, and our own message is sent.
        inject(Outbox(Ids.newDeviceId(), TestBase.pinned).plain(PacketType.CHAT, ChatPayload("Meena", "Is anyone near the bridge?", now()).toBytes(), Destinations.ALL))
        go("chat")
        waitText("Is anyone near the bridge?")
        typeInto(hasSetTextAction(), "I am at the temple, safe")
        tapIcon(en("send"))
        waitUntil("chat sent") { Graph.store.chat.value.any { it.mine && it.text == "I am at the temple, safe" } }
        back()

        // I'm safe.
        go("safe")
        tap(en("tell_everyone_safe"))
        waitUntil("I'm safe sent") { Graph.store.safe.value.any { it.mine } }
        back()
    }

    /* ======================= 3. rescue base ======================= */

    @Test
    fun a3_baseMode() {
        finishSetup()
        toPublicHome()

        // The key file opens only with its password, and only if it belongs to this app's base.
        val otherBase = BaseKeyFile.create("Other@1".toCharArray(), Crypto.newEcKeyPair(), Crypto.newEcKeyPair(), Crypto.newEcKeyPair(), iterations = 5_000)
        expectCrypto { Graph.base.importKeyFile(otherBase, "Other@1".toCharArray()) }
        expectCrypto { Graph.base.importKeyFile(TestBase.keyFile, "wrong".toCharArray()) }
        Graph.base.importKeyFile(TestBase.keyFile, TestBase.PASSWORD.toCharArray())
        assertTrue(Graph.base.hasKeys())

        // Enter base mode from the modes screen, like a user.
        tap(en("rescue_modes"))
        waitRoute("modes")
        tap(en("role_base"))
        waitUntil("base mode") { Graph.prefs.now.role == Roles.BASE }
        waitRoute("home")
        waitFor(tabNode(en("cases")))
        waitUntil("mesh engine in base mode") { Graph.mesh.engine.isBase }

        // Lock, wrong password, right password.
        tapIcon(en("lock"))
        type(en("base_password"), "not-the-password")
        tap(en("log_in"))
        waitText(en("wrong_password"), 30_000)
        type(en("base_password"), TestBase.PASSWORD)
        tap(en("log_in"))
        waitFor(tabNode(en("cases")), 30_000)

        // An SOS arrives over one relay phone, encrypted for the base.
        val victim = Ids.newDeviceId()
        val relay = Ids.newDeviceId()
        val secret = Hex.encode(Crypto.random(16))
        val sos = SosPayload(
            caseId = Ids.newId(), kind = EmergencyKind.FLOOD, people = 4, children = 1, elderly = 1, injured = true,
            needs = listOf(Needs.BOAT, Needs.WATER), note = "water rising, on the roof", battery = 30,
            lat = 11.6643, lon = 78.1460, accuracy = 12.0, locTime = now(), name = "Ravi", phone = "9876543210",
            trigger = Triggers.VOICE, cancelHash = Crypto.sha256Hex(secret), sentAt = now(),
        )
        val victimOut = Outbox(victim, TestBase.pinned)
        val sosBytes = PacketCodec.encode(victimOut.sealedRescue(PacketType.SOS, sos.toBytes()).relayCopy(Ids.shortOf(relay)))
        val originatedBefore = Graph.mesh.engine.metrics.originated
        Graph.mesh.onBytes(relay, sosBytes)
        waitUntil("SOS reached the base") { caseOf(sos.caseId) != null }
        val c = caseOf(sos.caseId)!!
        assertEquals(victim, c.victim)
        assertEquals(1, c.hops)
        assertEquals(listOf(Ids.shortHex(Ids.shortOf(relay))), c.path)
        assertTrue("AI score ${c.score}", c.score > 0)
        assertTrue(c.label in listOf("Critical", "High", "Medium", "Low"))
        assertTrue(c.reasons.isNotEmpty())
        assertEquals(4, c.proba.size)
        Log.i(TAG, "AI priority: ${c.label} ${c.score} (model ${c.modelScore}) ${c.reasons.map { "${it.label}+${it.points}" }}")
        // The base answers the victim with a signed reply.
        waitUntil("signed reply to the victim") { Graph.mesh.engine.metrics.originated > originatedBefore }

        // The same packet coming again through another phone is a duplicate; a damaged copy fails the CRC.
        val duplicates = Graph.mesh.engine.metrics.duplicates
        Graph.mesh.onBytes(Ids.newDeviceId(), sosBytes)
        waitUntil("duplicate dropped") { Graph.mesh.engine.metrics.duplicates > duplicates }
        assertEquals(0, caseOf(sos.caseId)!!.repeats)
        val crcDrops = Graph.mesh.engine.metrics.crcDrops
        val damaged = sosBytes.copyOf()
        damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 0x55).toByte()
        Graph.mesh.onBytes(relay, damaged)
        waitUntil("damaged packet dropped") { Graph.mesh.engine.metrics.crcDrops > crcDrops }

        // Location update and a heartbeat (topology) from the relay phone.
        inject(victimOut.sealedRescue(PacketType.SOS_UPDATE, SosUpdate(sos.caseId, 11.6650, 78.1470, 8.0, now(), 25, sentAt = now()).toBytes()), relay)
        waitUntil("location update") { (caseOf(sos.caseId)?.track?.size ?: 0) >= 2 }
        assertEquals(25, caseOf(sos.caseId)!!.battery)
        inject(Outbox(relay, TestBase.pinned).sealedBase(PacketType.HEARTBEAT, HeartbeatPayload(Roles.PUBLIC, 64, listOf(victim, Graph.prefs.now.deviceId), at = now()).toBytes()))
        waitUntil("phone in the topology") { Graph.store.nodes.value.any { it.deviceId == relay } }

        // The case on screen, with the AI reasons; dispatcher sets "on the way".
        tab(en("cases"))
        click(hasText("on the roof", substring = true) and hasClickAction())
        waitRoute("case/${sos.caseId}")
        waitText(en("why_priority"))
        waitText("Ravi")
        tap(en("cs_on_way"))
        waitUntil("status on the way") { caseOf(sos.caseId)?.status == CaseStatus.ON_THE_WAY }
        back()

        // Rescuer ID for a team member.
        tab(en("teams"))
        tap(en("issue_rescuer"))
        waitRoute("issue")
        type(en("rescuer_name"), "Karthik")
        type(en("pin_4_8"), "4321")
        tap(en("create_id"))
        waitText(en("issued_for").replace("{n}", "Karthik").replace("{t}", "ALPHA"), 30_000)
        assertTrue(Graph.store.rescuers.value.any { it.name == "Karthik" && it.team == "ALPHA" })
        back()

        // Team ALPHA takes the case. Karthik's phone reports its position about once a minute, signed with the team
        // key, and the base shows whether he is really getting closer. Forged positions are ignored.
        tab(en("cases"))
        click(hasText("on the roof", substring = true) and hasClickAction())
        waitRoute("case/${sos.caseId}")
        click(hasText("ALPHA", substring = true) and hasClickAction())
        waitUntil("assigned to ALPHA") { caseOf(sos.caseId)?.team == "ALPHA" }
        tap(en("cs_on_way"))
        waitUntil("on the way again") { caseOf(sos.caseId)?.status == CaseStatus.ON_THE_WAY }
        waitText(en("tracking_none").replace("{t}", "ALPHA"))
        waitText(en("nav_google_maps"))
        val karthikId = Graph.store.rescuers.value.first { it.name == "Karthik" }.rescuerId
        val alphaKey = Graph.base.teamKey("ALPHA", create = false)!!
        val karthikPhone = Ids.newDeviceId()
        val vLat = caseOf(sos.caseId)!!.lat!!
        val vLon = caseOf(sos.caseId)!!.lon!!
        fun karthikAt(minutesAgo: Int, metresSouth: Double, key: ByteArray = alphaKey, battery: Int = 70): Packet {
            val hb = HeartbeatPayload(
                Roles.RESCUER, battery, emptyList(), vLat - metresSouth / 111_195.0, vLon, "ALPHA", karthikId, "Karthik", "1.0",
                now(), now() - minutesAgo * 60_000L,
            )
            return Outbox(karthikPhone, TestBase.pinned).sealedBase(PacketType.HEARTBEAT, PositionAuth.sign(key, karthikPhone, hb).toBytes())
        }
        for ((i, step) in listOf(6 to 1200.0, 3 to 900.0, 0 to 600.0).withIndex()) {
            inject(karthikAt(step.first, step.second))
            waitUntil("Karthik's position ${i + 1}") { (rescuerOf(karthikId)?.trail?.size ?: 0) >= i + 1 }
        }
        val approach = Graph.base.approaches(caseOf(sos.caseId)!!).single { it.rescuer.rescuerId == karthikId }.approach
        assertEquals(Tracking.Trend.CLOSER, approach.trend)
        assertEquals(600.0, approach.distanceM, 25.0)
        Log.i(TAG, "tracking: ${approach.trend} ${approach.distanceM.toInt()} m (was ${approach.earlierM?.toInt()} m), ETA ${approach.etaMin} min")
        waitText(en("trend_closer"))
        val before = rescuerOf(karthikId)!!
        val forger = Ids.newDeviceId()
        inject(karthikAt(0, 0.0, key = Crypto.random(32), battery = 99))
        inject(Outbox(forger, TestBase.pinned).sealedBase(PacketType.HEARTBEAT, HeartbeatPayload(Roles.RESCUER, 98, emptyList(), vLat, vLon, "ALPHA", karthikId, "Karthik", "1.0", now(), now()).toBytes()))
        waitUntil("forged positions looked at") {
            val nodes = Graph.store.nodes.value
            nodes.any { it.deviceId == forger } && nodes.any { it.deviceId == karthikPhone && it.battery == 99 }
        }
        val after = rescuerOf(karthikId)!!
        assertEquals(before.lat, after.lat)
        assertEquals(before.posAt, after.posAt)
        assertEquals(70, after.battery)
        waitUntil("forged position noted in the log") { Graph.store.log.value.any { it.text.contains("not signed with team ALPHA") } }
        back()

        tab(en("map"))
        waitText(en("heatmap"))
        tab(en("network"))
        waitText(en("packet_paths"))
        // Phones that sent a heartbeat in the last 15 minutes (the relay, Karthik's phone, the forger, maybe this base).
        waitUntil("phones reporting") {
            val live = Graph.store.nodes.value.count { now() - it.lastSeen < 15 * 60_000 }
            live >= 1 && has(hasText(en("phones_reporting").replace("{n}", live.toString())))
        }

        // Signed alert and a shelter.
        tab(en("more"))
        tap(en("send_alert"))
        waitRoute("base_alert")
        type(en("alert_title"), "Move to higher ground")
        tap(en("sign_and_send"))
        waitUntil("alert sent") { Graph.store.alerts.value.any { it.mine && it.title == "Move to higher ground" } }
        back()
        tab(en("more"))
        tap(en("shelters_supplies"))
        waitRoute("base_shelters")
        type(en("shelter_name"), "Govt School")
        tap(en("add_shelter_here"))
        waitUntil("shelter added") { Graph.store.shelters.value.any { it.name == "Govt School" } }
        tap(en("check_in_qr"))
        waitFor(hasContentDescription("QR"))
        back()
        Graph.alarm.stopAll()

        // Laptop dashboard over the hotspot (here: the phone itself).
        tapIcon(en("web_dashboard"))
        waitRoute("web")
        tap(en("web_on"))
        waitUntil("dashboard switched on") { Graph.prefs.now.webServer }
        waitForServer()
        val page = http("GET", "/")
        assertEquals(200, page.code)
        assertTrue(page.body.contains("UyirMesh"))
        assertEquals(200, http("GET", "/app").code)
        assertEquals(401, http("GET", "/api/state").code)
        assertEquals(401, http("POST", "/api/login", "{\"password\":\"nope\"}").code)
        val login = http("POST", "/api/login", "{\"password\":\"${TestBase.PASSWORD}\"}")
        assertEquals(200, login.code)
        val cookie = (login.cookie ?: "").substringBefore(';')
        assertTrue(cookie, cookie.startsWith("um_session="))
        val state = http("GET", "/api/state", cookie = cookie)
        assertEquals(200, state.code)
        assertTrue(state.body.contains(sos.caseId))
        assertTrue("team tracking on the laptop", state.body.contains("\"trend\":\"CLOSER\""))
        val csvDownload = http("GET", "/api/export.csv", cookie = cookie)
        assertEquals(200, csvDownload.code)
        assertTrue(csvDownload.body.contains(sos.caseId))
        assertEquals(200, http("POST", "/api/message", "{\"caseId\":\"${sos.caseId}\",\"text\":\"Boat coming\"}", cookie).code)
        tap(en("web_on"))
        waitUntil("dashboard switched off") { !Graph.prefs.now.webServer }
        back()

        // Reports.
        val csv = Reports.writeCsv(Graph.app)
        assertNotNull(csv)
        assertTrue(csv!!.readText().contains(sos.caseId))
        val pdf = Reports.writePdf(Graph.app)
        assertNotNull(pdf)
        assertTrue(pdf!!.length() > 1_000)

        // The victim cancels: a wrong cancel code is refused, the right one closes the case.
        inject(victimOut.sealedRescue(PacketType.SOS_CANCEL, SosCancel(sos.caseId, "wrong-code", now()).toBytes()), relay)
        waitUntil("wrong cancel refused") { Graph.store.log.value.any { it.text.contains("rejected: wrong cancel code") } }
        assertEquals(CaseStatus.ON_THE_WAY, caseOf(sos.caseId)!!.status)
        inject(victimOut.sealedRescue(PacketType.SOS_CANCEL, SosCancel(sos.caseId, secret, now()).toBytes()), relay)
        waitUntil("case cancelled") { caseOf(sos.caseId)?.status == CaseStatus.CANCELLED }

        // Leave base mode.
        tab(en("more"))
        tap(en("leave_base_mode"))
        waitUntil("public mode") { Graph.prefs.now.role == Roles.PUBLIC }
        waitText(en("rescue_modes"))
        waitUntil("mesh engine left base mode") { !Graph.mesh.engine.isBase }
    }

    /* ======================= 4. rescuer ======================= */

    @Test
    fun a4_rescuerMode() {
        finishSetup()
        toPublicHome()
        if (!(Graph.base.hasKeys() && BaseKeyFile.keysMatch(Graph.base.keys()!!, TestBase.pinned))) {
            Graph.base.importKeyFile(TestBase.keyFile, TestBase.PASSWORD.toCharArray())
        }

        // The base issues an ID; the rescuer phone reads the QR text and checks the PIN (as after scanning it).
        val qr = Graph.base.issueRescuer("Priya", "ALPHA", 5, "1234".toCharArray())
        assertTrue(Credentials.isCredentialQr(qr))
        assertTrue("QR length ${qr.length}", qr.length < 600)
        expectCrypto { Graph.rescuer.login(qr, "9999".toCharArray()) }
        assertNull(Graph.rescuer.bundle())
        Graph.rescuer.login(qr, "1234".toCharArray())
        assertEquals(Roles.RESCUER, Graph.prefs.now.role)
        ui { act().nav.replace("home") }
        waitFor(tabNode(en("tasks")))
        waitUntil("mesh engine in rescuer mode") { Graph.mesh.engine.isRescuer && Graph.mesh.engine.teams == setOf("ALPHA") }
        val teamKey = Graph.base.teamKey("ALPHA", create = false)
        assertNotNull(teamKey)

        // This phone's heartbeat carries its position signed with the team key, and the base accepts it.
        val myId = Graph.mesh.engine.myId
        Graph.mesh.sendHeartbeat()
        val mine = ArrayList<Packet>()
        waitUntil("own heartbeat waiting to be sent") {
            mine.clear()
            // Looks into the store-and-forward buffer without removing anything.
            Graph.mesh.engine.forgetWhere { p ->
                if (p.type == PacketType.HEARTBEAT && p.src == myId) mine.add(p)
                false
            }
            mine.isNotEmpty()
        }
        val ownHb = HeartbeatPayload.fromBytes(Opener.asBase(TestBase.keys, TestBase.pinned, mine.maxBy { it.createdAt }))
        val priyaId = Graph.rescuer.bundle()!!.cert.rescuerId
        assertEquals(priyaId, ownHb.rescuerId)
        assertTrue("heartbeat position signed with the team key", PositionAuth.verify(teamKey!!, myId, ownHb))
        Graph.base.onHeartbeat(myId, ownHb, 0)
        assertTrue(rescuerOf(priyaId)!!.lastSeen > 0)

        // An SOS passes through this rescuer: the rescuer can read it AND passes it on towards the base.
        val victimOut = Outbox(Ids.newDeviceId(), TestBase.pinned)
        val sos = SosPayload(caseId = Ids.newId(), kind = EmergencyKind.MEDICAL, people = 2, injured = true, note = "near the temple tank", battery = 40, lat = 11.6700, lon = 78.1500, sentAt = now())
        val relayedBefore = Graph.mesh.engine.metrics.relayed
        inject(victimOut.sealedRescue(PacketType.SOS, sos.toBytes()))
        waitUntil("rescuer reads the SOS") { Graph.store.seen.value.any { it.caseId == sos.caseId } }
        waitUntil("SOS passed on") { Graph.mesh.engine.metrics.relayed > relayedBefore }

        // A task from the base (signed, team key); an unsigned task is refused.
        val baseTask = TaskPayload(Ids.newId(), Ids.newDeviceId(), 11.6800, 78.1600, "Critical", 92, "Fire · 3 people · injured", "", 3, "ALPHA", now())
        inject(Outbox(Ids.newDeviceId(), TestBase.pinned).teamSignedByBase(TestBase.keys, PacketType.TASK, baseTask.toBytes(), "ALPHA", teamKey))
        waitUntil("task from the base") { taskOf(baseTask.caseId) != null }
        val forgedBefore = Graph.mesh.engine.metrics.forgedDrops
        val fakeTask = TaskPayload(Ids.newId(), Ids.newDeviceId(), null, null, "Critical", 99, "fake task", "", 1, "ALPHA", now())
        inject(Outbox(Ids.newDeviceId(), TestBase.pinned).team(PacketType.TASK, fakeTask.toBytes(), "ALPHA", teamKey))
        waitUntil("unsigned task dropped") { Graph.mesh.engine.metrics.forgedDrops > forgedBefore }
        assertNull(taskOf(fakeTask.caseId))

        // Take the SOS seen directly, then work through the base's task.
        tab(en("nearby_sos"))
        tap(en("take_this"))
        waitUntil("direct task accepted") { taskOf(sos.caseId)?.state == TaskState.ACCEPTED }
        tab(en("tasks"))
        click(hasText("Fire · 3 people", substring = true) and hasClickAction())
        waitRoute("task/${baseTask.caseId}")
        waitText(en("nav_google_maps"))
        waitText(en("open_map_app"))
        tap(en("accept"))
        waitUntil("task accepted") { taskOf(baseTask.caseId)?.state == TaskState.ACCEPTED }
        tap(en("on_my_way"))
        waitUntil("on the way") { taskOf(baseTask.caseId)?.state == TaskState.ON_THE_WAY }
        tap(en("rescued"))
        waitUntil("rescued") { taskOf(baseTask.caseId)?.state == TaskState.RESCUED }
        back()

        tab(en("map"))
        waitText(en("breadcrumb"))

        // Team chat (encrypted with the team key), both ways.
        tab(en("team"))
        tap(en("team_chat"))
        waitRoute("team_chat")
        typeInto(hasSetTextAction(), "Reached the temple, need a stretcher")
        tapIcon(en("send"))
        waitText("Reached the temple, need a stretcher")
        inject(Outbox(Ids.newDeviceId(), TestBase.pinned).team(PacketType.TEAM_CHAT, TeamChatPayload("Arun", "On my way with a stretcher", now()).toBytes(), "ALPHA", teamKey))
        waitText("On my way with a stretcher")
        back()

        // Lock: wrong PIN, right PIN.
        tapIcon(en("lock"))
        type(en("pin"), "0000")
        tap(en("unlock"))
        waitText(en("wrong_pin"))
        type(en("pin"), "1234")
        tap(en("unlock"))
        waitFor(tabNode(en("tasks")))

        // Log out: the ID is removed and the phone is public again.
        tab(en("team"))
        tap(en("log_out"))
        waitUntil("logged out") { Graph.prefs.now.role == Roles.PUBLIC && Graph.rescuer.bundle() == null }
        waitText(en("rescue_modes"))
        waitUntil("mesh engine left rescuer mode") { !Graph.mesh.engine.isRescuer }
    }

    @Test
    fun a4b_googleMapsLinks() {
        assertEquals("google.navigation:q=11.664300,78.146000&mode=d", MapsLink.navigationUri(11.6643, 78.146).toString())
        assertEquals("geo:11.664300,78.146000?q=11.664300,78.146000(SOS%20Ravi)", MapsLink.pinUri(11.6643, 78.146, "SOS Ravi").toString())
        assertEquals("geo:11.664300,78.146000?q=11.664300,78.146000(a%20b)", MapsLink.pinUri(11.6643, 78.146, "a(b)").toString())
        assertEquals("https://maps.google.com/?q=11.664300,78.146000", MapsLink.webUrl(11.6643, 78.146))
        assertEquals("https://maps.google.com/?q=-0.000010,-79.000000", MapsLink.webUrl(-0.00001, -79.0))
    }

    @Test
    fun a5_noCrashRecorded() {
        val crash = CrashLog.read()
        assertNull("The app recorded a crash:\n$crash", crash)
    }

    /* ======================= helpers ======================= */

    private fun en(key: String) = L.get("en", key)
    private fun now() = System.currentTimeMillis()
    private fun act(): MainActivity = rule.activity
    private fun caseOf(id: String): CaseItem? = Graph.store.cases.value.firstOrNull { it.caseId == id }
    private fun taskOf(id: String): TaskItem? = Graph.store.tasks.value.firstOrNull { it.caseId == id }
    private fun rescuerOf(id: String): RescuerRecord? = Graph.store.rescuers.value.firstOrNull { it.rescuerId == id }

    /** A packet arriving from a neighbouring phone over Nearby. */
    private fun inject(p: Packet, from: String = Ids.newDeviceId()) = Graph.mesh.onBytes(from, PacketCodec.encode(p))

    private fun ui(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.waitForIdle()
    }

    private fun route(): String {
        val a = act()
        return rule.runOnUiThread { a.nav.current }
    }

    private fun depth(): Int {
        val a = act()
        return rule.runOnUiThread { a.nav.stack.size }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMillis = timeoutMs) { condition() }
        } catch (e: Throwable) {
            val where = try {
                route()
            } catch (_: Throwable) {
                "?"
            }
            throw AssertionError("Timed out after $timeoutMs ms waiting for: $what (screen: $where)", e)
        }
    }

    private fun eventually(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun waitRoute(r: String) = waitUntil("screen $r") { route() == r }

    private fun has(m: SemanticsMatcher): Boolean = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /** Scrolls lazy lists until a node matching [m] is composed. */
    private fun reveal(m: SemanticsMatcher): Boolean {
        val lists = rule.onAllNodes(hasScrollToNodeAction())
        val n = lists.fetchSemanticsNodes().size
        for (i in 0 until n) {
            try {
                lists[i].performScrollToNode(m)
            } catch (_: Throwable) {
                continue
            }
            if (has(m)) return true
        }
        return false
    }

    private fun find(m: SemanticsMatcher, timeoutMs: Long = 15_000): SemanticsNodeInteraction {
        waitUntil(m.description, timeoutMs) { has(m) || reveal(m) }
        val node = rule.onAllNodes(m).onFirst()
        try {
            node.performScrollTo()
        } catch (_: Throwable) {
            // Not inside a scrolling container.
        }
        return node
    }

    private fun waitFor(m: SemanticsMatcher, timeoutMs: Long = 15_000) {
        find(m, timeoutMs)
    }

    private fun waitText(text: String, timeoutMs: Long = 15_000) = waitFor(hasText(text, substring = true), timeoutMs)

    private fun click(m: SemanticsMatcher, timeoutMs: Long = 15_000) {
        find(m, timeoutMs).performClick()
        rule.waitForIdle()
    }

    private fun tap(text: String) = click(hasText(text) and hasClickAction() and isEnabled())
    private fun tapIcon(description: String) = click(hasContentDescription(description) and hasClickAction())

    private fun tabNode(label: String): SemanticsMatcher =
        hasText(label, substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun tab(label: String) = click(tabNode(label))

    private fun typeInto(m: SemanticsMatcher, text: String) {
        val node = find(m)
        node.performTextClearance()
        node.performTextInput(text)
        rule.waitForIdle()
    }

    /** Types into the text field with this label. */
    private fun type(label: String, text: String) = typeInto(hasSetTextAction() and hasText(label), text)

    private fun go(r: String) {
        ui { act().nav.go(r) }
        waitRoute(r)
    }

    /** Opens a screen directly, lets it draw, and goes back. */
    private fun visit(r: String) {
        go(r)
        rule.waitForIdle()
        back()
    }

    /** System Back (the same path as the phone's back gesture). */
    private fun back() {
        val before = depth()
        assertTrue("Back on the first screen would close the app (screen ${route()})", before > 1)
        val a = act()
        rule.runOnUiThread { a.onBackPressedDispatcher.onBackPressed() }
        waitUntil("back") { depth() < before }
    }

    private fun finishSetup() {
        if (route() != "onboarding") return
        tap(en("next"))
        type(en("your_name"), "Test User")
        tap(en("next"))
        val start = hasText(en("start")) and hasClickAction()
        val startAnyway = hasText(en("start_anyway")) and hasClickAction()
        waitUntil("permission step") { has(start) || has(startAnyway) }
        if (has(start)) tap(en("start")) else tap(en("start_anyway"))
        waitRoute("home")
    }

    private fun toPublicHome() {
        if (Graph.prefs.now.role != Roles.PUBLIC) {
            Graph.prefs.update { it.copy(role = Roles.PUBLIC) }
            Graph.mesh.onRoleChanged()
        }
        ui { act().nav.replace("home") }
        waitRoute("home")
        waitText(en("rescue_modes"))
    }

    private fun expectCrypto(block: () -> Unit) {
        try {
            block()
        } catch (e: CryptoException) {
            return
        }
        fail("Expected the key or PIN check to fail")
    }

    private class Reply(val code: Int, val body: String, val cookie: String?)

    private fun http(method: String, path: String, body: String? = null, cookie: String? = null): Reply {
        val c = URL("http://127.0.0.1:${DashboardServer.PORT}$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5_000
            c.readTimeout = 15_000
            if (cookie != null) c.setRequestProperty("Cookie", cookie)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
            return Reply(code, text, c.getHeaderField("Set-Cookie"))
        } finally {
            c.disconnect()
        }
    }

    private fun waitForServer() {
        val end = System.currentTimeMillis() + 30_000
        var last: Throwable? = null
        while (System.currentTimeMillis() < end) {
            try {
                if (http("GET", "/").code == 200) return
            } catch (e: Throwable) {
                last = e
            }
            Thread.sleep(500)
        }
        throw AssertionError("Dashboard did not start (service running ${MeshService.running}, urls ${Graph.base.serverUrls.value})", last)
    }

    companion object {
        private val PUBLIC_ROUTES = listOf(
            "alerts", "chat", "missing", "shelters", "map", "firstaid", "report", "volunteer", "profile", "network",
            "selftest", "modes", "safe", "findme", "about", "log", "sos_form", "sos_status",
        )
        private var errorsPrinted = 0
        private var logPrinted = 0L
    }
}
