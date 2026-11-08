package dev.etran.core

import java.util.Locale

/**
 * The variants that are currently generated. Replaced as a whole on every (re)load, so readers on any thread
 * (BedWars1058-MapLoader resolves names on its IO threads) always see one consistent snapshot.
 *
 * Variants that disappear on a reload are kept as *retired*: an arena object of that name may still exist (a game
 * running on it, or parked by BedWars1058-MapLoader), so its map must stay resolvable until it is wound down.
 */
class VariantRegistry {

    private class Snapshot(val active: Map<String, VariantPlan>, val retired: Map<String, VariantPlan>)

    @Volatile
    private var snap = Snapshot(emptyMap(), emptyMap())

    /** Installs [variants]; returns the keys whose generated configuration changed (or that are new). */
    @Synchronized
    fun replace(variants: List<VariantPlan>): Set<String> {
        val old = snap
        val next = LinkedHashMap<String, VariantPlan>()
        for (v in variants) next[v.key] = v
        val retired = LinkedHashMap(old.retired)
        for ((k, v) in old.active) if (k !in next) retired[k] = v
        for (k in next.keys) retired.remove(k)
        snap = Snapshot(next, retired)
        return next.filter { (k, v) -> old.active[k]?.let { !TreeMerge.sameValue(it.tree, v.tree) } ?: true }.keys
    }

    operator fun get(name: String): VariantPlan? = snap.active[name.lowercase(Locale.ROOT)]

    fun isVariant(name: String): Boolean = snap.active.containsKey(name.lowercase(Locale.ROOT))

    fun isRetired(name: String): Boolean = snap.retired.containsKey(name.lowercase(Locale.ROOT))

    /** The base map of an active or retired variant arena, or null when [name] is not a variant. */
    fun baseOf(name: String): String? {
        val k = name.lowercase(Locale.ROOT)
        val s = snap
        return (s.active[k] ?: s.retired[k])?.map
    }

    /** True for active and retired variants: arenas whose world is a copy of another map. */
    fun isKnown(name: String): Boolean = baseOf(name) != null

    @Synchronized
    fun forgetRetired(name: String) {
        val s = snap
        snap = Snapshot(s.active, s.retired - name.lowercase(Locale.ROOT))
    }

    fun all(): List<VariantPlan> = snap.active.values.toList()

    fun retired(): List<VariantPlan> = snap.retired.values.toList()

    fun ofMap(map: String): List<VariantPlan> = snap.active.values.filter { it.map.equals(map, ignoreCase = true) }

    fun size(): Int = snap.active.size
}
