package dev.etran.bedwars

import dev.etran.core.VariantPlanner
import java.io.File
import java.util.Locale
import java.util.logging.Logger

/**
 * Direct access to BedWars1058's files. Used while BedWars1058 is not enabled yet (this plugin prepares the arena
 * files before BedWars1058 reads them), so nothing here may touch the BedWars1058 API.
 */
class BedWarsFiles(val dataFolder: File, private val log: Logger) {

    val arenasDir: File get() = File(dataFolder, "Arenas")

    /** Stock BedWars1058 keeps a pristine zip of every map here (made the first time an arena loads). */
    val cacheDir: File get() = File(dataFolder, "Cache")

    val mainConfig: File get() = File(dataFolder, "config.yml")

    /** Arena configuration files by lower-case arena name. */
    fun arenaFiles(): Map<String, File> {
        val files = arenasDir.listFiles { f -> f.isFile && f.name.endsWith(".yml") && !f.name.startsWith(".") } ?: return emptyMap()
        val out = LinkedHashMap<String, File>()
        for (f in files.sortedBy { it.name }) out.putIfAbsent(f.name.removeSuffix(".yml").lowercase(Locale.ROOT), f)
        return out
    }

    fun arenaFile(name: String): File? = arenaFiles()[name.lowercase(Locale.ROOT)]

    /** True when [tree] is an arena configuration written by this plugin. */
    fun isGenerated(tree: Map<String, Any?>): Boolean =
        (tree[VariantPlanner.MARKER_SECTION] as? Map<*, *>)?.get("generated") == true

    /** BedWars1058's `arenaGroups` (null when the key is missing: every arena is then in group "Default"). */
    fun arenaGroups(): List<String>? {
        val tree = YamlTrees.load(mainConfig) ?: return null
        val v = tree["arenaGroups"] as? List<*> ?: return null
        return v.filterNotNull().map { it.toString() }
    }

    /**
     * Adds [groups] to `arenaGroups` in BedWars1058's config.yml. Only call this before BedWars1058 has loaded its
     * configuration (this plugin's onLoad); later the in-memory copy has to be changed through the API.
     */
    fun addArenaGroupsOnDisk(groups: List<String>): Boolean {
        if (groups.isEmpty() || !mainConfig.isFile) return false
        val warnings = ArrayList<String>()
        val tree = YamlTrees.load(mainConfig, warnings)
        if (tree == null) {
            warnings.forEach { log.warning(it) }
            return false
        }
        val current = (tree["arenaGroups"] as? List<*>)?.filterNotNull()?.map { it.toString() } ?: emptyList()
        val next = current + groups.filter { g -> current.none { it.equals(g, ignoreCase = true) } }
        if (next.size == current.size) return false
        tree["arenaGroups"] = next
        return try {
            YamlTrees.save(mainConfig, tree)
            true
        } catch (ex: Exception) {
            log.warning("Could not add groups to ${mainConfig.path}: ${ex.message}")
            false
        }
    }
}
