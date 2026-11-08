package dev.etran

import dev.etran.core.ConfigMatcher
import dev.etran.core.PlanSettings
import org.bukkit.ChatColor
import org.bukkit.configuration.ConfigurationSection

/** Everything in `plugins/BedWars1058-MultiGroup/config.yml`. */
data class Settings(
    val plan: PlanSettings,
    val minScore: Int,
    val synonyms: Map<String, List<String>>,
    val registerMissingGroups: Boolean,
    val registerPerTick: Int,
    val world: WorldSettings,
    val messages: Messages,
    val duplicates: DuplicateSettings,
    /** BedWars1058's main command and aliases (to guard `setupArena` / `deleteArena` on variants). */
    val bedwarsCommands: List<String>,
    val debug: Boolean,
) {
    data class WorldSettings(
        val deleteLevelDat: Boolean,
        val voidGenerator: Boolean,
        val keepSpawnInMemory: Boolean,
        val ioThreads: Int,
    )

    data class DuplicateSettings(
        val autoMerge: Boolean,
        val minSimilarity: Double,
        val reviewSimilarity: Double,
        val minLocations: Int,
    )

    data class Messages(val setupBlocked: String, val deleteBlocked: String) {
        fun format(template: String, vararg pairs: Pair<String, String>): String {
            var s = template
            for ((k, v) in pairs) s = s.replace("{$k}", v)
            return ChatColor.translateAlternateColorCodes('&', s)
        }
    }

    fun matcher() = ConfigMatcher(synonyms, minScore)

    companion object {
        fun read(c: ConfigurationSection, warnings: MutableList<String>): Settings {
            val pattern = c.getString("variant-name-pattern", "{map}-{group}") ?: "{map}-{group}"
            if (!pattern.contains("{map}") || !pattern.contains("{group}")) {
                warnings += "variant-name-pattern must contain {map} and {group}; using {map}-{group}."
            }
            val plan = PlanSettings(
                namePattern = if (pattern.contains("{map}") && pattern.contains("{group}")) pattern else "{map}-{group}",
                displayNameFormat = c.getString("display-name-format", "{display}") ?: "{display}",
                replaceSections = c.getStringList("replace-sections").takeIf { c.isList("replace-sections") }?.toSet() ?: setOf("Team"),
                fallbackToBase = c.getBoolean("fallback-to-base", true),
            )

            val synonyms = LinkedHashMap<String, List<String>>()
            val syn = c.getConfigurationSection("matching.synonyms")
            if (syn == null) {
                synonyms.putAll(ConfigMatcher.DEFAULT_SYNONYMS)
            } else {
                for (k in syn.getKeys(false)) synonyms[k] = syn.getStringList(k)
            }

            val minScore = c.getInt("matching.min-score", 150).let {
                if (it < 1 || it > ConfigMatcher.EXACT) {
                    warnings += "matching.min-score must be between 1 and ${ConfigMatcher.EXACT}; using 150."
                    150
                } else it
            }

            val world = WorldSettings(
                deleteLevelDat = c.getBoolean("world.delete-level-dat", true),
                voidGenerator = c.getBoolean("world.void-generator", true),
                keepSpawnInMemory = c.getBoolean("world.keep-spawn-in-memory", true),
                ioThreads = c.getInt("world.io-threads", 2).coerceIn(1, 16),
            )

            val messages = Messages(
                setupBlocked = c.getString("messages.setup-blocked", DEFAULT_SETUP_BLOCKED) ?: DEFAULT_SETUP_BLOCKED,
                deleteBlocked = c.getString("messages.delete-blocked", DEFAULT_DELETE_BLOCKED) ?: DEFAULT_DELETE_BLOCKED,
            )

            val minSim = c.getDouble("duplicates.min-similarity", 0.8).coerceIn(0.3, 1.0)
            val duplicates = DuplicateSettings(
                autoMerge = c.getBoolean("duplicates.auto-merge", true),
                minSimilarity = minSim,
                reviewSimilarity = minOf(0.5, minSim),
                minLocations = c.getInt("duplicates.min-locations", 4).coerceAtLeast(2),
            )

            return Settings(
                plan = plan,
                minScore = minScore,
                synonyms = synonyms,
                registerMissingGroups = c.getBoolean("register-missing-groups", true),
                registerPerTick = c.getInt("register-per-tick", 5).coerceIn(1, 100),
                world = world,
                messages = messages,
                duplicates = duplicates,
                bedwarsCommands = c.getStringList("bedwars-commands").takeIf { c.isList("bedwars-commands") } ?: listOf("bw", "bedwars"),
                debug = c.getBoolean("debug", false),
            )
        }

        private const val DEFAULT_SETUP_BLOCKED =
            "&c{arena} is the {group} variant of the map {map}. Set up the map itself with &e/bw setupArena {map}&c; " +
                "group specific settings are changed with &e/bwmg&c."
        private const val DEFAULT_DELETE_BLOCKED =
            "&c{arena} is the {group} variant of the map {map}. Remove the group with &e/bwmg group remove {map} {group}&c instead."
    }
}
