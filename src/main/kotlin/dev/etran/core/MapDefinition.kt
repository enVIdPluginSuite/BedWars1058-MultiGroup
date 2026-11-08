package dev.etran.core

import java.util.Locale

/**
 * One named configuration of a map (`configs.<name>` in a map file).
 *
 * @property overrides YAML tree layered on top of the base arena configuration (`plugins/BedWars1058/Arenas/<map>.yml`)
 * @property remove dotted paths removed from the base configuration before the overrides are applied
 * @property match extra names this configuration should match (e.g. `[duos, 2v2]`)
 */
data class MapConfig(
    val name: String,
    val match: List<String> = emptyList(),
    val overrides: Map<String, Any?> = emptyMap(),
    val remove: List<String> = emptyList(),
) {
    fun candidate() = ConfigCandidate(name, match)
}

/**
 * A map file `plugins/BedWars1058-MultiGroup/maps/<map>.yml`: in which extra groups the arena `<map>` is played, and
 * the configurations available for those groups.
 */
data class MapDefinition(
    /** The BedWars1058 arena (and therefore world/map) name, as written in the file name. */
    val name: String,
    val enabled: Boolean = true,
    /** Extra groups. Entries may be glob patterns (`*Rush`, `*`) matched against BedWars1058's `arenaGroups`. */
    val groups: List<String> = emptyList(),
    /** Fixed choices: group -> configuration name (or `base`). Wins over matching. */
    val groupConfigs: Map<String, String> = emptyMap(),
    /** Layered on every variant of this map, before the chosen configuration. */
    val shared: Map<String, Any?> = emptyMap(),
    val configs: List<MapConfig> = emptyList(),
) {
    val key: String get() = name.lowercase(Locale.ROOT)

    fun config(name: String): MapConfig? = configs.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun explicitFor(group: String): String? =
        groupConfigs.entries.firstOrNull { it.key.equals(group, ignoreCase = true) }?.value

    companion object {
        /**
         * Reads a map file tree. Unknown keys are ignored, wrong types are reported in [warnings] and skipped, so a
         * typo never takes the whole map down.
         */
        fun parse(name: String, tree: Map<String, Any?>, warnings: MutableList<String>): MapDefinition {
            fun warn(msg: String) {
                warnings += "maps/$name.yml: $msg"
            }

            val enabled = when (val e = tree["enabled"]) {
                null -> true
                is Boolean -> e
                else -> e.toString().equals("true", ignoreCase = true).also { warn("'enabled' should be true or false") }
            }

            val groups = stringList(tree["groups"], "groups", ::warn)

            val groupConfigs = LinkedHashMap<String, String>()
            when (val gc = tree["group-configs"]) {
                null -> {}
                is Map<*, *> -> for ((k, v) in gc) if (k != null && v != null) groupConfigs[k.toString()] = v.toString()
                else -> warn("'group-configs' must be a section (group: config)")
            }

            val shared = when (val s = tree["shared"]) {
                null -> emptyMap()
                is Map<*, *> -> TreeMerge.deepCopy(TreeMerge.asTree(s))
                else -> emptyMap<String, Any?>().also { warn("'shared' must be a section") }
            }

            val configs = ArrayList<MapConfig>()
            when (val cs = tree["configs"]) {
                null -> {}
                is Map<*, *> -> for ((k, v) in cs) {
                    val cname = k?.toString() ?: continue
                    if (cname.equals(ConfigMatcher.BASE, ignoreCase = true)) {
                        warn("'$cname' is reserved for the base configuration; rename that configuration")
                        continue
                    }
                    if (v == null) {
                        configs += MapConfig(cname)
                        continue
                    }
                    if (v !is Map<*, *>) {
                        warn("configs.$cname must be a section")
                        continue
                    }
                    val c = TreeMerge.asTree(v)
                    val overrides = when (val o = c["overrides"]) {
                        null -> emptyMap()
                        is Map<*, *> -> TreeMerge.deepCopy(TreeMerge.asTree(o))
                        else -> emptyMap<String, Any?>().also { warn("configs.$cname.overrides must be a section") }
                    }
                    configs += MapConfig(
                        name = cname,
                        match = stringList(c["match"], "configs.$cname.match", ::warn),
                        overrides = overrides,
                        remove = stringList(c["remove"], "configs.$cname.remove", ::warn),
                    )
                }
                else -> warn("'configs' must be a section")
            }
            val dup = configs.groupBy { it.name.lowercase(Locale.ROOT) }.filter { it.value.size > 1 }.keys
            if (dup.isNotEmpty()) warn("configuration names differ only in case: ${dup.joinToString()}; only the first of each is used")
            val unique = configs.distinctBy { it.name.lowercase(Locale.ROOT) }

            for ((g, c) in groupConfigs) {
                if (!c.equals(ConfigMatcher.BASE, ignoreCase = true) && unique.none { it.name.equals(c, ignoreCase = true) }) {
                    warn("group-configs.$g points to '$c', which is not a configuration of this map; the closest match is used instead")
                }
            }
            return MapDefinition(name, enabled, groups, groupConfigs, shared, unique)
        }

        private fun stringList(v: Any?, key: String, warn: (String) -> Unit): List<String> = when (v) {
            null -> emptyList()
            is List<*> -> v.filterNotNull().map { it.toString().trim() }.filter { it.isNotEmpty() }
            is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList<String>().also { warn("'$key' must be a list") }
        }

        /** Serialises a definition back into a tree (used by the in-game editing commands). */
        fun toTree(d: MapDefinition): LinkedHashMap<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            out["enabled"] = d.enabled
            out["groups"] = d.groups.toList()
            out["group-configs"] = LinkedHashMap(d.groupConfigs)
            out["shared"] = TreeMerge.deepCopy(d.shared)
            val cs = LinkedHashMap<String, Any?>()
            for (c in d.configs) {
                val m = LinkedHashMap<String, Any?>()
                m["match"] = c.match.toList()
                m["overrides"] = TreeMerge.deepCopy(c.overrides)
                m["remove"] = c.remove.toList()
                cs[c.name] = m
            }
            out["configs"] = cs
            return out
        }
    }
}
