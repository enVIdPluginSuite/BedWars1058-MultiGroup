package dev.etran

import dev.etran.bedwars.BedWarsFiles
import dev.etran.bedwars.YamlTrees
import dev.etran.core.ArenaSig
import dev.etran.core.Cluster
import dev.etran.core.DuplicateDetector
import dev.etran.core.TreeMerge
import dev.etran.core.VariantPlanner
import dev.etran.world.FileOps
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.logging.Logger

/**
 * Turns arenas that are copies of a map into configurations of the original map (what the plugin exists for), so an
 * existing server can be moved to the new system without redoing anything by hand.
 *
 * File work only (arena files and map files); the maps (worlds) of the copies are handled by the caller, because that
 * needs the restore adapter. Nothing is deleted here: arena files go to `plugins/BedWars1058-MultiGroup/backups/`.
 */
class Merger(
    private val files: BedWarsFiles,
    private val maps: MapStore,
    private val settings: () -> Settings,
    private val dataFolder: File,
    private val serverRoot: File,
    private val isLoaded: (String) -> Boolean,
    private val log: Logger,
) {
    class MergeException(message: String) : Exception(message)

    /** Everything a merge will do, computed without changing anything. */
    data class Plan(
        val base: String,
        val dup: String,
        val group: String,
        val configName: String,
        val diff: TreeMerge.Diff,
        /** the arena the copy's group will get */
        val variantName: String?,
        val dupFile: File,
    )

    data class Detection(val clusters: List<Cluster>, val warnings: List<String>)

    data class AutoResult(
        val merged: List<Plan>,
        val skipped: List<String>,
        val needReview: List<String>,
    )

    fun detector(): DuplicateDetector {
        val d = settings().duplicates
        return DuplicateDetector(d.minSimilarity, d.reviewSimilarity, d.minLocations)
    }

    fun detect(): Detection {
        val warnings = ArrayList<String>()
        val sigs = ArrayList<ArenaSig>()
        val det = detector()
        for ((_, f) in files.arenaFiles()) {
            val tree = YamlTrees.load(f, warnings) ?: continue
            if (files.isGenerated(tree)) continue
            sigs += det.signature(f.name.removeSuffix(".yml"), tree)
        }
        val withMapFile = maps.files().keys
        return Detection(det.detect(sigs, withMapFile), warnings)
    }

    // ---------------------------------------------------------------------------------------------

    /** Checks a merge and computes what it would change. Throws [MergeException] with a reason when it cannot be done. */
    fun prepare(baseArg: String, dupArg: String, requestedConfig: String? = null): Plan {
        val baseFile = files.arenaFile(baseArg) ?: throw MergeException("There is no arena '$baseArg'.")
        val dupFile = files.arenaFile(dupArg) ?: throw MergeException("There is no arena '$dupArg'.")
        val base = baseFile.name.removeSuffix(".yml")
        val dup = dupFile.name.removeSuffix(".yml")
        if (base.equals(dup, ignoreCase = true)) throw MergeException("An arena cannot be merged into itself.")
        val baseTree = YamlTrees.load(baseFile) ?: throw MergeException("Could not read ${baseFile.name}.")
        val dupTree = YamlTrees.load(dupFile) ?: throw MergeException("Could not read ${dupFile.name}.")
        if (files.isGenerated(baseTree)) throw MergeException("'$base' is a generated variant, not a map.")
        if (files.isGenerated(dupTree)) throw MergeException("'$dup' is a generated variant, not a copy.")

        val group = dupTree["group"]?.toString() ?: "Default"
        val baseGroup = baseTree["group"]?.toString() ?: "Default"
        if (group.equals(baseGroup, ignoreCase = true)) {
            throw MergeException("'$dup' and '$base' are in the same group ($group); a variant could not replace it. Keep both arenas or delete one.")
        }

        val mapTree = maps.loadTree(base)
        val groups = (mapTree["groups"] as? List<*>)?.filterNotNull()?.map { it.toString() }.orEmpty()
        if (groups.any { it.equals(group, ignoreCase = true) }) {
            throw MergeException("'$base' is already played in the group $group (maps/$base.yml); '$dup' would be a second arena for it.")
        }
        val existing = (mapTree["configs"] as? Map<*, *>)?.keys?.map { it.toString() }.orEmpty()
        fun taken(n: String) = existing.any { it.equals(n, ignoreCase = true) } || n.equals("base", ignoreCase = true) || n.equals("shared", ignoreCase = true)
        val configName = if (requestedConfig != null) {
            if (taken(requestedConfig)) throw MergeException("'$base' already has a configuration called '$requestedConfig'.")
            requestedConfig
        } else {
            val fromGroup = safeConfigName(group)
            val fromName = safeConfigName(dup)
            when {
                !taken(fromGroup) -> fromGroup
                !taken(fromName) -> fromName
                else -> throw MergeException("'$base' already has configurations called '$fromGroup' and '$fromName'; name one: /bwmg merge $base $dup <config>.")
            }
        }

        val replace: (String) -> Boolean = { it in settings().plan.replaceSections }
        val cleanBase = TreeMerge.deepCopy(baseTree).also { it.remove("group"); it.remove(VariantPlanner.MARKER_SECTION) }
        val cleanDup = TreeMerge.deepCopy(dupTree).also { it.remove("group"); it.remove(VariantPlanner.MARKER_SECTION) }
        val diff = TreeMerge.diff(cleanBase, cleanDup, replace)
        val variant = VariantPlanner(settings().matcher(), settings().plan).variantName(base, group)
        return Plan(base, dup, group, configName, diff, variant, dupFile)
    }

    /** Writes the configuration into the map file and moves the copy's arena file to the backups. Returns the backup. */
    fun apply(plan: Plan): File {
        val tree = maps.loadTree(plan.base)
        val groups = (tree["groups"] as? List<*>)?.filterNotNull()?.map { it.toString() }?.toMutableList() ?: ArrayList()
        groups += plan.group
        tree["groups"] = groups
        section(tree, "configs")[plan.configName] = linkedMapOf<String, Any?>(
            "match" to listOf(plan.dup),
            "merged-from" to plan.dup, // the copy whose world can now be deleted: /bwmg cleanup
            "overrides" to plan.diff.overrides,
            "remove" to plan.diff.removals,
        )
        section(tree, "group-configs")[plan.group] = plan.configName
        maps.saveTree(plan.base, tree)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val backup = File(dataFolder, "backups/arenas/${plan.dup}-$stamp.yml")
        backup.parentFile.mkdirs()
        if (!plan.dupFile.renameTo(backup)) {
            plan.dupFile.copyTo(backup, overwrite = true)
            plan.dupFile.delete()
        }
        return backup
    }

    /**
     * A plain world folder in the server root called like the new variant (typically the copy's own map, when the copy
     * was named like a variant) would stop the variant from creating its world. Moves it to the backups. Returns the
     * new location, or null when there was nothing to do.
     */
    fun setAsideClashingWorld(variantName: String?): File? {
        if (variantName == null) return null
        val dir = File(serverRoot, variantName)
        if (!dir.isDirectory || File(dir, FileOps.MARKER).exists() || isLoaded(variantName)) return null
        if (!FileOps.isWorldFolder(dir)) return null
        val aside = File(dataFolder, "backups/worlds/$variantName-${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())}")
        aside.parentFile.mkdirs()
        return if (dir.renameTo(aside)) aside else null
    }

    /**
     * Merges every clear duplicate (same layout, different group, a recognisable original). Anything else is only
     * reported. Meant for startup, before BedWars1058 has read its arenas.
     */
    fun autoMerge(): AutoResult {
        val det = detector()
        val merged = ArrayList<Plan>()
        val skipped = ArrayList<String>()
        val review = ArrayList<String>()
        for (cluster in detect().clusters) {
            if (cluster.base == null) {
                review += "${cluster.members.joinToString(", ") { it.name }}: ${cluster.note}"
                continue
            }
            for (m in cluster.members) {
                if (!det.isClear(cluster, m)) {
                    review += if (m.group.equals(cluster.baseGroup, ignoreCase = true))
                        "'${m.name}' looks like a copy of '${cluster.base}' but is in the same group (${m.group}); not merged"
                    else
                        "'${m.name}' resembles '${cluster.base}' (${percent(m.similarity)}), probably another layout; check it, then /bwmg merge ${cluster.base} ${m.name}"
                    continue
                }
                try {
                    val plan = prepare(cluster.base, m.name)
                    val backup = apply(plan)
                    setAsideClashingWorld(plan.variantName)
                    merged += plan
                    log.info("Merged '${plan.dup}' into '${plan.base}': group ${plan.group} is now the variant ${plan.variantName} " +
                        "(configuration '${plan.configName}', ${countLeaves(plan.diff.overrides)} changed setting(s)). Arena file backup: ${backup.path}")
                } catch (ex: MergeException) {
                    skipped += "'${m.name}' was not merged into '${cluster.base}': ${ex.message}"
                }
            }
        }
        return AutoResult(merged, skipped, review)
    }

    // ---------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun section(tree: MutableMap<String, Any?>, key: String): MutableMap<String, Any?> {
        val cur = tree[key]
        if (cur is MutableMap<*, *>) return cur as MutableMap<String, Any?>
        val m = if (cur is Map<*, *>) TreeMerge.deepCopy(TreeMerge.asTree(cur)) else LinkedHashMap()
        tree[key] = m
        return m
    }

    companion object {
        fun safeConfigName(s: String): String =
            s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_.-]"), "_").trim('_').ifEmpty { "copy" }

        fun countLeaves(t: Map<String, Any?>): Int = t.values.sumOf { if (it is Map<*, *>) countLeaves(TreeMerge.asTree(it)) else 1 }

        fun percent(d: Double) = "${(d * 100).toInt()}%"
    }
}
