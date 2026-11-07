package com.ameer.uyirmesh.mesh

import android.content.Context
import android.os.BatteryManager
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.BeaconPayload
import com.ameer.uyirmesh.core.Geo
import com.ameer.uyirmesh.core.MediaLevel
import com.ameer.uyirmesh.core.MediaQuality
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PowerPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Last time this phone heard from the rescue base, and how many hops away it was. */
data class BaseContact(
    val at: Long,
    val hops: Int,
    val name: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val openCases: Int = -1,
)

/** Battery state and what the phone does about it. */
data class PowerState(
    val battery: Int = -1,
    val charging: Boolean = false,
    /** Highest QoS class still relayed for others (3 = all). */
    val relayMaxQos: Int = 3,
    val lowRadio: Boolean = false,
)

/**
 * How well this phone is connected: base contact (from base-signed messages and the base beacon), battery-aware
 * relaying, and the media quality that suits the link right now.
 */
class MeshHealth(private val ctx: Context) {
    private val _base = MutableStateFlow(loadBase())
    val base: StateFlow<BaseContact?> = _base
    private val _power = MutableStateFlow(PowerState())
    val power: StateFlow<PowerState> = _power

    /** Any accepted message that only the base can send (signature already checked by the relay engine). */
    fun onBaseHeard(p: Packet) {
        val old = _base.value
        val now = System.currentTimeMillis()
        // A copy that went round a longer way a moment later should not make the base look further away.
        val hops = if (old != null && now - old.at < 60_000 && old.hops in 0 until p.hops) old.hops else p.hops
        set(BaseContact(now, hops, old?.name ?: "", old?.lat, old?.lon, old?.openCases ?: -1))
    }

    fun onBeacon(p: Packet, b: BeaconPayload) {
        val now = System.currentTimeMillis()
        set(BaseContact(now, p.hops, b.baseName, b.lat, b.lon, b.openCases))
    }

    private fun set(c: BaseContact) {
        _base.value = c
        val last = Graph.prefs.getLong("base_saved_at")
        if (c.at - last > 60_000) {
            Graph.prefs.putString("base_contact", listOf(c.at, c.hops, c.name.replace('|', ' '), c.lat ?: "", c.lon ?: "", c.openCases).joinToString("|"))
            Graph.prefs.putLong("base_saved_at", c.at)
        }
    }

    private fun loadBase(): BaseContact? = try {
        Graph.prefs.getString("base_contact")?.split('|')?.takeIf { it.size >= 6 }?.let {
            BaseContact(it[0].toLong(), it[1].toInt(), it[2], it[3].toDoubleOrNull(), it[4].toDoubleOrNull(), it[5].toInt())
        }
    } catch (e: Exception) {
        null
    }

    /** Distance from this phone to the base in metres, if both positions are known. */
    fun distanceToBase(): Double? {
        val b = _base.value ?: return null
        val me = Graph.loc.lastLatLon ?: return null
        if (b.lat == null || b.lon == null) return null
        return Geo.distanceM(me.lat, me.lon, b.lat, b.lon)
    }

    /** Re-reads the battery and decides how much this phone relays. Returns true when the decision changed. */
    fun refreshPower(): Boolean {
        val s = Graph.prefs.now
        val battery = Mesh.batteryPercent(ctx)
        val charging = isCharging()
        val n = PowerState(
            battery, charging,
            PowerPolicy.relayMaxQos(battery, charging, s.relayMode, s.autoPowerSave),
            PowerPolicy.lowPowerRadio(battery, charging, s.relayMode, s.autoPowerSave),
        )
        val old = _power.value
        _power.value = n
        return old.relayMaxQos != n.relayMaxQos || old.lowRadio != n.lowRadio
    }

    private fun isCharging(): Boolean = try {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
    } catch (e: Exception) {
        false
    }

    /** Photo size and voice bitrate for the link this phone has now. */
    fun mediaLevel(): MediaLevel {
        val stats = Graph.mesh.stats.value
        val b = _base.value
        val hops = if (b != null && System.currentTimeMillis() - b.at < 30 * 60_000) b.hops else -1
        val battery = _power.value.battery.takeIf { it >= 0 } ?: Mesh.batteryPercent(ctx)
        return MediaQuality.pick(stats.peers, hops, battery, stats.waitingOwn)
    }
}
