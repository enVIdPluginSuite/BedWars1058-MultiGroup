package dev.etran.core

import java.util.Locale

/** The fingerprint of an arena: every coordinate its configuration mentions (x,y,z, rounded to the block). */
data class ArenaSig(val name: String, val group: String, val locations: Set<String>)

/** One arena of a [Cluster]. [similarity] is measured against the cluster's base (or the closest other member). */
data class DupMember(val name: String, val group: String, val similarity: Double)

/**
 * Arenas that are probably copies of one map.
 *
 * @property base the arena the others should be merged into, or null when no arena can be told apart as the original
 * @property note why [base] is null / what needs a human decision
 */
data class Cluster(val base: String?, val baseGroup: String?, val members: List<DupMember>, val note: String?)

/**
 * Finds arenas that are copies of the same map.
 *
 * A copied map keeps its coordinates, so two arenas of the same map mention (almost) the same locations: team spawns,
 * beds, shops, generators, waiting lobby. The detector compares those sets (Jaccard similarity) instead of looking at
 * names or files, so it works whatever the copies are called and wherever their worlds are stored.
 *
 *  - similarity >= [minSimilarity] (default 0.8): the same map; safe to merge automatically
 *  - similarity >= [reviewSimilarity] (default 0.5): probably the same map with another layout (fewer teams, moved
 *    generators); reported, merged only by hand
 *
 * The original of a cluster is the arena whose name is the common beginning of the others' names (`lighthouse` for
 * `lighthouse_doubles`, `lighthouse-solorush`), preferring one that already has a map file. If no arena qualifies the
 * cluster has no base and is only reported.
 */
class DuplicateDetector(
    val minSimilarity: Double = 0.8,
    val reviewSimilarity: Double = 0.5,
    val minLocations: Int = 4,
) {

    fun signature(name: String, tree: Map<String, Any?>): ArenaSig {
        val out = HashSet<String>()
        collect(tree, out, top = true)
        return ArenaSig(name, tree["group"]?.toString() ?: "Default", out)
    }

    private fun collect(node: Any?, out: MutableSet<String>, top: Boolean = false) {
        when (node) {
            is Map<*, *> -> for ((k, v) in node) {
                if (top && k == VariantPlanner.MARKER_SECTION) continue
                collect(v, out)
            }
            is List<*> -> node.forEach { collect(it, out) }
            is String -> LOCATION.matchEntire(node.trim())?.let { m ->
                out += listOf(1, 2, 3).joinToString(",") { Math.floor(m.groupValues[it].toDouble()).toLong().toString() }
            }
            else -> {}
        }
    }

    fun similarity(a: ArenaSig, b: ArenaSig): Double {
        if (a.locations.size < minLocations || b.locations.size < minLocations) return 0.0
        val inter = a.locations.count { it in b.locations }
        val union = a.locations.size + b.locations.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /** @param withMapFile lower-case names of arenas that already have a `maps/<name>.yml` (preferred as the original) */
    fun detect(sigs: List<ArenaSig>, withMapFile: Set<String> = emptySet()): List<Cluster> {
        val n = sigs.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (similarity(sigs[i], sigs[j]) >= reviewSimilarity) parent[find(i)] = find(j)
        }
        val groups = (0 until n).groupBy { find(it) }.values.filter { it.size > 1 }

        val clusters = ArrayList<Cluster>()
        for (g in groups) {
            val members = g.map { sigs[it] }.sortedBy { it.name.lowercase(Locale.ROOT) }
            val base = pickBase(members, withMapFile)
            if (base == null) {
                val list = members.map { m ->
                    DupMember(m.name, m.group, members.filter { it !== m }.maxOf { similarity(m, it) })
                }
                clusters += Cluster(null, null, list, "no arena is clearly the original; merge by hand: /bwmg merge <original> <copy>")
            } else {
                val list = members.filter { it !== base }.map { DupMember(it.name, it.group, similarity(base, it)) }
                clusters += Cluster(base.name, base.group, list, null)
            }
        }
        return clusters.sortedBy { (it.base ?: it.members.first().name).lowercase(Locale.ROOT) }
    }

    /** True when [member] can be merged without anyone looking at it. */
    fun isClear(cluster: Cluster, member: DupMember): Boolean =
        cluster.base != null && member.similarity >= minSimilarity && !member.group.equals(cluster.baseGroup, ignoreCase = true)

    private fun pickBase(members: List<ArenaSig>, withMapFile: Set<String>): ArenaSig? {
        val norm = members.associateWith { ConfigMatcher.normalize(it.name) }
        val candidates = members.filter { c ->
            val cn = norm.getValue(c)
            cn.isNotEmpty() && members.all { o -> o === c || norm.getValue(o).startsWith(cn) }
        }
        if (candidates.isEmpty()) return null
        val preferred = candidates.filter { it.name.lowercase(Locale.ROOT) in withMapFile }
        val pool = preferred.ifEmpty { candidates }
        val shortest = pool.minOf { norm.getValue(it).length }
        val best = pool.filter { norm.getValue(it).length == shortest }
        return if (best.size == 1) best[0] else null
    }

    companion object {
        /** `x,y,z` or `x,y,z,yaw,pitch` as BedWars1058 stores locations. */
        private val LOCATION = Regex("(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)(?:,-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?)?")
    }
}
