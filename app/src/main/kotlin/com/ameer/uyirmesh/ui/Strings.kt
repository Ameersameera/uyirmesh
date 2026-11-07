package com.ameer.uyirmesh.ui

import com.ameer.uyirmesh.Graph

/** Translations. Composables use [tr]; other code uses [L.t] with the language from the settings. */
object L {
    fun get(lang: String, key: String): String {
        val local = when (lang) {
            "ta" -> StringsTa.map[key] ?: StringsTaX.map[key]
            "hi" -> StringsHi.map[key]
            "te" -> StringsTe.map[key]
            "ml" -> StringsMl.map[key]
            "kn" -> StringsKn.map[key]
            else -> null
        }
        return local ?: StringsEn.map[key] ?: StringsEnX.map[key] ?: key
    }

    fun t(key: String): String {
        val lang = try {
            Graph.prefs.now.lang
        } catch (e: Exception) {
            "en"
        }
        return get(lang, key)
    }
}
