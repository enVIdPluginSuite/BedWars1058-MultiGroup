package dev.etran.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuplicateDetectorTest {

    private fun team(x: Int) = linkedMapOf<String, Any?>("Spawn" to "$x.5,65.0,0.5,90.0,0.0", "Bed" to "${x + 3}.5,65.0,0.5,0.0,0.0")

    private fun tree(group: String, vararg xs: Int, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val teams = linkedMapOf<String, Any?>()
        xs.forEachIndexed { i, x -> teams["T$i"] = team(x) }
        return linkedMapOf<String, Any?>("group" to group, "maxInTeam" to 1, "Team" to teams) + extra
    }

    private val d = DuplicateDetector()
    private fun sig(name: String, t: Map<String, Any?>) = d.signature(name, t)

    @Test
    fun `copies of a map are found whatever they are called, ignoring yaw and decimals`() {
        val a = sig("lighthouse", tree("Solo", 10, -10, 30))
        val b = sig("lighthouse_doubles", tree("Doubles", 10, -10, 30, extra = mapOf("maxInTeam" to 2)))
        assertEquals(1.0, d.similarity(a, b))
        val cs = d.detect(listOf(a, b, sig("castle", tree("Solo", 100, 120, 140))))
        assertEquals(1, cs.size)
        assertEquals("lighthouse", cs[0].base)
        assertEquals(listOf("lighthouse_doubles"), cs[0].members.map { it.name })
        assertTrue(d.isClear(cs[0], cs[0].members[0]))
    }

    @Test
    fun `same group is not clear and another layout is only for review`() {
        val base = sig("lighthouse", tree("Solo", 10, -10, 30, 50))
        val sameGroup = sig("lighthouse2", tree("Solo", 10, -10, 30, 50))
        val fewerTeams = sig("lighthouse-squads", tree("Squads", 10, -10, 30))
        val cs = d.detect(listOf(base, sameGroup, fewerTeams))
        assertEquals(1, cs.size)
        val byName = cs[0].members.associateBy { it.name }
        assertFalse(d.isClear(cs[0], byName.getValue("lighthouse2")))
        // 6 of 8 locations shared = 0.75: below the automatic threshold
        assertFalse(d.isClear(cs[0], byName.getValue("lighthouse-squads")))
    }

    @Test
    fun `no recognisable original gives a cluster without base`() {
        val cs = d.detect(listOf(sig("alpha", tree("Solo", 10, -10, 30)), sig("beta", tree("Doubles", 10, -10, 30))))
        assertEquals(1, cs.size)
        assertNull(cs[0].base)
        assertTrue(cs[0].note!!.contains("merge by hand"))
    }

    @Test
    fun `an arena that already has a map file is the original`() {
        val a = sig("lighthouse", tree("Solo", 10, -10, 30))
        val b = sig("lighthouse-old", tree("Doubles", 10, -10, 30))
        val cs = d.detect(listOf(a, b), withMapFile = setOf("lighthouse"))
        assertEquals("lighthouse", cs[0].base)
    }

    @Test
    fun `tiny or generated arenas are ignored`() {
        val small = sig("a", mapOf("group" to "Solo", "Team" to mapOf("R" to team(1))))
        val small2 = sig("a2", mapOf("group" to "Doubles", "Team" to mapOf("R" to team(1))))
        assertTrue(d.detect(listOf(small, small2)).isEmpty())
        val marked = d.signature("x", tree("Solo", 1, 2, 3) + mapOf(VariantPlanner.MARKER_SECTION to mapOf("p" to "9,9,9")))
        assertFalse("9,9,9" in marked.locations)
    }
}
