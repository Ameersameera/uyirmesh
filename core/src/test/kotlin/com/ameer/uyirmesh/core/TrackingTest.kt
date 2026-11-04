package com.ameer.uyirmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingTest {
    private val victim = LatLon(11.6643, 78.1460)
    private val t0 = 1_760_000_000_000L
    private val metresPerDegLat = Geo.EARTH_RADIUS_M * Math.PI / 180

    /** A position [northM] metres north and [eastM] metres east of the victim, [min] minutes after t0. */
    private fun at(min: Double, northM: Double, eastM: Double = 0.0): Fix {
        val lat = victim.lat + northM / metresPerDegLat
        val lon = victim.lon + eastM / (metresPerDegLat * Math.cos(Math.toRadians(victim.lat)))
        return Fix(lat, lon, t0 + (min * 60_000).toLong())
    }

    @Test
    fun walkingTowardsTheVictimIsCloserWithMeasuredEta() {
        // Starts 1.5 km south, walks north at 80 m/min, one position a minute for 10 minutes.
        val fixes = (0..10).map { at(it.toDouble(), -1500.0 + 80.0 * it) }
        val a = Tracking.approach(victim, fixes, 5.0)!!
        assertEquals(Tracking.Trend.CLOSER, a.trend)
        assertEquals(700.0, a.distanceM, 5.0)
        assertEquals(1100.0, a.earlierM!!, 5.0)
        assertEquals(5, a.overMin)
        assertEquals(0, a.stillMin)
        assertEquals(9, a.etaMin) // 700 m at the measured 80 m/min
        assertEquals(fixes.last().at, a.at)
    }

    @Test
    fun walkingAwayIsAway() {
        val fixes = (0..6).map { at(it.toDouble(), -300.0 - 70.0 * it) }
        val a = Tracking.approach(victim, fixes, 5.0)!!
        assertEquals(Tracking.Trend.AWAY, a.trend)
        assertEquals(720.0, a.distanceM, 5.0)
        assertEquals(Geo.etaMinutes(a.distanceM, 5.0), a.etaMin)
    }

    @Test
    fun stoppingIsStillEvenAfterComingCloser() {
        // Walks closer for 5 minutes, then stays put (with GPS jitter) for 6 minutes.
        val walking = (0..5).map { at(it.toDouble(), -2000.0 + 100.0 * it) }
        val stopped = (6..11).map { at(it.toDouble(), -1500.0 + if (it % 2 == 0) 12.0 else -9.0, if (it % 3 == 0) 8.0 else 0.0) }
        val a = Tracking.approach(victim, walking + stopped, 5.0)!!
        assertEquals(Tracking.Trend.STILL, a.trend)
        assertTrue("still for ${a.stillMin} min", a.stillMin in 5..6)
    }

    @Test
    fun withinSixtyMetresHasArrived() {
        val fixes = listOf(at(0.0, -400.0), at(3.0, -150.0), at(6.0, -35.0))
        val a = Tracking.approach(victim, fixes, 5.0)!!
        assertEquals(Tracking.Trend.ARRIVED, a.trend)
        assertEquals(0, a.etaMin)
    }

    @Test
    fun singlePositionGivesDistanceButNoTrend() {
        val a = Tracking.approach(victim, listOf(at(0.0, 980.0)), 6.0)!!
        assertEquals(Tracking.Trend.UNKNOWN, a.trend)
        assertEquals(980.0, a.distanceM, 1.0)
        assertNull(a.earlierM)
        assertEquals(10, a.etaMin) // 980 m at 6 km/h (100 m/min)
        // Two positions only 30 s apart are still too close in time to tell.
        val b = Tracking.approach(victim, listOf(at(0.0, 1000.0), at(0.5, 900.0)), 6.0)!!
        assertEquals(Tracking.Trend.UNKNOWN, b.trend)
    }

    @Test
    fun movingAroundTheVictimAtTheSameDistanceIsSame() {
        // Walks along a circle of 1 km around the victim, 80 m per minute.
        val fixes = (0..8).map { m ->
            val angle = 80.0 * m / 1000.0
            at(m.toDouble(), 1000.0 * Math.cos(angle), 1000.0 * Math.sin(angle))
        }
        val a = Tracking.approach(victim, fixes, 5.0)!!
        assertEquals(Tracking.Trend.SAME, a.trend)
        assertEquals(0, a.stillMin)
    }

    @Test
    fun usesTheOldestPositionWhenThereIsNoFiveMinuteHistory() {
        val fixes = listOf(at(0.0, -1000.0), at(2.0, -790.0))
        val a = Tracking.approach(victim, fixes, 5.0)!!
        assertEquals(Tracking.Trend.CLOSER, a.trend)
        assertEquals(2, a.overMin)
        assertEquals(8, a.etaMin) // 790 m at the measured 105 m/min
    }

    @Test
    fun ignoresBadPositionsAndOrder() {
        val good = (0..6).map { at(it.toDouble(), -1200.0 + 100.0 * it) }
        val noisy = good.shuffled(java.util.Random(7)) + Fix(0.0, 0.0, t0 + 99 * 60_000) + Fix(Double.NaN, 78.0, t0 + 98 * 60_000)
        val a = Tracking.approach(victim, noisy, 5.0)!!
        assertEquals(Tracking.Trend.CLOSER, a.trend)
        assertEquals(600.0, a.distanceM, 5.0)
        assertNull(Tracking.approach(victim, emptyList(), 5.0))
        assertNull(Tracking.approach(victim, listOf(Fix(0.0, 0.0, t0)), 5.0))
    }

    @Test
    fun rescuerPositionMacSurvivesTheWireAndCatchesForgeries() {
        val teamKey = Crypto.random(32)
        val hb = HeartbeatPayload(
            role = Roles.RESCUER, battery = 64, neighbours = listOf("ab12"), lat = 11.6612345678, lon = 78.1498765432,
            team = "A", rescuerId = "r-123", name = "Ravi", version = "1.0", at = t0, locAt = t0 - 20_000,
        )
        val signed = PositionAuth.sign(teamKey, "dev1", hb)
        assertTrue(signed.mac.isNotEmpty())
        val wire = HeartbeatPayload.fromBytes(signed.toBytes())
        assertEquals(t0 - 20_000, wire.locAt)
        assertEquals(signed.mac, wire.mac)
        assertTrue(PositionAuth.verify(teamKey, "dev1", wire))
        // Changed position, battery, time, sender or key: rejected.
        assertFalse(PositionAuth.verify(teamKey, "dev1", wire.copy(lat = 11.70)))
        assertFalse(PositionAuth.verify(teamKey, "dev1", wire.copy(battery = 99)))
        assertFalse(PositionAuth.verify(teamKey, "dev1", wire.copy(locAt = t0)))
        assertFalse(PositionAuth.verify(teamKey, "dev2", wire))
        assertFalse(PositionAuth.verify(Crypto.random(32), "dev1", wire))
        // Missing or garbage MAC: rejected without throwing.
        assertFalse(PositionAuth.verify(teamKey, "dev1", wire.copy(mac = "")))
        assertFalse(PositionAuth.verify(teamKey, "dev1", wire.copy(mac = "not base64 !!")))
        assertFalse(PositionAuth.verify(teamKey, "dev1", wire.copy(mac = B64.encode(ByteArray(8)))))
        // Older phones send no MAC; their heartbeats still parse.
        val old = HeartbeatPayload.fromBytes(hb.toBytes())
        assertEquals("", old.mac)
        assertNotNull(old.lat)
    }

    @Test
    fun distanceUpdateForTheVictimMustBeSignedByARescuer() {
        val pinned = TestUtil.pinned
        val out = Outbox(Ids.newDeviceId(), pinned) { t0 }
        val victimId = Ids.newDeviceId()
        val karthik = Credentials.issue(TestUtil.basePrivate, "Karthik", "ALPHA", Crypto.random(32), t0, 5)
        val update = AckPayload("c1", AckStatus.ON_THE_WAY, "ALPHA", 7, "", t0, "Karthik", karthik.cert.toBase64(), distanceM = 620)
        val p = out.signedByRescuer(karthik, PacketType.PROGRESS, update.toBytes(), victimId)
        assertEquals(2, p.qos) // lower priority than SOS and replies, kept for an hour
        val relayed = PacketCodec.decode(PacketCodec.encode(p.relayCopy(4)))
        assertEquals(PacketType.PROGRESS, relayed.type)
        assertTrue(Authenticity.check(pinned, relayed, t0))
        assertEquals("Karthik", Authenticity.rescuerSigner(pinned, relayed, t0)?.name)
        assertEquals(620, AckPayload.fromBytes(relayed.payload).distanceM)
        // Signed with someone else's key, signed by nobody, or by a rescuer of another base: dropped by every relay.
        assertFalse(Authenticity.check(pinned, Crypto.signPacket(Crypto.newEcKeyPair().private, out.plain(PacketType.PROGRESS, update.toBytes(), victimId)), t0))
        assertFalse(Authenticity.check(pinned, out.plain(PacketType.PROGRESS, update.toBytes(), victimId), t0))
        val otherBase = BasePrivateKeys(Crypto.newEcKeyPair().private, Crypto.newEcKeyPair().private, Crypto.newEcKeyPair().private)
        val stranger = Credentials.issue(otherBase, "Stranger", "ALPHA", Crypto.random(32), t0, 5)
        val strangers = update.copy(by = "Stranger", cert = stranger.cert.toBase64())
        assertFalse(Authenticity.check(pinned, out.signedByRescuer(stranger, PacketType.PROGRESS, strangers.toBytes(), victimId), t0))
        // The base signs replies, but distance updates come only from rescuers.
        assertFalse(Authenticity.check(pinned, out.signedByBase(TestUtil.basePrivate, PacketType.PROGRESS, update.toBytes(), victimId), t0))
        // Replies without a distance (older phones) still parse.
        assertEquals(-1, AckPayload.fromBytes(AckPayload("c1", AckStatus.RECEIVED).toBytes()).distanceM)
    }

    @Test
    fun aRoughNetworkPositionDoesNotReplaceARecentGpsFix() {
        val gps = 8.0
        // A rough cell position (1.5 km) a few minutes after a GPS fix is ignored...
        assertFalse(FixChoice.isBetter(1500.0, t0 + 3 * 60_000, gps, t0))
        assertFalse(FixChoice.isBetter(1500.0, t0 + 15 * 60_000, gps, t0))
        // ...until the GPS fix is so old that it is no longer better (or older than 30 minutes).
        assertTrue(FixChoice.isBetter(1500.0, t0 + 17 * 60_000, gps, t0))
        assertTrue(FixChoice.isBetter(3000.0, t0 + 30 * 60_000, gps, t0))
        // A new GPS fix always replaces an older one, and a GPS fix replaces a rough position at once.
        assertTrue(FixChoice.isBetter(12.0, t0 + 30_000, gps, t0))
        assertTrue(FixChoice.isBetter(9.0, t0 + 1_000, 1500.0, t0))
        // Among rough positions the newer, slightly better one wins; a much older one never does.
        assertTrue(FixChoice.isBetter(1400.0, t0 + 1_000, 1500.0, t0))
        assertFalse(FixChoice.isBetter(5.0, t0 - 3 * 60_000, 1500.0, t0))
        // Out of order by a few seconds (two providers): only if more exact.
        assertTrue(FixChoice.isBetter(6.0, t0 - 2_000, gps, t0))
        assertFalse(FixChoice.isBetter(30.0, t0 - 2_000, gps, t0))
        // A position without accuracy counts as very rough.
        assertFalse(FixChoice.isBetter(null, t0 + 60_000, gps, t0))
        assertTrue(FixChoice.isBetter(50.0, t0 + 1_000, null, t0))
        assertTrue(FixChoice.isPrecise(8.0))
        assertFalse(FixChoice.isPrecise(1500.0))
        assertFalse(FixChoice.isUsable(null))
        assertTrue(FixChoice.isUsable(150.0))
    }

    @Test
    fun taskCarriesTheVictimsLocationAccuracy() {
        val t = TaskPayload("c1", "v1", 11.66, 78.14, "High", 70, "Flood", "", 3, "ALPHA", t0, 1500.0)
        assertEquals(1500.0, TaskPayload.fromBytes(t.toBytes()).accuracy!!, 0.0)
        assertNull(TaskPayload.fromBytes(t.copy(accuracy = null).toBytes()).accuracy)
    }

    @Test
    fun taskStatusCarriesFixTimeAndBattery() {
        val st = TaskStatusPayload("case1", "r-1", "Ravi", "A", TaskState.ON_THE_WAY, 11.66, 78.14, t0, t0 - 5_000, 71)
        val back = TaskStatusPayload.fromBytes(st.toBytes())
        assertEquals(t0 - 5_000, back.locAt)
        assertEquals(71, back.battery)
        assertEquals(st, back)
    }
}
