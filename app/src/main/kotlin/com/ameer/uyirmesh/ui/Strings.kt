package com.ameer.uyirmesh.ui

import com.ameer.uyirmesh.Graph

/** Translations. Composables use [tr]; other code uses [L.t] with the language from the settings. */
object L {
    fun get(lang: String, key: String): String =
        (if (lang == "ta") StringsTa.map[key] else null) ?: StringsEn.map[key] ?: key

    fun t(key: String): String {
        val lang = try {
            Graph.prefs.now.lang
        } catch (e: Exception) {
            "en"
        }
        return get(lang, key)
    }
}
