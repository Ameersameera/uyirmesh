package com.ameer.uyirmesh.core

/**
 * Blood groups for the blood donor finder. The base asks for a group; phones whose owner chose to be a donor and
 * whose group can give to it show "I can donate". Red-cell compatibility: O- gives to everyone, AB+ takes from
 * everyone, and the Rh "-" groups can give to "+" of the same letters but not the other way round.
 */
object BloodGroups {
    val ALL = listOf("O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-")
    const val ANY = "ANY"

    /** "o positive", "O +ve", "ab-", "B POS" -> "O+", "O+", "AB-", "B+"; "" when it cannot be read. */
    fun normalize(text: String): String {
        val t = text.uppercase().replace(" ", "").replace("POSITIVE", "+").replace("NEGATIVE", "-")
            .replace("POS", "+").replace("NEG", "-").replace("VE", "")
        val letters = when {
            t.startsWith("AB") -> "AB"
            t.startsWith("A") -> "A"
            t.startsWith("B") -> "B"
            t.startsWith("O") || t.startsWith("0") -> "O"
            else -> return ""
        }
        val rest = t.removePrefix(letters).removePrefix("0")
        val rh = when {
            rest.startsWith("+") -> "+"
            rest.startsWith("-") -> "-"
            else -> return ""
        }
        return letters + rh
    }

    /** True when a person of group [donor] can give red cells to a person of group [needed] ("ANY" = any group). */
    fun canDonate(donor: String, needed: String): Boolean {
        val d = normalize(donor)
        if (d.isEmpty()) return false
        if (needed.trim().uppercase() == ANY) return true
        val r = normalize(needed)
        if (r.isEmpty()) return false
        val dRh = d.last()
        val rRh = r.last()
        if (dRh == '+' && rRh == '-') return false
        val dAbo = d.dropLast(1)
        val rAbo = r.dropLast(1)
        return when (dAbo) {
            "O" -> true
            "A" -> rAbo == "A" || rAbo == "AB"
            "B" -> rAbo == "B" || rAbo == "AB"
            else -> rAbo == "AB"
        }
    }
}

/** Things to have ready before a flood or cyclone (preparedness checklist); the labels are in the app's strings. */
object Preparedness {
    val ITEMS = listOf(
        "water", "food", "medicines", "first_aid", "documents", "power_bank", "torch", "whistle", "radio", "cash",
        "phone_numbers", "clothes", "special_needs", "meeting_point", "app_ready", "practised",
    )
}
