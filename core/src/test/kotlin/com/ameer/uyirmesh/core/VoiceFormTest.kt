package com.ameer.uyirmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFormTest {

    @Test
    fun readsPeopleInjuryAndNeeds() {
        val r = VoiceForm.parse("Help help, my father is injured, we are four people, two children, we need a boat and drinking water")
        assertEquals(4, r.people)
        assertEquals(2, r.children)
        assertEquals(true, r.injured)
        assertTrue(Needs.BOAT in r.needs)
        assertTrue(Needs.WATER in r.needs)
        assertTrue(r.filled >= 5)
    }

    @Test
    fun picksTheEmergencyKind() {
        assertEquals(EmergencyKind.FIRE, VoiceForm.parse("there is fire and smoke in the building").kind)
        assertEquals(EmergencyKind.FLOOD, VoiceForm.parse("water is rising, we are on the roof").kind)
        assertEquals(EmergencyKind.TRAPPED, VoiceForm.parse("the wall collapsed and we are trapped").kind)
        assertEquals(EmergencyKind.MEDICAL, VoiceForm.parse("my mother had a heart attack, she is unconscious").kind)
        assertEquals(EmergencyKind.NEED, VoiceForm.parse("we are hungry, no food since yesterday").kind)
        assertNull(VoiceForm.parse("hello can anyone hear me").kind)
    }

    @Test
    fun numbersInWordsAndDigits() {
        assertEquals(12, VoiceForm.parse("12 people are here").people)
        assertEquals(5, VoiceForm.parse("we are five").people)
        assertNull(VoiceForm.parse("we are a family").people)
        assertEquals(1, VoiceForm.parse("there is a baby with us").children)
        assertEquals(1, VoiceForm.parse("my grandmother cannot walk").elderly)
        assertEquals(1, VoiceForm.parse("my grandmother cannot walk").disabled)
        assertEquals(3, VoiceForm.parse("three cows and a dog are stuck").animals)
    }

    @Test
    fun waterRisingIsNotANeedForWater() {
        val r = VoiceForm.parse("water rising fast")
        assertTrue(Needs.WATER !in r.needs)
        assertTrue(Needs.WATER in VoiceForm.parse("we have no water").needs)
        assertTrue(Needs.MEDICINE in VoiceForm.parse("we need insulin tablets").needs)
        assertTrue(Needs.BABY_FOOD in VoiceForm.parse("need milk powder for the baby").needs)
    }

    @Test
    fun avoidsCommonFalseHits() {
        assertNull(VoiceForm.parse("the power is cut and the bridge is broken").injured)
        assertNull(VoiceForm.parse("food delivery is late").pregnant)
        assertEquals(0, VoiceForm.parse("").filled)
    }

    @Test
    fun sosPayloadKeepsNewFields() {
        val p = SosPayload(
            caseId = "c1", kind = EmergencyKind.FLOOD, people = 3, silent = true, forOther = true,
            otherName = "Ravi", otherPhone = "98", animals = 4, trigger = Triggers.FALL,
        )
        val back = SosPayload.fromBytes(p.toBytes())
        assertEquals(true, back.silent)
        assertEquals(true, back.forOther)
        assertEquals("Ravi", back.otherName)
        assertEquals("98", back.otherPhone)
        assertEquals(4, back.animals)
        assertEquals(Triggers.FALL, back.trigger)
        // Older messages without the new fields still read.
        val old = SosPayload.fromJson(mapOf("c" to "x", "k" to "TRAPPED", "p" to 2))
        assertEquals(false, old.silent)
        assertEquals(0, old.animals)
    }
}
