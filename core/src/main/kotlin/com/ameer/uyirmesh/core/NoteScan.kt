package com.ameer.uyirmesh.core

/**
 * Reads the free-text note of an SOS for words that mean danger to life ("not breathing", "bleeding",
 * "நெஞ்சு வலி") and adds a few points to the urgency score, with a readable reason.
 *
 * This is a plain keyword rule, not machine learning: the trained model only sees the form fields, so without
 * this a note like "father not breathing" would add nothing. The points are capped ([CAP]) so a note can
 * raise a case but never outweigh the form. Simple negation is handled ("no bleeding", "ரத்தம் இல்லை").
 * Words are listed in English, Tamil, Tamil written in English letters and a few Hindi ones.
 */
object NoteScan {
    data class Hit(val label: String, val points: Int)

    /** Most points a note can add. */
    const val CAP = 15

    private class Rule(val label: String, val points: Int, val words: List<String>)

    private val RULES = listOf(
        Rule(
            "not breathing", 10,
            listOf(
                "not breathing", "can't breathe", "cant breathe", "cannot breathe", "unable to breathe",
                "difficulty breathing", "trouble breathing", "breathing problem", "breathless", "choking",
                "மூச்சு விட முடிய", "மூச்சுத் திணறல்", "மூச்சு திணறல்", "moochu vida mudiyala", "saans nahi", "saans lene",
            ),
        ),
        Rule(
            "unconscious", 10,
            listOf(
                "unconscious", "not waking", "not responding", "unresponsive", "fainted", "passed out",
                "மயக்க", "mayakkam", "mayangi", "behosh",
            ),
        ),
        Rule("bleeding", 10, listOf("bleeding", "lot of blood", "losing blood", "blood loss", "ரத்தம்", "இரத்தம்", "ratham", "khoon")),
        Rule(
            "chest pain or heart attack", 10,
            listOf("chest pain", "heart attack", "heart problem", "heart patient", "cardiac", "நெஞ்சு வலி", "நெஞ்சுவலி", "nenju vali"),
        ),
        Rule("drowning", 10, listOf("drowning", "drowned", "swept away", "washed away", "மூழ்க", "moozhgi")),
        Rule("snake bite", 10, listOf("snake bite", "snakebite", "bitten by a snake", "snake bit", "பாம்பு", "pambu", "saanp")),
        Rule("electric shock", 10, listOf("electric shock", "electrocuted", "current shock", "மின்சாரம் தாக்", "shock adichu")),
        Rule(
            "labour pain", 10,
            listOf("labour pain", "labor pain", "in labour", "in labor", "water broke", "delivery pain", "giving birth", "பிரசவ", "prasavam", "prasava vali"),
        ),
        Rule("seizure or stroke", 10, listOf("seizure", "convulsion", "stroke", "paralysed", "paralyzed", "வலிப்பு", "valippu")),
        Rule("poisoning", 10, listOf("poison", "pesticide", "விஷம்", "visham")),
        Rule(
            "broken bone or head injury", 6,
            listOf("fracture", "broken leg", "broken arm", "broken bone", "bone broken", "head injury", "hit his head", "hit her head", "எலும்பு முறி", "elumbu"),
        ),
        Rule("burns", 6, listOf("burn", "தீக்காயம்")),
        Rule(
            "needs daily medicine", 6,
            listOf("insulin", "diabetic", "dialysis", "oxygen cylinder", "on oxygen", "asthma", "inhaler", "இன்சுலின்", "சர்க்கரை நோய்"),
        ),
        Rule(
            "cannot walk", 6,
            listOf("bedridden", "bed ridden", "cannot walk", "can't walk", "cant walk", "wheelchair", "நடக்க முடியா", "nadakka mudiyala"),
        ),
        Rule("newborn baby", 6, listOf("newborn", "new born", "infant", "small baby", "month old", "months old", "கைக்குழந்தை", "பச்சிளம்")),
        Rule(
            "water rising", 6,
            listOf(
                "water rising", "water is rising", "rising water", "water level rising", "water entering", "on the roof", "on roof",
                "terrace", "தண்ணீர் ஏறு", "thanni eruthu", "thanni yeruthu",
            ),
        ),
        Rule(
            "building collapse", 6,
            listOf(
                "house collapsed", "building collapsed", "wall collapsed", "roof collapsed", "collapsed house", "collapsed building",
                "wall fell", "roof fell", "under debris", "trapped under", "stuck under", "இடிந்து", "idinju",
            ),
        ),
        Rule("smoke or gas", 6, listOf("fire spreading", "smoke", "gas leak", "புகை", "தீ பரவ")),
        Rule(
            "fever or vomiting", 3,
            listOf("fever", "vomiting", "diarrhea", "diarrhoea", "dehydrat", "shivering", "hypothermia", "காய்ச்சல்", "வாந்தி", "kaichal", "vaanthi"),
        ),
        Rule("alone", 3, listOf("alone", "children alone", "kids alone", "தனியாக", "thaniya")),
    )

    /** Words just before a match that cancel it ("no bleeding", "not unconscious"). */
    private val NEG_BEFORE = setOf("no", "not", "without", "never", "nahi", "nahin")

    /** Words just after a match that cancel it (Tamil and Hindi put the "no" after the word). */
    private val NEG_AFTER = setOf("illa", "illai", "ille", "இல்லை", "இல்ல", "nahi", "nahin")

    /** Danger words found in [note], most serious first. Each rule counts once. */
    fun scan(note: String): List<Hit> {
        if (note.isBlank()) return emptyList()
        val text = note.lowercase().replace('’', '\'').replace('‘', '\'')
        val hits = ArrayList<Hit>()
        for (r in RULES) {
            if (r.words.any { w -> found(text, w) }) hits.add(Hit(r.label, r.points))
        }
        return hits.sortedByDescending { it.points }
    }

    /** Points the note adds (at most [CAP]) with one reason per hit. */
    fun reasons(note: String): List<Reason> {
        val out = ArrayList<Reason>()
        var total = 0
        for (h in scan(note)) {
            val add = minOf(h.points, CAP - total)
            if (add <= 0) break
            total += add
            out.add(Reason("Note: ${h.label}", add))
        }
        return out
    }

    private fun found(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            from = i + 1
            // Latin words must start at a word boundary ("stroke" is not in "heatstroke").
            if (word[0].isLatinLetter() && i > 0 && text[i - 1].isLetter()) continue
            if (negated(text, i, i + word.length)) continue
            return true
        }
    }

    private fun negated(text: String, start: Int, end: Int): Boolean {
        val before = text.substring(0, start).split(SPLIT).filter { it.isNotEmpty() }.takeLast(2)
        if (before.any { it in NEG_BEFORE }) return true
        // Skip the rest of the matched word ("ரத்தம்" + "இல்லை", "bleeding" + "illa").
        var j = end
        while (j < text.length && !SPLIT.matches(text[j].toString())) j++
        val after = text.substring(j).split(SPLIT).firstOrNull { it.isNotEmpty() } ?: return false
        return after in NEG_AFTER
    }

    private val SPLIT = Regex("[\\s,.;:!?()\"/]+")

    private fun Char.isLatinLetter() = this in 'a'..'z'
}
