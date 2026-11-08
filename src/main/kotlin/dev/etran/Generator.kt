package dev.etran

import dev.etran.bedwars.BedWarsFiles
import dev.etran.bedwars.YamlTrees
import dev.etran.core.BaseArena
import dev.etran.core.MapDefinition
import dev.etran.core.Plan
import dev.etran.core.VariantPlanner
import dev.etran.core.VariantRegistry
import java.io.File
import java.util.Locale
import java.util.logging.Logger

/**
 * Writes one BedWars1058 arena configuration per (map, extra group) pair into `plugins/BedWars1058/Arenas/` and removes
 * the ones that are no longer wanted. Only files that carry this plugin's marker section are ever changed or deleted.
 */
class Generator(
    private val files: BedWarsFiles,
    private val maps: MapStore,
    private val registry: VariantRegistry,
    private val log: Logger,
) {
    data class Result(
        val plan: Plan,
        val written: List<String>,
        val removed: List<String>,
        /** variants that are new or whose configuration changed */
        val changed: Set<String>,
        val warnings: List<String>,
        val definitions: List<MapDefinition>,
    )

    /**
     * @param groupsAdder called with groups that BedWars1058's arenaGroups lacks (when register-missing-groups is on);
     *                    returns the groups it really added
     */
    fun run(settings: Settings, groupsAdder: (List<String>) -> List<String>): Result {
        val warnings = ArrayList<String>()
        val defs = maps.loadAll(warnings)
        val arenaFiles = files.arenaFiles()

        val trees = HashMap<String, Map<String, Any?>>()
        val generated = HashSet<String>()
        for ((key, f) in arenaFiles) {
            val t = YamlTrees.load(f, warnings) ?: continue
            trees[key] = t
            if (files.isGenerated(t)) generated += key
        }
        val foreign = arenaFiles.keys - generated

        var bedwarsGroups = files.arenaGroups()
        val bases = HashMap<String, BaseArena>()
        for (def in defs) {
            val key = def.key
            if (key in generated) {
                warnings += "maps/${def.name}.yml: '${def.name}' is itself a generated variant; a map file must name the original arena."
                continue
            }
            val t = trees[key] ?: continue
            bases[key] = BaseArena(arenaFiles.getValue(key).name.removeSuffix(".yml"), effectiveGroup(t, bedwarsGroups), t)
        }

        val planner = VariantPlanner(settings.matcher(), settings.plan)
        var plan = planner.plan(defs, bases, bedwarsGroups ?: emptyList(), foreign)

        if (plan.unknownGroups.isNotEmpty()) {
            val added = if (settings.registerMissingGroups) groupsAdder(plan.unknownGroups) else emptyList()
            if (added.isNotEmpty()) {
                log.info("Added group(s) ${added.joinToString(", ")} to BedWars1058's arenaGroups.")
                bedwarsGroups = (bedwarsGroups ?: emptyList()) + added
                plan = planner.plan(defs, bases, bedwarsGroups, foreign)
            }
            val missing = plan.unknownGroups.filter { g -> added.none { it.equals(g, ignoreCase = true) } }
            if (missing.isNotEmpty()) {
                warnings += "Group(s) ${missing.joinToString(", ")} are not in BedWars1058's arenaGroups (config.yml), so BedWars1058 " +
                    "puts those variants into the group 'Default'. Add them there or enable register-missing-groups."
            }
        }
        warnings += plan.warnings

        // write the wanted files
        val written = ArrayList<String>()
        val wanted = HashSet<String>()
        for (v in plan.variants) {
            wanted += v.key
            val existing = arenaFiles[v.key]
            val target = File(files.arenasDir, v.key + ".yml")
            try {
                // a generated file whose name differs only in case: replace it by the lower-case one
                if (existing != null && existing.name != target.name && v.key in generated) existing.delete()
                if (YamlTrees.save(target, v.tree)) written += v.arenaName
            } catch (ex: Exception) {
                warnings += "Could not write ${target.path}: ${ex.message}"
            }
        }

        // remove generated files nobody wants any more
        val removed = ArrayList<String>()
        for (key in generated) {
            if (key in wanted) continue
            val f = arenaFiles[key] ?: continue
            if (f.delete()) removed += f.name.removeSuffix(".yml") else warnings += "Could not delete the old variant file ${f.path}."
        }

        val changed = registry.replace(plan.variants)
        return Result(plan, written, removed, changed, warnings, defs)
    }

    companion object {
        /** The group BedWars1058 really gives an arena: its `group` only when that is listed in arenaGroups (case-sensitive). */
        fun effectiveGroup(tree: Map<String, Any?>, arenaGroups: List<String>?): String {
            val g = tree["group"]?.toString() ?: return "Default"
            return if (arenaGroups != null && g in arenaGroups) g else "Default"
        }

        fun key(name: String) = name.lowercase(Locale.ROOT)
    }
}
