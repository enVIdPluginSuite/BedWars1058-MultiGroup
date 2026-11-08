package dev.etran

import dev.etran.bedwars.YamlTrees
import dev.etran.core.MapDefinition
import java.io.File
import java.util.Locale

/**
 * The map files: `plugins/BedWars1058-MultiGroup/maps/<map>.yml`, one per map that is played in more than one group.
 * Files whose name starts with `_` or `.` are ignored (the shipped `_example.yml`).
 */
class MapStore(val dir: File) {

    fun files(): Map<String, File> {
        val list = dir.listFiles { f -> f.isFile && f.name.endsWith(".yml") && !f.name.startsWith("_") && !f.name.startsWith(".") }
            ?: return emptyMap()
        val out = LinkedHashMap<String, File>()
        for (f in list.sortedBy { it.name }) out.putIfAbsent(f.name.removeSuffix(".yml").lowercase(Locale.ROOT), f)
        return out
    }

    fun loadAll(warnings: MutableList<String>): List<MapDefinition> {
        val out = ArrayList<MapDefinition>()
        for ((_, f) in files()) {
            val tree = YamlTrees.load(f, warnings) ?: continue
            out += MapDefinition.parse(f.name.removeSuffix(".yml"), tree, warnings)
        }
        return out
    }

    fun exists(map: String): Boolean = files().containsKey(map.lowercase(Locale.ROOT))

    fun fileFor(map: String): File = files()[map.lowercase(Locale.ROOT)] ?: File(dir, map.lowercase(Locale.ROOT) + ".yml")

    /** The raw tree of a map file (a new, empty definition when the file does not exist yet). */
    fun loadTree(map: String): LinkedHashMap<String, Any?> {
        val f = fileFor(map)
        val tree = if (f.isFile) YamlTrees.load(f) else null
        return tree ?: MapDefinition.toTree(MapDefinition(map))
    }

    fun saveTree(map: String, tree: Map<String, Any?>) {
        YamlTrees.save(fileFor(map), tree, HEADER)
    }

    fun delete(map: String): Boolean = files()[map.lowercase(Locale.ROOT)]?.delete() ?: false

    companion object {
        val HEADER = """
            BedWars1058-MultiGroup map file. The file name is the BedWars1058 arena of the map (plugins/BedWars1058/Arenas/<name>.yml).
            groups:         extra groups this map is played in (patterns like *Rush allowed). Its own arena's group is always played.
            group-configs:  optional fixed choice per group: <group>: <config> (or 'base' for the unmodified arena configuration).
            shared:         settings applied to every variant of this map.
            configs:        named configurations. Each group gets the configuration whose name matches the group most closely.
              <name>:
                match:      extra names this configuration should match, e.g. [duos, 2v2]
                overrides:  arena settings layered over the arena's own configuration (sections in replace-sections, like Team, are replaced whole)
                remove:     dotted paths removed from the arena's configuration first, e.g. [Team.Yellow]
            Apply changes with /bwmg reload. See /bwmg help.
        """.trimIndent()
    }
}
