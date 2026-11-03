package com.ameer.uyirmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoPriorityPayloadTest {

    @Test
    fun haversineDistanceAndBearing() {
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 0.0, 1.0), 5.0)
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 5.0)
        assertEquals(0.0, Geo.distanceM(11.66, 78.14, 11.66, 78.14), 1e-6)
        val salem = LatLon(11.6643, 78.1460)
        val chennai = LatLon(13.0827, 80.2707)
        assertEquals(279_000.0, Geo.distanceM(salem, chennai), 4_000.0)
        assertEquals(0.0, Geo.bearingDeg(LatLon(10.0, 78.0), LatLon(11.0, 78.0)), 1e-6)
        assertEquals(90.0, Geo.bearingDeg(LatLon(0.0, 78.0), LatLon(0.0, 79.0)), 1e-6)
        assertEquals("N", Geo.compassName(359.0))
        assertEquals("E", Geo.compassName(90.0))
        assertEquals("SW", Geo.compassName(225.0))
        assertEquals("850 m", Geo.formatDistance(850.4))
        assertEquals("2.5 km", Geo.formatDistance(2_500.0))
        assertEquals("12 km", Geo.formatDistance(12_300.0))
        assertEquals(4, Geo.etaMinutes(1_000.0, 15.0))
        assertEquals(1, Geo.etaMinutes(10.0, 15.0))
        assertTrue(Geo.isValid(11.0, 78.0))
        assertFalse(Geo.isValid(null, 78.0))
        assertFalse(Geo.isValid(0.0, 0.0))
        assertFalse(Geo.isValid(95.0, 78.0))
        val (x, y) = Geo.toLocalMetres(LatLon(11.0, 78.0), LatLon(11.001, 78.001))
        assertEquals(109.2, x, 1.0)
        assertEquals(111.2, y, 1.0)
    }

    @Test
    fun dbscanFindsHotspotsAndIgnoresNoise() {
        val base = LatLon(11.66, 78.14)
        fun near(dLat: Double, dLon: Double) = LatLon(base.lat + dLat, base.lon + dLon)
        val pts = listOf(
            near(0.0, 0.0), near(0.0002, 0.0001), near(-0.0001, 0.0002), near(0.0001, -0.0002), // cluster A (~25 m)
            near(0.02, 0.02), near(0.0202, 0.0201), near(0.0199, 0.0198), // cluster B (~2.5 km away)
            near(-0.05, 0.05), // noise
        )
        val clusters = Geo.dbscan(pts, epsM = 60.0, minPts = 3)
        assertEquals(2, clusters.size)
        assertEquals(listOf(0, 1, 2, 3), clusters[0])
        assertEquals(listOf(4, 5, 6), clusters[1])
        assertTrue(Geo.dbscan(emptyList(), 50.0, 2).isEmpty())
    }

    @Test
    fun routeOrderIsOptimalForSmallSets() {
        val rnd = java.util.Random(3)
        repeat(30) {
            val start = LatLon(11.6, 78.1)
            val stops = List(6) { LatLon(11.6 + rnd.nextDouble() * 0.05, 78.1 + rnd.nextDouble() * 0.05) }
            val order = Geo.routeOrder(start, stops)
            assertEquals(stops.indices.toList(), order.sorted())
            val best = permutations(stops.indices.toList()).minOf { Geo.routeLengthM(start, stops, it) }
            val got = Geo.routeLengthM(start, stops, order)
            // Nearest neighbour + 2-opt is within 10% of the optimum for these small sets.
            assertTrue("got $got best $best", got <= best * 1.10 + 1e-6)
        }
        assertEquals(emptyList<Int>(), Geo.routeOrder(LatLon(0.0, 0.0), emptyList()))
    }

    private fun permutations(items: List<Int>): List<List<Int>> =
        if (items.size <= 1) listOf(items) else items.flatMap { x -> permutations(items - x).map { listOf(x) + it } }

    @Test
    fun forestMatchesScikitLearnPredictions() {
        val model = ForestModel.fromJson(resourceText("priority_model.json"))
        assertEquals(Triage.FEATURES, model.featureNames)
        assertEquals(Triage.CLASSES, model.classNames)
        assertEquals(30, model.nTrees)
        @Suppress("UNCHECKED_CAST")
        val vectors = Json.parse(resourceText("priority_vectors.json")) as List<Map<String, Any?>>
        assertEquals(60, vectors.size)
        for (v in vectors) {
            val inp = v.obj("input")
            val input = TriageInput(
                inp.str("kind"), inp.int("people"), inp.int("children"), inp.int("elderly"), inp.int("disabled"),
                inp.int("pregnant"), inp.bool("injured"), inp.strList("needs"), inp.int("battery"),
            )
            val x = Triage.features(input)
            val expectedX = v.list("x").map { (it as Number).toDouble() }
            assertEquals(expectedX, x.toList())
            val expected = v.list("proba").map { (it as Number).toDouble() }
            val got = model.predictProba(x)
            for (c in 0 until 4) assertEquals(expected[c], got[c], 1e-4)
            // Explanations add up: bias + contributions = prediction.
            val e = model.explain(x)
            for (c in 0 until 4) {
                val sum = e.bias[c] + (0 until model.nFeatures).sumOf { e.contributions[it][c] }
                assertEquals(got[c], sum, 1e-9)
                assertEquals(got[c], e.proba[c], 1e-12)
            }
        }
    }

    @Test
    fun triageScoreLabelsAndReasons() {
        val model = ForestModel.fromJson(resourceText("priority_model.json"))
        val critical = TriageInput(EmergencyKind.FIRE, 6, 2, 2, 1, 1, true, listOf(Needs.MEDICINE, Needs.WATER), 8)
        val low = TriageInput(EmergencyKind.NEED, 1, 0, 0, 0, 0, false, listOf(Needs.FOOD), 90)
        val c = Triage.assess(model, critical, waitingMinutes = 0.0, repeats = 0)
        val l = Triage.assess(model, low, waitingMinutes = 0.0, repeats = 0)
        assertEquals("Critical", c.label)
        assertTrue("critical score ${c.score}", c.score >= 80)
        assertTrue("low score ${l.score}", l.score < 35)
        assertEquals("Low", l.label)
        assertTrue(c.reasons.isNotEmpty())
        assertTrue(c.reasons.any { it.label == "Injured" })
        assertEquals(c.reasons, c.reasons.sortedByDescending { it.points })
        // Waiting and repeated messages raise the score, with caps.
        val aged = Triage.assess(model, low, waitingMinutes = 45.0, repeats = 2)
        assertEquals(l.modelScore + 30 + 6, aged.score)
        assertTrue(aged.reasons.any { it.label.startsWith("Waiting") })
        assertEquals(l.modelScore + 12, Triage.assess(model, low, 0.0, 10).score)
        assertEquals("High", Triage.labelFor(60))
        assertEquals("Medium", Triage.labelFor(35))
        assertEquals("Low", Triage.labelFor(34))
        // Unknown battery is treated as 50%.
        assertEquals(50.0, Triage.features(low.copy(battery = -1))[16], 0.0)
    }

    @Test
    fun payloadsRoundTrip() {
        val sos = TestUtil.sampleSos()
        assertEquals(sos, SosPayload.fromBytes(sos.toBytes()))
        val upd = SosUpdate("c", 11.1234567, 78.7654321, 5.0, 123L, 4, true, "moving", 99L)
        val updBack = SosUpdate.fromBytes(upd.toBytes())
        assertEquals(11.123457, updBack.lat!!, 1e-9)
        assertTrue(updBack.lowBattery)
        val media = SosMedia("c", "audio/amr", 15_000, Crypto.random(1000), 5L)
        val mediaBack = SosMedia.fromBytes(media.toBytes())
        assertTrue(media.data.contentEquals(mediaBack.data))
        assertEquals("audio/amr", mediaBack.mime)
        assertEquals(SosCancel("c", "s", 1L), SosCancel.fromBytes(SosCancel("c", "s", 1L).toBytes()))
        val ack = AckPayload("c", AckStatus.ASSIGNED, "ALPHA", 9, "m", 2L, "base", "", 3, listOf("aa", "bb"))
        assertEquals(ack, AckPayload.fromBytes(ack.toBytes()))
        val alert = AlertPayload("a", AlertKind.SHELTER, 2, "School", "Go", 11.0, 78.0, 200, 300, 120, 9L, 1L)
        assertEquals(alert, AlertPayload.fromBytes(alert.toBytes()))
        assertEquals(ChatPayload("n", "x", 1L), ChatPayload.fromBytes(ChatPayload("n", "x", 1L).toBytes()))
        val safe = SafePayload("n", "safe", 11.0, 78.0, "School", 3L)
        assertEquals(safe, SafePayload.fromBytes(safe.toBytes()))
        val rep = ReportPayload("r", ReportKind.ROAD_BLOCK, "tree", 11.0, 78.0, "n", 4L)
        assertEquals(rep, ReportPayload.fromBytes(rep.toBytes()))
        val vol = VolunteerPayload("n", "1", listOf(Skills.SWIMMER), "boat", 11.0, 78.0, 5L)
        assertEquals(vol, VolunteerPayload.fromBytes(vol.toBytes()))
        val hb = HeartbeatPayload(Roles.RESCUER, 40, listOf("a", "b"), 11.0, 78.0, "ALPHA", "r1", "K", "1.0", 6L)
        assertEquals(hb, HeartbeatPayload.fromBytes(hb.toBytes()))
        val task = TaskPayload("c", "dev", 11.0, 78.0, "High", 70, "sum", "med", 3, "ALPHA", 7L)
        assertEquals(task, TaskPayload.fromBytes(task.toBytes()))
        val ts = TaskStatusPayload("c", "r1", "K", "ALPHA", TaskState.ON_THE_WAY, 11.0, 78.0, 8L)
        assertEquals(ts, TaskStatusPayload.fromBytes(ts.toBytes()))
        assertEquals(TeamChatPayload("K", "hi", 9L), TeamChatPayload.fromBytes(TeamChatPayload("K", "hi", 9L).toBytes()))
        assertEquals(FindRequest("r", "Ravi", 1L), FindRequest.fromBytes(FindRequest("r", "Ravi", 1L).toBytes()))
        val fr = FindResult("r", "Ravi", listOf(FindMatch("Ravi K", "SAFE", "School", 3L)), 4L)
        assertEquals(fr, FindResult.fromBytes(fr.toBytes()))
        val card = ProfileCard("dev", "Ameer", "98", "O+", "Penicillin", "Asthma", "Inhaler", "Dad", "97")
        assertEquals(card, ProfileCard.fromQr(card.toQr()))
        assertEquals("Blood O+; Allergies: Penicillin; Conditions: Asthma; Medicines: Inhaler", card.medicalSummary())
        expectThrows<FormatException> { ProfileCard.fromQr("hello") }
        // Missing fields fall back to safe defaults.
        val minimal = SosPayload.fromBytes("{\"c\":\"x\"}".toByteArray())
        assertEquals(1, minimal.people)
        assertEquals(EmergencyKind.TRAPPED, minimal.kind)
        assertEquals(-1, minimal.battery)
    }
}
