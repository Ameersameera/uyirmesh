package com.ameer.uyirmesh.core

/**
 * Fills the SOS form from what the person said ("help, my father is injured, we are four people, we need a boat").
 * The speech itself is recognised offline on the phone (Vosk, English); this part only reads the words.
 * Every field is null when the words say nothing about it, so the form keeps what the person already chose.
 */
data class VoiceFormResult(
    val kind: String? = null,
    val people: Int? = null,
    val children: Int? = null,
    val elderly: Int? = null,
    val disabled: Int? = null,
    val pregnant: Int? = null,
    val injured: Boolean? = null,
    val needs: Set<String> = emptySet(),
    val animals: Int? = null,
) {
    /** Number of form fields the speech filled in. */
    val filled: Int
        get() = listOf(kind, people, children, elderly, disabled, pregnant, injured, animals).count { it != null } + needs.size
}

object VoiceForm {
    private val NUMBERS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "single" to 1, "two" to 2, "couple" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "hundred" to 100,
    )

    private const val NUM_WORDS = "\\d{1,3}|one|single|two|couple|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
        "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|hundred"

    /** A count before a noun ("a baby", "two children"). */
    private const val NUM = "(a|an|$NUM_WORDS)"

    /** A count on its own ("we are five"): "a"/"an" would read "we are a family" as one person. */
    private const val NUM_ALONE = "($NUM_WORDS)"

    private val KIND_WORDS: List<Pair<String, List<String>>> = listOf(
        EmergencyKind.FIRE to listOf("fire", "burning", "smoke", "flames", "gas leak", "explosion"),
        EmergencyKind.MEDICAL to listOf(
            "heart attack", "unconscious", "not breathing", "can't breathe", "cannot breathe", "fainted", "fever",
            "snake bite", "snakebite", "labour pain", "labor pain", "in labour", "sick", "medical", "seizure", "fits", "stroke",
            "bleeding", "chest pain", "diabetic",
        ),
        EmergencyKind.TRAPPED to listOf(
            "trapped", "stuck", "collapsed", "can't get out", "cannot get out", "buried", "locked in", "landslide",
            "under the rubble", "rubble",
        ),
        EmergencyKind.FLOOD to listOf("flood", "water rising", "water is rising", "drowning", "water entered", "water inside", "submerged", "rain water"),
        EmergencyKind.NEED to listOf("hungry", "no food", "nothing to eat", "no water", "thirsty", "supplies", "ration"),
    )

    private val INJURY = listOf(
        "injured", "injury", "hurt", "bleeding", "broken leg", "broken arm", "broken bone", "broken hand", "fracture",
        "wound", "unconscious", "burnt", "burned", "burns", "head injury", "not breathing",
    )

    fun parse(spoken: String): VoiceFormResult {
        val t = " " + spoken.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (t.isBlank()) return VoiceFormResult()
        val kind = kindOf(t)
        val people = maxCount(t, listOf("people", "persons", "person", "members", "of us", "family members", "adults"))
            ?: Regex(" we are $NUM_ALONE ").find(t)?.let { num(it.groupValues[1]) }
            ?: Regex(" there are $NUM_ALONE of us ").find(t)?.let { num(it.groupValues[1]) }
        var children = maxCount(t, listOf("children", "child", "kids", "kid", "babies", "baby", "infants", "infant", "small children"))
        if (children == null && hasAny(t, listOf("my son", "my daughter", "a baby", "small baby", "my baby", "a child"))) children = 1
        var elderly = maxCount(t, listOf("elderly", "old people", "old persons", "old person", "senior citizens", "seniors", "grandparents"))
        if (elderly == null && hasAny(t, listOf("grandmother", "grandfather", "grandma", "grandpa", "old man", "old woman", "old lady", "elderly", "aged"))) elderly = 1
        var disabled = maxCount(t, listOf("disabled", "handicapped", "wheelchair users"))
        if (disabled == null && hasAny(t, listOf("disabled", "wheelchair", "handicapped", "cannot walk", "can't walk", "blind", "deaf", "paralysed", "paralyzed", "bedridden"))) disabled = 1
        var pregnant = maxCount(t, listOf("pregnant women", "pregnant ladies", "pregnant"))
        if (pregnant == null && hasAny(t, listOf("pregnant", "labour pain", "labor pain", "in labour"))) pregnant = 1
        val injured = if (hasAny(t, INJURY)) true else null
        val needs = LinkedHashSet<String>()
        if (Regex(" (need|needs|no|drinking|want|thirsty|give us|send) (some )?(drinking )?water ").containsMatchIn(t) ||
            hasAny(t, listOf("thirsty", "water to drink", "drinking water"))
        ) needs.add(Needs.WATER)
        if (hasAny(t, listOf(" food", "hungry", "nothing to eat", "ration"))) needs.add(Needs.FOOD)
        if (hasAny(t, listOf("medicine", "medicines", "tablet", "tablets", "insulin", "drugs", "inhaler"))) needs.add(Needs.MEDICINE)
        if (hasAny(t, listOf("baby food", "milk for", "milk powder", "baby milk", "formula"))) needs.add(Needs.BABY_FOOD)
        if (hasAny(t, listOf("boat", "boats"))) needs.add(Needs.BOAT)
        val animals = maxCount(t, listOf("cows", "cow", "goats", "goat", "dogs", "dog", "animals", "animal", "cattle", "buffaloes", "buffalo", "hens", "chickens", "sheep", "pets", "pet"))
            ?: if (hasAny(t, listOf(" cow ", " cows ", " goat", " cattle", " buffalo", " dog ", " dogs ", " animals ", " pets "))) 1 else null
        return VoiceFormResult(kind, people?.coerceIn(1, 200), children?.coerceIn(0, 99), elderly?.coerceIn(0, 99),
            disabled?.coerceIn(0, 99), pregnant?.coerceIn(0, 99), injured, needs, animals?.coerceIn(0, 999))
    }

    private fun kindOf(t: String): String? {
        var best: String? = null
        var bestCount = 0
        for ((kind, words) in KIND_WORDS) {
            val n = words.count { t.contains(" $it") }
            if (n > bestCount) {
                best = kind
                bestCount = n
            }
        }
        return best
    }

    /** Largest "<number> <noun>" found for any of [nouns], e.g. "three children" → 3. */
    private fun maxCount(t: String, nouns: List<String>): Int? {
        var best: Int? = null
        for (noun in nouns) {
            for (m in Regex(" $NUM (?:small |little |old |young |more )?${Regex.escape(noun)} ").findAll(t)) {
                val n = num(m.groupValues[1]) ?: continue
                if (best == null || n > best) best = n
            }
        }
        return best
    }

    private fun hasAny(t: String, words: List<String>): Boolean = words.any { w -> t.contains(if (w.startsWith(" ")) w else " $w") }

    private fun num(w: String): Int? = w.toIntOrNull() ?: NUMBERS[w]
}
