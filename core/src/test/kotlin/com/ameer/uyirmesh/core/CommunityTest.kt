package com.ameer.uyirmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityTest {
    @Test
    fun bloodGroupsAreReadInTheWaysPeopleWriteThem() {
        assertEquals("O+", BloodGroups.normalize("o positive"))
        assertEquals("O+", BloodGroups.normalize("O +ve"))
        assertEquals("AB-", BloodGroups.normalize("ab-"))
        assertEquals("B+", BloodGroups.normalize("B POS"))
        assertEquals("A-", BloodGroups.normalize("A Negative"))
        assertEquals("O-", BloodGroups.normalize("0-"))
        assertEquals("", BloodGroups.normalize("don't know"))
        assertEquals("", BloodGroups.normalize("B"))
    }

    @Test
    fun donorCompatibility() {
        for (g in BloodGroups.ALL) {
            assertTrue("O- gives to $g", BloodGroups.canDonate("O-", g))
            assertTrue("$g gives to AB+", BloodGroups.canDonate(g, "AB+"))
            assertTrue("$g gives to itself", BloodGroups.canDonate(g, g))
            assertTrue(BloodGroups.canDonate(g, BloodGroups.ANY))
        }
        assertFalse(BloodGroups.canDonate("O+", "O-"))
        assertFalse(BloodGroups.canDonate("A+", "B+"))
        assertFalse(BloodGroups.canDonate("AB-", "A-"))
        assertTrue(BloodGroups.canDonate("A-", "AB-"))
        assertTrue(BloodGroups.canDonate("B-", "B+"))
        assertFalse("unknown donor group", BloodGroups.canDonate("", "O+"))
        // Count of compatible pairs in the standard red-cell table: 27 of 64.
        assertEquals(27, BloodGroups.ALL.sumOf { d -> BloodGroups.ALL.count { r -> BloodGroups.canDonate(d, r) } })
        assertEquals(16, Preparedness.ITEMS.distinct().size)
    }
}
