package dev.etran.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigMatcherTest {

    private val m = ConfigMatcher()

    private fun pick(group: String, vararg configs: String, explicit: String? = null): String? =
        m.match(group, configs.map { ConfigCandidate(it) }, explicit).config

    @Test
    fun `exact name wins regardless of case and punctuation`() {
        assertEquals("solo-rush", pick("SoloRush", "solo", "rush", "solo-rush"))
        assertEquals("Doubles", pick("doubles", "solo", "Doubles"))
    }

    @Test
    fun `closest configuration for a combined group`() {
        assertEquals("solo", pick("SoloRush", "solo", "doubles"))
        assertEquals("doubles", pick("DoublesRush", "solo", "doubles"))
        assertEquals("doubles", pick("Doubles_OneBlock", "solo", "doubles"))
    }

    @Test
    fun `synonyms and team size notation`() {
        assertEquals("doubles", pick("Duos", "solo", "doubles"))
        assertEquals("doubles", pick("2v2v2v2", "solo", "doubles", "squads"))
        assertEquals("squads", pick("4v4v4v4", "solo", "doubles", "squads"))
        assertEquals("solo", pick("1v1v1v1v1v1v1v1", "solo", "doubles"))
        assertEquals("trio", pick("Threes", "trio", "solo"))
    }

    @Test
    fun `the word 'one' is not solo`() {
        // "Doubles_OneBlock" must not drift to "solo" because of "one"
        assertEquals("doubles", pick("Doubles_OneBlock", "solo", "doubles"))
    }

    @Test
    fun `more specific configuration beats a general one`() {
        assertEquals("solo-rush", pick("SoloRush", "solo", "solo-rush"))
        assertEquals("doubles-lucky", pick("LuckyDoubles", "doubles", "doubles-lucky"))
    }

    @Test
    fun `tie prefers the word that comes first in the group`() {
        val r = m.match("SoloRush", listOf(ConfigCandidate("rush"), ConfigCandidate("solo")))
        assertEquals("solo", r.config)
        assertTrue(r.ambiguous)
        assertEquals("rush", r.runnerUp)
    }

    @Test
    fun `nothing close enough falls back to base`() {
        assertNull(pick("Squads", "solo", "doubles"))
        assertNull(pick("Solo"))
    }

    @Test
    fun `typos are tolerated`() {
        assertEquals("doubles", pick("Doubels", "solo", "doubles"))
    }

    @Test
    fun `aliases count as names`() {
        val r = m.match("Lucky", listOf(ConfigCandidate("lb", listOf("lucky", "luckyblock")), ConfigCandidate("solo")))
        assertEquals("lb", r.config)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `explicit choice wins and base can be forced`() {
        assertEquals("doubles", pick("SoloRush", "solo", "doubles", explicit = "doubles"))
        assertNull(pick("SoloRush", "solo", "doubles", explicit = "base"))
        // an explicit name that does not exist falls back to matching
        assertEquals("solo", pick("SoloRush", "solo", "doubles", explicit = "nope"))
    }

    @Test
    fun `word splitting`() {
        assertEquals(listOf("solo", "rush"), m.words("SoloRush"))
        assertEquals(listOf("doubles", "one", "block"), m.words("Doubles_OneBlock"))
        assertEquals(listOf("doubles"), m.words("DUOS"))
        assertEquals(listOf("pvp", "map"), m.words("PVPMap"))
    }

    @Test
    fun `custom synonyms and threshold`() {
        val custom = ConfigMatcher(mapOf("lucky" to listOf("lb", "luckyblock")), minScore = 600)
        assertEquals("lucky", custom.match("LB_Solo", listOf(ConfigCandidate("lucky"), ConfigCandidate("other"))).config)
        // a partial match scoring below 600 is rejected by the higher threshold
        assertNull(custom.match("SoloRushX", listOf(ConfigCandidate("solo-rush-y-z"))).config)
    }
}
