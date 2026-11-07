package com.ameer.uyirmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Version 2, part 3: note words, dispatch planning, learned ETAs, SOS checks, power rules, base beacon, secure export. */
class Version2bTest {
    private val t0 = 1_760_000_000_000L

    @Test
    fun noteWordsAddCappedPointsWithReasons() {
        val hits = NoteScan.scan("My father is not breathing and there is a lot of blood")
        assertEquals(listOf("not breathing", "bleeding"), hits.map { it.label })
        val reasons = NoteScan.reasons("My father is not breathing and there is a lot of blood")
        assertEquals(NoteScan.CAP, reasons.sumOf { it.points })
        assertEquals("Note: not breathing", reasons[0].label)
        // Negation in English, Tamil and Tanglish.
        assertTrue(NoteScan.scan("no bleeding, we only need food").isEmpty())
        assertTrue(NoteScan.scan("ரத்தம் இல்லை, சாப்பாடு வேண்டும்").isEmpty())
        assertTrue(NoteScan.scan("paati mayakkam illa").isEmpty())
        assertEquals("chest pain or heart attack", NoteScan.scan("அப்பாவுக்கு நெஞ்சு வலி")[0].label)
        assertEquals("unconscious", NoteScan.scan("Paati mayakkam aagitaanga")[0].label)
        // Word boundaries: "heatstroke" does not contain the word "stroke"; "heartburn" is not a burn.
        assertTrue(NoteScan.scan("mild heatstroke and heartburn").none { it.label == "seizure or stroke" || it.label == "burns" })
        assertEquals(listOf("newborn baby", "water rising"), NoteScan.scan("Water is rising fast, we have a 2 months old baby").map { it.label })
        assertTrue(NoteScan.scan("").isEmpty())
    }

    @Test
    fun noteAndAnimalsRaiseTheScoreButNotTheModelPart() {
        val model = ForestModel.fromJson(resourceText("priority_model.json"))
        val base = TriageInput(EmergencyKind.NEED, 2, 0, 0, 0, 0, false, listOf(Needs.FOOD), 70)
        val plain = Triage.assess(model, base, 0.0, 0)
        val urgent = Triage.assess(model, base.copy(note = "grandmother unconscious", animals = 6), 0.0, 0)
        assertEquals(plain.modelScore, urgent.modelScore)
        assertEquals(plain.score + 10 + Triage.MAX_ANIMAL_POINTS, urgent.score)
        assertTrue(urgent.reasons.any { it.label == "Note: unconscious" && it.points == 10 })
        assertTrue(urgent.reasons.any { it.label == "6 animals" && it.points == Triage.MAX_ANIMAL_POINTS })
        // TriageInput.from copies the note and the animals from the SOS.
        val s = SosPayload("c1", EmergencyKind.FLOOD, 3, note = "water rising", animals = 2)
        val i = TriageInput.from(s)
        assertEquals("water rising", i.note)
        assertEquals(2, i.animals)
    }

    @Test
    fun hungarianFindsTheCheapestPlan() {
        val cost = arrayOf(
            doubleArrayOf(4.0, 1.0, 3.0),
            doubleArrayOf(2.0, 0.0, 5.0),
            doubleArrayOf(3.0, 2.0, 2.0),
        )
        val a = Hungarian.solve(cost)
        assertEquals(5.0, Hungarian.total(cost, a), 1e-9)
        assertEquals(listOf(1, 0, 2), a.toList())
        // Random square and rectangular problems against trying every possibility.
        val rnd = Random(7)
        for (round in 0 until 60) {
            val rows = 1 + rnd.nextInt(5)
            val cols = 1 + rnd.nextInt(5)
            val c = Array(rows) { DoubleArray(cols) { (rnd.nextInt(200) - 80).toDouble() } }
            val got = Hungarian.solve(c)
            assertEquals("round $round", brute(c), Hungarian.total(c, got), 1e-9)
            // Every column used at most once, and as many rows served as possible.
            val usedCols = got.filter { it >= 0 }
            assertEquals(usedCols.size, usedCols.distinct().size)
            assertEquals(minOf(rows, cols), usedCols.size)
        }
        assertEquals(0, Hungarian.solve(emptyArray()).size)
    }

    private fun brute(c: Array<DoubleArray>): Double {
        val rows = c.size
        val cols = c[0].size
        var best = Double.POSITIVE_INFINITY
        fun go(r: Int, used: BooleanArray, sum: Double, assigned: Int) {
            if (r == rows) {
                if (assigned == minOf(rows, cols)) best = minOf(best, sum)
                return
            }
            // Row r takes a free column, or (only when rows > cols) nothing.
            for (j in 0 until cols) if (!used[j]) {
                used[j] = true
                go(r + 1, used, sum + c[r][j], assigned + 1)
                used[j] = false
            }
            if (rows > cols) go(r + 1, used, sum, assigned)
        }
        go(0, BooleanArray(cols), 0.0, 0)
        return best
    }

    @Test
    fun plannerSendsTeamsToTheMostUrgentCasesFirst() {
        val travel = { t: TeamSlot, c: WaitingCase -> Geo.etaMinutes(Geo.distanceM(t.lat!!, t.lon!!, c.lat!!, c.lon!!), 15.0) }
        val alpha = TeamSlot("ALPHA", 11.660, 78.140, 1)
        val bravo = TeamSlot("BRAVO", 11.700, 78.140, 1)
        val nearAlphaLow = WaitingCase("low", 11.661, 78.140, 20)
        val nearAlphaCritical = WaitingCase("critical", 11.665, 78.141, 95)
        val nearBravoHigh = WaitingCase("high", 11.699, 78.141, 70)
        val plan = Planner.plan(listOf(alpha, bravo), listOf(nearAlphaLow, nearAlphaCritical, nearBravoHigh), travel)
        assertEquals(mapOf("critical" to "ALPHA", "high" to "BRAVO"), plan.associate { it.caseId to it.team })
        assertEquals("critical", plan.first().caseId)
        // A greedy "nearest team first" would send BRAVO across town if ALPHA was taken by a case BRAVO could reach.
        val crossTown = Planner.plan(listOf(alpha, bravo), listOf(WaitingCase("a", 11.661, 78.140, 80), WaitingCase("b", 11.699, 78.140, 80)), travel)
        assertEquals(mapOf("a" to "ALPHA", "b" to "BRAVO"), crossTown.associate { it.caseId to it.team })
        // Unknown positions still get planned, and no team gets more than its free places.
        val blind = Planner.plan(listOf(alpha.copy(free = 2)), listOf(WaitingCase("x", null, null, 50), WaitingCase("y", null, null, 60), WaitingCase("z", null, null, 40)), travel)
        assertEquals(listOf("y", "x"), blind.map { it.caseId })
        assertTrue(blind.all { it.etaMin == Planner.UNKNOWN_MINUTES })
        assertTrue(Planner.plan(listOf(alpha.copy(free = 0)), listOf(nearAlphaLow), travel).isEmpty())
    }

    @Test
    fun etaModelLearnsFromFinishedRescues() {
        val fresh = EtaModel(15.0, emptyList())
        assertEquals(15.0, fresh.kmh, 1e-9)
        assertEquals(8, fresh.minutes(2_000.0))
        // Rescues took 12 minutes per km (5 km/h): the estimate moves that way, more with every rescue.
        val samples = listOf(EtaSample(1_000.0, 12.0), EtaSample(2_000.0, 24.0), EtaSample(500.0, 6.0))
        val three = EtaModel(15.0, samples)
        assertEquals(3, three.sampleCount)
        assertEquals((4.0 + 12.0) / 2, three.pace, 1e-9)
        val six = EtaModel(15.0, samples + samples)
        assertTrue(six.kmh < three.kmh && three.kmh < fresh.kmh)
        assertEquals(16, three.minutes(2_000.0))
        // Nonsense samples are ignored: 10 km in 2 minutes, a 3 m trip, a 20 hour task.
        val odd = EtaModel(15.0, listOf(EtaSample(10_000.0, 2.0), EtaSample(3.0, 5.0), EtaSample(2_000.0, 1_200.0)))
        assertEquals(0, odd.sampleCount)
        assertEquals(-1, fresh.minutes(-1.0))
    }

    @Test
    fun sosChecksRaiseFlagsButNeverScores() {
        val ok = SosChecks.Input(t0, t0 - 60_000, hasPosition = true, messages = 1, otherRecentCases = 0, recentCancels = 0)
        assertTrue(SosChecks.flags(ok).isEmpty())
        assertEquals(listOf(SosChecks.BURST), SosChecks.flags(ok.copy(otherRecentCases = 2)))
        assertEquals(listOf(SosChecks.NO_GPS), SosChecks.flags(ok.copy(hasPosition = false, messages = 3)))
        assertTrue(SosChecks.flags(ok.copy(hasPosition = false, messages = 2)).isEmpty())
        assertEquals(listOf(SosChecks.OLD_CLOCK), SosChecks.flags(ok.copy(createdAt = t0 + 3600_000)))
        assertEquals(listOf(SosChecks.OLD_CLOCK), SosChecks.flags(ok.copy(createdAt = t0 - 13 * 3600_000L)))
        assertEquals(listOf(SosChecks.REPEAT_CANCEL), SosChecks.flags(ok.copy(recentCancels = 2)))
        // 12 km in 3 minutes is impossible; 1 km in 10 minutes is a walk.
        assertEquals("JUMP:12.2 km", SosChecks.jump(11.60, 78.14, t0, 11.71, 78.14, t0 + 180_000))
        assertNull(SosChecks.jump(11.600, 78.14, t0, 11.609, 78.14, t0 + 600_000))
        assertNull("far apart but hours later", SosChecks.jump(11.60, 78.14, t0, 11.71, 78.14, t0 + 3 * 3600_000L))
        assertEquals(listOf("JUMP:12.2 km", SosChecks.NO_GPS), SosChecks.merge(listOf(SosChecks.BURST, "JUMP:12.2 km"), listOf(SosChecks.NO_GPS)))
    }

    @Test
    fun duplicatesBySpotPhoneAndName() {
        val a = CaseFacts("a", "dev1", EmergencyKind.FLOOD, t0, 11.6600, 78.1400, "Lakshmi", "+91 98400 12345")
        assertEquals(Duplicates.SAME_SPOT, Duplicates.reason(a, a.copy(caseId = "b", device = "dev2", lat = 11.6601, name = "", phone = "")))
        assertEquals(Duplicates.SAME_PHONE, Duplicates.reason(a, a.copy(caseId = "b", device = "dev2", lat = 11.70, kind = EmergencyKind.MEDICAL, phone = "09840012345")))
        assertEquals(Duplicates.SAME_NAME, Duplicates.reason(a, a.copy(caseId = "b", device = "dev2", lat = 11.6630, kind = EmergencyKind.MEDICAL, name = "Lakshmy", phone = "")))
        assertNull("same name but 5 km away", Duplicates.reason(a, a.copy(caseId = "b", device = "dev2", lat = 11.705, name = "Lakshmi", phone = "")))
        assertNull("same phone sending again is not a duplicate", Duplicates.reason(a, a.copy(caseId = "b")))
        assertNull(Duplicates.reason(a, a.copy(caseId = "b", device = "dev2", lat = 11.6630, kind = EmergencyKind.MEDICAL, name = "Ravi", phone = "")))
        assertEquals("9840012345", Duplicates.digits("+91 98400-12345"))
    }

    @Test
    fun mediaAndPowerFollowTheLink() {
        assertEquals(MediaLevel.NORMAL, MediaQuality.pick(peers = 2, baseHops = 2, battery = 80, waiting = 0))
        assertEquals(MediaLevel.LOW, MediaQuality.pick(peers = 2, baseHops = 5, battery = 80, waiting = 0))
        assertEquals(MediaLevel.LOW, MediaQuality.pick(peers = 2, baseHops = -1, battery = 18, waiting = 0))
        assertEquals(MediaLevel.VERY_LOW, MediaQuality.pick(peers = 0, baseHops = 1, battery = 80, waiting = 0))
        assertTrue(MediaLevel.VERY_LOW.photoBytes < MediaLevel.LOW.photoBytes && MediaLevel.LOW.photoBytes < MediaLevel.NORMAL.photoBytes)

        assertEquals(3, PowerPolicy.relayMaxQos(battery = 50, charging = false, relayMode = false, auto = true))
        assertEquals(2, PowerPolicy.relayMaxQos(battery = 18, charging = false, relayMode = false, auto = true))
        assertEquals(1, PowerPolicy.relayMaxQos(battery = 9, charging = false, relayMode = false, auto = true))
        assertEquals(3, PowerPolicy.relayMaxQos(battery = 9, charging = true, relayMode = false, auto = true))
        assertEquals(3, PowerPolicy.relayMaxQos(battery = 9, charging = false, relayMode = false, auto = false))
        assertEquals(3, PowerPolicy.relayMaxQos(battery = 18, charging = false, relayMode = true, auto = true))
        assertTrue(PowerPolicy.lowPowerRadio(battery = 15, charging = false, relayMode = false, auto = true))
        assertFalse(PowerPolicy.lowPowerRadio(battery = 15, charging = false, relayMode = true, auto = true))
    }

    @Test
    fun lowBatteryPhoneStopsRelayingPhotosButNotSos() {
        val me = Ids.newDeviceId()
        val engine = MeshEngine(me, { t0 })
        engine.setNeighbours(listOf(Ids.newDeviceId()))
        val src1 = Outbox(Ids.newDeviceId(), TestUtil.pinned) { t0 }
        val src2 = Outbox(Ids.newDeviceId(), TestUtil.pinned) { t0 }
        engine.relayMaxQos = 2
        val photo = engine.receive("x", PacketCodec.encode(src1.plain(PacketType.SOS_MEDIA, ByteArray(100), Destinations.BASE)))
        assertFalse(photo.forwarded)
        assertEquals(1L, engine.metrics.powerSkips)
        val sos = engine.receive("x", PacketCodec.encode(src2.plain(PacketType.SOS, ByteArray(10), Destinations.BASE)))
        assertTrue(sos.forwarded)
        engine.relayMaxQos = 3
        val photo2 = engine.receive("x", PacketCodec.encode(src1.plain(PacketType.SOS_MEDIA, ByteArray(100), Destinations.BASE)))
        assertTrue(photo2.forwarded)
        // Crowd count: two different phones were heard.
        assertEquals(2, engine.phonesHeard(30 * 60_000L))
    }

    @Test
    fun baseBeaconMustBeSignedByTheBase() {
        val pinned = TestUtil.pinned
        val out = Outbox(Ids.newDeviceId(), pinned) { t0 }
        val b = BeaconPayload("Salem Control", 11.6643, 78.1460, 4, t0)
        val signed = PacketCodec.decode(PacketCodec.encode(out.signedByBase(TestUtil.basePrivate, PacketType.BASE_BEACON, b.toBytes(), Destinations.ALL).relayCopy(2)))
        assertTrue(Authenticity.check(pinned, signed, t0))
        assertTrue(PacketType.BASE_BEACON in Authenticity.BASE_ONLY)
        assertFalse(Authenticity.check(pinned, out.plain(PacketType.BASE_BEACON, b.toBytes(), Destinations.ALL), t0))
        val back = BeaconPayload.fromBytes(signed.payload)
        assertEquals("Salem Control", back.baseName)
        assertEquals(4, back.openCases)
        assertEquals(11.6643, back.lat!!, 1e-9)
        assertEquals(1, signed.hops)
    }

    @Test
    fun secureZipLooksLikeAnAesZip() {
        val csv = "case_id,name\nc1,Lakshmi\n".repeat(50).toByteArray()
        val zip = SecureZip.write(listOf(SecureZip.Entry("cases.csv", csv, t0), SecureZip.Entry("readme.txt", "hello".toByteArray(), t0)), "rescue-2026")
        assertEquals(0x50, zip[0].toInt())
        assertEquals(0x4b, zip[1].toInt())
        // The names are visible, the content is not.
        val text = String(zip, Charsets.ISO_8859_1)
        assertTrue(text.contains("cases.csv"))
        assertFalse(text.contains("Lakshmi"))
        assertTrue(text.contains("AE"))
        // Known PBKDF2-HMAC-SHA1 answer (RFC 6070: "password", "salt", 2 rounds).
        val k = SecureZip.pbkdf2("password".toByteArray(), "salt".toByteArray(), 2, 20)
        assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", k.joinToString("") { "%02x".format(it) })
        var threw = false
        try {
            SecureZip.write(listOf(SecureZip.Entry("a", ByteArray(1))), "12345")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("passwords under 6 letters are refused", threw)
        System.getProperty("uyirmesh.zipOut")?.let { java.io.File(it).writeBytes(zip) }
    }
}
