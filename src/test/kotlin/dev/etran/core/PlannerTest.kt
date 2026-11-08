package dev.etran.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerTest {

    private fun team(color: String, x: Int) = linkedMapOf<String, Any?>(
        "Color" to color, "Spawn" to "$x,64,0,0,0", "Bed" to "$x,64,5,0,0",
    )

    private val baseTree: Map<String, Any?> = linkedMapOf(
        "group" to "Solo",
        "display-name" to "",
        "maxInTeam" to 1,
        "minPlayers" to 2,
        "Team" to linkedMapOf<String, Any?>("Red" to team("RED", 10), "Blue" to team("BLUE", -10), "Green" to team("GREEN", 30)),
        "generator" to linkedMapOf<String, Any?>("Diamond" to listOf("1,1,1"), "Emerald" to listOf("2,2,2")),
        "waiting" to linkedMapOf<String, Any?>("Loc" to "0,100,0,0,0"),
    )

    private val base = BaseArena("lighthouse", "Solo", baseTree)

    private fun planner(s: PlanSettings = PlanSettings()) = VariantPlanner(ConfigMatcher(), s)

    private fun def(yaml: Map<String, Any?>, warnings: MutableList<String> = ArrayList()) =
        MapDefinition.parse("lighthouse", yaml, warnings)

    @Test
    fun `merge replaces Team as a whole and merges the rest`() {
        val override = linkedMapOf<String, Any?>(
            "maxInTeam" to 2,
            "Team" to linkedMapOf<String, Any?>("Red" to team("RED", 11), "Blue" to team("BLUE", -11)),
            "generator" to linkedMapOf<String, Any?>("Emerald" to listOf("9,9,9")),
        )
        val out = TreeMerge.merge(baseTree, override, { it == "Team" })
        assertEquals(2, out["maxInTeam"])
        assertEquals(setOf("Red", "Blue"), (out["Team"] as Map<*, *>).keys)
        val gen = out["generator"] as Map<*, *>
        assertEquals(listOf("1,1,1"), gen["Diamond"])
        assertEquals(listOf("9,9,9"), gen["Emerald"])
        // the input is untouched
        assertEquals(3, (baseTree["Team"] as Map<*, *>).size)
    }

    @Test
    fun `diff is the inverse of merge`() {
        val target = TreeMerge.deepCopy(baseTree)
        target["maxInTeam"] = 2
        target["Team"] = linkedMapOf<String, Any?>("Red" to team("RED", 10))
        target.remove("minPlayers")
        (target["generator"] as MutableMap<String, Any?>)["Emerald"] = listOf("5,5,5")
        val replace: (String) -> Boolean = { it == "Team" }
        val d = TreeMerge.diff(baseTree, target, replace)
        assertEquals(listOf("minPlayers"), d.removals)
        assertFalse((d.overrides["generator"] as Map<*, *>).containsKey("Diamond"))

        val rebuilt = TreeMerge.deepCopy(baseTree)
        d.removals.forEach { TreeMerge.remove(rebuilt, it) }
        assertTrue(TreeMerge.sameValue(target, TreeMerge.merge(rebuilt, d.overrides, replace)))
    }

    @Test
    fun `numbers compare by value`() {
        assertTrue(TreeMerge.sameValue(1, 1.0))
        assertTrue(TreeMerge.diff(mapOf("a" to 1), mapOf("a" to 1.0)).isEmpty)
    }

    @Test
    fun `variants per group with closest configuration`() {
        val d = def(
            linkedMapOf(
                "groups" to listOf("Solo", "Doubles", "SoloRush", "DoublesRush", "Squads"),
                "configs" to linkedMapOf(
                    "doubles" to linkedMapOf("overrides" to linkedMapOf("maxInTeam" to 2)),
                    "rush" to linkedMapOf("overrides" to linkedMapOf("minPlayers" to 4)),
                ),
                "group-configs" to linkedMapOf("SoloRush" to "rush"),
            ),
        )
        val plan = planner().plan(listOf(d), mapOf("lighthouse" to base), listOf("Solo", "Doubles", "SoloRush", "DoublesRush", "Squads"), emptySet())
        val byGroup = plan.variants.associateBy { it.group }
        // Solo is the base arena's own group: no variant
        assertEquals(setOf("Doubles", "SoloRush", "DoublesRush", "Squads"), byGroup.keys)
        assertEquals("lighthouse-doubles", byGroup.getValue("Doubles").arenaName)
        assertEquals(2, byGroup.getValue("Doubles").tree["maxInTeam"])
        assertEquals("rush", byGroup.getValue("SoloRush").match.config)
        assertEquals(4, byGroup.getValue("SoloRush").tree["minPlayers"])
        // DoublesRush: doubles and rush tie; "doubles" comes first in the name
        assertEquals("doubles", byGroup.getValue("DoublesRush").match.config)
        assertTrue(plan.warnings.any { it.contains("DoublesRush") && it.contains("equally") })
        // Squads: nothing close -> base configuration
        assertNull(byGroup.getValue("Squads").match.config)
        assertEquals(1, byGroup.getValue("Squads").tree["maxInTeam"])

        val t = byGroup.getValue("Doubles").tree
        assertEquals("Doubles", t["group"])
        assertEquals("Lighthouse", t["display-name"])
        assertEquals(true, (t[VariantPlanner.MARKER_SECTION] as Map<*, *>)["generated"])
    }

    @Test
    fun `group casing comes from BedWars and unknown groups are reported`() {
        val d = def(linkedMapOf("groups" to listOf("doubles", "Brand_New")))
        val plan = planner().plan(listOf(d), mapOf("lighthouse" to base), listOf("Solo", "Doubles"), emptySet())
        assertEquals(listOf("Doubles", "Brand_New"), plan.variants.map { it.group })
        assertEquals(listOf("Brand_New"), plan.unknownGroups)
        assertEquals("lighthouse-brand_new", plan.variants[1].arenaName)
    }

    @Test
    fun `glob patterns expand against BedWars groups`() {
        val d = def(linkedMapOf("groups" to listOf("*Rush", "Solo")))
        val plan = planner().plan(listOf(d), mapOf("lighthouse" to base), listOf("Solo", "SoloRush", "DoublesRush", "Doubles"), emptySet())
        assertEquals(listOf("SoloRush", "DoublesRush"), plan.variants.map { it.group })
    }

    @Test
    fun `existing foreign arena with the variant name is never overwritten`() {
        val d = def(linkedMapOf("groups" to listOf("Doubles")))
        val plan = planner().plan(listOf(d), mapOf("lighthouse" to base), listOf("Solo", "Doubles"), setOf("lighthouse-doubles"))
        assertTrue(plan.variants.isEmpty())
        assertTrue(plan.warnings.single().contains("/bwmg import lighthouse lighthouse-doubles"))
    }

    @Test
    fun `missing base arena and disabled maps`() {
        val plan = planner().plan(
            listOf(def(linkedMapOf("groups" to listOf("Doubles"))), MapDefinition("other", enabled = false, groups = listOf("Doubles"))),
            emptyMap(), listOf("Doubles"), emptySet(),
        )
        assertTrue(plan.variants.isEmpty())
        assertEquals(1, plan.warnings.size)
    }

    @Test
    fun `no fallback skips unmatched groups`() {
        val d = def(linkedMapOf("groups" to listOf("Squads")))
        val plan = planner(PlanSettings(fallbackToBase = false)).plan(listOf(d), mapOf("lighthouse" to base), listOf("Squads"), emptySet())
        assertTrue(plan.variants.isEmpty())
    }

    @Test
    fun `remove paths and shared overrides`() {
        val d = def(
            linkedMapOf(
                "groups" to listOf("Doubles"),
                "shared" to linkedMapOf("worldBorder" to 200),
                "configs" to linkedMapOf("doubles" to linkedMapOf("remove" to listOf("Team.Green"), "overrides" to linkedMapOf("display-name" to "Lighthouse Duos"))),
            ),
        )
        val v = planner().plan(listOf(d), mapOf("lighthouse" to base), listOf("Doubles"), emptySet()).variants.single()
        assertEquals(setOf("Red", "Blue"), (v.tree["Team"] as Map<*, *>).keys)
        assertEquals(200, v.tree["worldBorder"])
        assertEquals("Lighthouse Duos", v.tree["display-name"])
    }

    @Test
    fun `name pattern is sanitised`() {
        val p = planner(PlanSettings(namePattern = "{map}@{group}"))
        assertEquals("lighthouse_doubles_x", p.variantName("Lighthouse", "Doubles X"))
        assertEquals("map-solo", planner().variantName("Map", "Solo"))
    }

    @Test
    fun `parse reports bad types without failing`() {
        val w = ArrayList<String>()
        val d = MapDefinition.parse(
            "x",
            linkedMapOf("groups" to 5, "configs" to linkedMapOf("base" to null, "a" to "oops", "b" to null), "group-configs" to linkedMapOf("G" to "zzz")),
            w,
        )
        assertEquals(listOf("b"), d.configs.map { it.name })
        assertEquals(4, w.size)
    }

    @Test
    fun `registry keeps retired variants until forgotten`() {
        val r = VariantRegistry()
        val v = VariantPlan("a-b", "a", "B", MatchResult(null, 0, ""), linkedMapOf())
        r.replace(listOf(v))
        assertEquals("a", r.baseOf("A-B"))
        r.replace(emptyList())
        assertTrue(r.isRetired("a-b"))
        r.replace(listOf(v))
        assertFalse(r.isRetired("a-b"))
    }
}
