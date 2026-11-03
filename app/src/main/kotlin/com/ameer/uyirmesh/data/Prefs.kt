package com.ameer.uyirmesh.data

import android.content.Context
import com.ameer.uyirmesh.core.Ids
import com.ameer.uyirmesh.core.Outbox
import com.ameer.uyirmesh.core.Roles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User settings (not secret). Secrets live in [Vault]. */
data class Settings(
    val deviceId: String,
    val role: String = Roles.PUBLIC,
    val name: String = "",
    val phone: String = "",
    val lang: String = "en",
    val largeText: Boolean = false,
    /** 0 = follow phone, 1 = light, 2 = dark */
    val theme: Int = 0,
    val batterySaver: Boolean = false,
    val voice: Boolean = true,
    val shake: Boolean = true,
    val drill: Boolean = false,
    val ttl: Int = Outbox.DEFAULT_TTL,
    val onboarded: Boolean = false,
    val autoAssign: Boolean = true,
    val speedKmh: Int = 15,
    val webServer: Boolean = false,
    val familyPhones: String = "",
    val baseName: String = "Rescue Base",
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("uyirmesh_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings
    val now: Settings get() = _settings.value

    private fun load(): Settings {
        var id = sp.getString("deviceId", null)
        if (id == null || !Ids.isValidDeviceId(id)) {
            id = Ids.newDeviceId()
            sp.edit().putString("deviceId", id).apply()
        }
        return Settings(
            deviceId = id,
            role = sp.getString("role", Roles.PUBLIC) ?: Roles.PUBLIC,
            name = sp.getString("name", "") ?: "",
            phone = sp.getString("phone", "") ?: "",
            lang = sp.getString("lang", "en") ?: "en",
            largeText = sp.getBoolean("largeText", false),
            theme = sp.getInt("theme", 0),
            batterySaver = sp.getBoolean("batterySaver", false),
            voice = sp.getBoolean("voice", true),
            shake = sp.getBoolean("shake", true),
            drill = sp.getBoolean("drill", false),
            ttl = sp.getInt("ttl", Outbox.DEFAULT_TTL).coerceIn(1, 10),
            onboarded = sp.getBoolean("onboarded", false),
            autoAssign = sp.getBoolean("autoAssign", true),
            speedKmh = sp.getInt("speedKmh", 15).coerceIn(2, 80),
            webServer = sp.getBoolean("webServer", false),
            familyPhones = sp.getString("familyPhones", "") ?: "",
            baseName = sp.getString("baseName", "Rescue Base") ?: "Rescue Base",
        )
    }

    @Synchronized
    fun update(change: (Settings) -> Settings) {
        val old = _settings.value
        val s = change(old).copy(deviceId = old.deviceId)
        sp.edit()
            .putString("role", s.role)
            .putString("name", s.name.take(40))
            .putString("phone", s.phone.take(20))
            .putString("lang", s.lang)
            .putBoolean("largeText", s.largeText)
            .putInt("theme", s.theme)
            .putBoolean("batterySaver", s.batterySaver)
            .putBoolean("voice", s.voice)
            .putBoolean("shake", s.shake)
            .putBoolean("drill", s.drill)
            .putInt("ttl", s.ttl.coerceIn(1, 10))
            .putBoolean("onboarded", s.onboarded)
            .putBoolean("autoAssign", s.autoAssign)
            .putInt("speedKmh", s.speedKmh.coerceIn(2, 80))
            .putBoolean("webServer", s.webServer)
            .putString("familyPhones", s.familyPhones.take(200))
            .putString("baseName", s.baseName.take(40))
            .apply()
        _settings.value = s
    }

    /** Small non-secret values (timestamps, counters). */
    fun getLong(key: String, def: Long = 0L): Long = sp.getLong("x_$key", def)
    fun putLong(key: String, value: Long) = sp.edit().putLong("x_$key", value).apply()
    fun getString(key: String): String? = sp.getString("x_$key", null)
    fun putString(key: String, value: String?) = sp.edit().putString("x_$key", value).apply()
}
