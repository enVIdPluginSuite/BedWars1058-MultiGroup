package dev.etran.command

import dev.etran.Merger
import dev.etran.MultiGroupPlugin
import dev.etran.bedwars.YamlTrees
import dev.etran.core.ConfigMatcher
import dev.etran.core.MapDefinition
import dev.etran.core.TreeMerge
import dev.etran.core.VariantPlanner
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.util.Locale

/** `/bwmg` - inspect and edit which groups a map is played in and with which configuration. */
class MultiGroupCommand(private val plugin: MultiGroupPlugin) : CommandExecutor, TabCompleter {

    private val g = ChatColor.GRAY.toString()
    private val a = ChatColor.AQUA.toString()
    private val y = ChatColor.YELLOW.toString()
    private val gr = ChatColor.GREEN.toString()
    private val r = ChatColor.RED.toString()
    private val w = ChatColor.WHITE.toString()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase(Locale.ROOT) ?: "help"
        val rest = args.drop(1)
        try {
            when (sub) {
                "status" -> status(sender)
                "list" -> list(sender)
                "info" -> need(sender, rest, 1, "info <map>") { info(sender, it[0]) }
                "match" -> need(sender, rest, 2, "match <map> <group>") { match(sender, it[0], it[1]) }
                "reload" -> reload(sender)
                "create" -> need(sender, rest, 1, "create <map>") { create(sender, it[0]) }
                "group" -> group(sender, rest)
                "config" -> config(sender, rest)
                "fix" -> need(sender, rest, 3, "fix <map> <group> <config|base|auto>") { fix(sender, it[0], it[1], it[2]) }
                "set" -> need(sender, rest, 4, "set <map> <config|shared> <path> <value>") {
                    set(sender, it[0], it[1], it[2], it.drop(3).joinToString(" "))
                }
                "setloc" -> need(sender, rest, 3, "setloc <map> <config|shared> <path> [center] [add]") {
                    setLoc(sender, it[0], it[1], it[2], it.drop(3).map { f -> f.lowercase(Locale.ROOT) })
                }
                "unset" -> need(sender, rest, 3, "unset <map> <config|shared> <path>") { unset(sender, it[0], it[1], it[2]) }
                "drop" -> need(sender, rest, 3, "drop <map> <config> <path>") { drop(sender, it[0], it[1], it[2]) }
                "duplicates", "dupes" -> duplicates(sender)
                "merge", "import" -> merge(sender, rest)
                "cleanup" -> cleanup(sender, rest)
                else -> help(sender, label)
            }
        } catch (ex: Exception) {
            sender.sendMessage(r + "Error: " + (ex.message ?: ex.javaClass.simpleName))
            plugin.logger.log(java.util.logging.Level.WARNING, "/bwmg ${args.joinToString(" ")} failed", ex)
        }
        return true
    }

    private fun need(sender: CommandSender, rest: List<String>, n: Int, usage: String, block: (List<String>) -> Unit) {
        if (rest.size < n) sender.sendMessage(r + "Usage: /bwmg $usage") else block(rest)
    }

    private fun help(s: CommandSender, label: String) {
        s.sendMessage("$a--- BedWars1058-MultiGroup ---")
        listOf(
            "status" to "what is installed and how variants are loaded",
            "list" to "every map with its variant arenas",
            "info <map>" to "groups, configurations and the choice made for each group",
            "match <map> <group>" to "how well each configuration fits a group (dry run)",
            "create <map>" to "start a map file for an existing arena",
            "group add|remove <map> <group...>" to "play the map in more / fewer groups",
            "config create|delete <map> <config> [copy-of]" to "add or remove a configuration",
            "config alias <map> <config> <names...>" to "extra names a configuration matches",
            "fix <map> <group> <config|base|auto>" to "pin a group to a configuration",
            "set <map> <config|shared> <path> <value>" to "change an arena setting (e.g. maxInTeam 2)",
            "setloc <map> <config|shared> <path> [center] [add]" to "store your position (e.g. Team.Red.Spawn)",
            "unset <map> <config|shared> <path>" to "remove an override again",
            "drop <map> <config> <path>" to "remove a setting of the base arena (e.g. Team.Yellow)",
            "duplicates" to "arenas that are copies of one map",
            "merge all [delete-maps] [confirm]" to "turn every clear duplicate into a configuration",
            "merge <map> <copy> [config] [delete-map] [confirm]" to "turn one copy into a configuration",
            "cleanup [confirm]" to "delete the worlds of copies that were already merged",
            "reload" to "apply the map files",
        ).forEach { (cmd, desc) -> s.sendMessage("$y/$label $cmd $g- $desc") }
    }

    // ---------------------------------------------------------------------------------------------
    // Read-only
    // ---------------------------------------------------------------------------------------------

    private fun status(s: CommandSender) {
        val api = plugin.api
        s.sendMessage("$a--- BedWars1058-MultiGroup ${plugin.description.version} ---")
        s.sendMessage("${g}Hooked into BedWars1058: ${if (api != null) "${gr}yes" else "${r}no"}")
        plugin.adapter?.let { s.sendMessage("${g}Restore adapter: $w${it.displayName}") }
        val ml = plugin.bridge.plugin
        val mode = when {
            ml == null -> "this plugin copies each variant's map from BedWars1058's Cache or the server root"
            plugin.delegatesVariants() -> "BedWars1058-MapLoader ${plugin.bridge.version()} loads variants (just-in-time, from its library)"
            else -> "this plugin copies variants out of BedWars1058-MapLoader ${plugin.bridge.version()}'s library " +
                "(update MapLoader to 3.6.0+ for just-in-time variants)"
        }
        s.sendMessage("${g}Variants: $w${plugin.registry.size()} ${g}($mode)")
        if (api != null) {
            val variants = api.arenaUtil.arenas.filter { plugin.registry.isKnown(it.arenaName) }
            s.sendMessage("${g}Variant arenas listed by BedWars1058: $w${variants.size}${g}, loaded: $w${variants.count { it.world != null }}")
        }
        val warnings = plugin.lastResult?.warnings.orEmpty()
        if (warnings.isNotEmpty()) s.sendMessage("$y${warnings.size} warning(s) at the last (re)load - see the console.")
    }

    private fun list(s: CommandSender) {
        val byMap = plugin.registry.all().groupBy { it.map }
        if (byMap.isEmpty()) {
            s.sendMessage("${g}No variants. Start with $y/bwmg create <map>$g and $y/bwmg group add <map> <group>$g.")
            return
        }
        for ((map, variants) in byMap.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
            s.sendMessage("$a$map$g:")
            for (v in variants.sortedBy { it.group.lowercase(Locale.ROOT) }) {
                s.sendMessage("$g  ${w}${v.group}$g -> ${y}${v.arenaName}$g, config $w${v.match.config ?: ConfigMatcher.BASE}${state(v.arenaName)}")
            }
        }
    }

    private fun state(arena: String): String {
        val api = plugin.api ?: return ""
        api.arenaUtil.enableQueue.firstOrNull { it.arenaName.equals(arena, ignoreCase = true) }?.let { return "$g [loading]" }
        val a = api.arenaUtil.arenas.firstOrNull { it.arenaName.equals(arena, ignoreCase = true) } ?: return "$r [not registered]"
        return if (a.world == null) "$g [parked]" else "$g [${a.status}, ${a.players.size}/${a.maxPlayers}]"
    }

    private fun info(s: CommandSender, map: String) {
        val def = definition(s, map) ?: return
        s.sendMessage("$a--- ${def.name} ${if (def.enabled) "" else "${r}(disabled)"}$a---")
        val baseFile = plugin.bwFiles.arenaFile(def.name)
        val baseGroup = baseFile?.let { YamlTrees.load(it)?.get("group") }?.toString() ?: "?"
        s.sendMessage("${g}Own arena group: $w$baseGroup")
        s.sendMessage("${g}Extra groups: $w${def.groups.joinToString(", ").ifEmpty { "-" }}")
        s.sendMessage("${g}Configurations: $w${def.configs.joinToString(", ") { c -> c.name + if (c.match.isEmpty()) "" else " (${c.match.joinToString("/")})" }.ifEmpty { "-" }}")
        if (def.groupConfigs.isNotEmpty()) s.sendMessage("${g}Pinned: $w${def.groupConfigs.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        val variants = plugin.registry.ofMap(def.name)
        if (variants.isEmpty()) s.sendMessage("${g}No variant arenas (yet). $y/bwmg reload$g applies changes.")
        for (v in variants) {
            s.sendMessage("$g  ${w}${v.group}$g -> ${y}${v.arenaName}$g: $w${v.match.config ?: ConfigMatcher.BASE}$g (${v.match.reason}" +
                (if (v.match.ambiguous) ", ${y}tie with ${v.match.runnerUp}$g" else "") + ")${state(v.arenaName)}")
        }
        plugin.lastResult?.warnings?.filter { it.contains("'${def.name}'", ignoreCase = true) || it.contains("maps/${def.name}.yml", ignoreCase = true) }
            ?.forEach { s.sendMessage("$y! $it") }
    }

    private fun match(s: CommandSender, map: String, group: String) {
        val def = definition(s, map) ?: return
        val matcher = plugin.settings.matcher()
        val candidates = def.configs.map { it.candidate() }
        val result = matcher.match(group, candidates, def.explicitFor(group))
        s.sendMessage("$a--- $group on ${def.name} ---")
        s.sendMessage("${g}Words of the group: $w${matcher.words(group).joinToString(" ")}")
        for (sc in matcher.rank(group, candidates)) {
            s.sendMessage("$g  $w${sc.config}$g: ${if (sc.score >= matcher.minScore) gr else r}${sc.score}$g (${sc.reason}" +
                (if (!sc.term.equals(sc.config, ignoreCase = true)) " via '${sc.term}'" else "") + ")")
        }
        s.sendMessage("${g}Chosen: $y${result.config ?: ConfigMatcher.BASE}$g - ${result.reason} (minimum score ${matcher.minScore})")
    }

    private fun reload(s: CommandSender) {
        val report = plugin.reload() ?: return
        val res = report.result
        s.sendMessage("${gr}Reloaded: ${res.plan.variants.size} variant(s); ${res.written.size} arena file(s) written" +
            (if (res.removed.isNotEmpty()) ", removed ${res.removed.joinToString(", ")}" else "") + ".")
        if (report.registered > 0) s.sendMessage("${g}New variant arenas registered: $w${report.registered}")
        if (report.restarted.isNotEmpty()) s.sendMessage("${g}Restarted idle arenas: $w${report.restarted.joinToString(", ")}")
        if (report.waiting.isNotEmpty()) s.sendMessage("${g}Busy or parked, updated after their next game: $w${report.waiting.joinToString(", ")}")
        res.warnings.forEach { s.sendMessage("$y! $it") }
        val dupes = plugin.merger.detect().clusters.sumOf { c -> c.members.count { plugin.merger.detector().isClear(c, it) } }
        if (dupes > 0) s.sendMessage("$y$dupes duplicate arena(s) can be merged into their original map: /bwmg duplicates")
    }

    // ---------------------------------------------------------------------------------------------
    // Editing map files
    // ---------------------------------------------------------------------------------------------

    private fun create(s: CommandSender, map: String) {
        val base = baseArenaName(s, map) ?: return
        if (plugin.maps.exists(base)) {
            s.sendMessage("${y}maps/${plugin.maps.fileFor(base).name} already exists.")
            return
        }
        plugin.maps.saveTree(base, MapDefinition.toTree(MapDefinition(base)))
        s.sendMessage("${gr}Created ${plugin.maps.fileFor(base).path}. Next: $y/bwmg group add $base <group>")
    }

    private fun group(s: CommandSender, rest: List<String>) {
        val action = rest.getOrNull(0)?.lowercase(Locale.ROOT)
        if (rest.size < 3 || (action != "add" && action != "remove")) {
            s.sendMessage("${r}Usage: /bwmg group add|remove <map> <group...>")
            return
        }
        val base = baseArenaName(s, rest[1]) ?: return
        val groups = rest.drop(2)
        editMap(base) { tree ->
            val list = (tree["groups"] as? List<*>)?.filterNotNull()?.map { it.toString() }?.toMutableList() ?: ArrayList()
            if (action == "add") {
                for (grp in groups) if (list.none { it.equals(grp, ignoreCase = true) }) list += grp
            } else {
                list.removeAll { e -> groups.any { it.equals(e, ignoreCase = true) } }
            }
            tree["groups"] = list
        }
        s.sendMessage("${gr}Groups of $base: ${w}${currentDefinition(base)?.groups?.joinToString(", ").orEmpty().ifEmpty { "-" }}$gr. $y/bwmg reload$gr applies it.")
        if (action == "add") {
            val known = plugin.bwFiles.arenaGroups().orEmpty()
            val unknown = groups.filter { grp -> !grp.contains('*') && known.none { it.equals(grp, ignoreCase = true) } }
            if (unknown.isNotEmpty()) {
                s.sendMessage("$y${unknown.joinToString(", ")} ${if (unknown.size == 1) "is" else "are"} not in BedWars1058's arenaGroups yet" +
                    if (plugin.settings.registerMissingGroups) "; it is added on reload." else "; add it there (register-missing-groups is off).")
            }
        }
    }

    private fun config(s: CommandSender, rest: List<String>) {
        val action = rest.getOrNull(0)?.lowercase(Locale.ROOT)
        if (rest.size < 3 || action !in setOf("create", "delete", "alias")) {
            s.sendMessage("${r}Usage: /bwmg config create|delete <map> <config> [copy-of]  |  /bwmg config alias <map> <config> <names...>")
            return
        }
        val base = baseArenaName(s, rest[1]) ?: return
        val name = rest[2]
        if (name.equals(ConfigMatcher.BASE, ignoreCase = true) || name.equals(SHARED, ignoreCase = true)) {
            s.sendMessage("$r'$name' is reserved.")
            return
        }
        when (action) {
            "create" -> {
                val copyOf = rest.getOrNull(3)
                var ok = true
                editMap(base) { tree ->
                    val configs = section(tree, "configs")
                    if (configs.keys.any { it.equals(name, ignoreCase = true) }) {
                        s.sendMessage("${r}Configuration '$name' already exists.")
                        ok = false
                        return@editMap
                    }
                    val src = copyOf?.let { c -> configs.entries.firstOrNull { it.key.equals(c, ignoreCase = true) }?.value as? Map<*, *> }
                    if (copyOf != null && src == null) {
                        s.sendMessage("${r}There is no configuration '$copyOf' to copy.")
                        ok = false
                        return@editMap
                    }
                    configs[name] = if (src != null) TreeMerge.deepCopy(TreeMerge.asTree(src)) else linkedMapOf<String, Any?>(
                        "match" to ArrayList<String>(), "overrides" to LinkedHashMap<String, Any?>(), "remove" to ArrayList<String>(),
                    )
                }
                if (ok) s.sendMessage("${gr}Configuration '$name' created for $base. Change it with $y/bwmg set $base $name <path> <value>$gr.")
            }
            "delete" -> {
                var found = false
                editMap(base) { tree ->
                    val configs = section(tree, "configs")
                    val k = configs.keys.firstOrNull { it.equals(name, ignoreCase = true) }
                    if (k != null) {
                        configs.remove(k)
                        found = true
                    }
                }
                s.sendMessage(if (found) "${gr}Configuration '$name' deleted. $y/bwmg reload$gr applies it." else "${r}No configuration '$name'.")
            }
            "alias" -> {
                val names = rest.drop(3)
                var found = false
                editMap(base) { tree ->
                    val c = configSection(tree, name) ?: return@editMap
                    found = true
                    c["match"] = names
                }
                s.sendMessage(if (found) "${gr}'$name' also matches: ${names.joinToString(", ").ifEmpty { "-" }}" else "${r}No configuration '$name'.")
            }
        }
    }

    private fun fix(s: CommandSender, map: String, group: String, config: String) {
        val base = baseArenaName(s, map) ?: return
        var problem: String? = null
        editMap(base) { tree ->
            val pins = section(tree, "group-configs")
            pins.keys.filter { it.equals(group, ignoreCase = true) }.forEach { pins.remove(it) }
            when {
                config.equals("auto", ignoreCase = true) -> {}
                config.equals(ConfigMatcher.BASE, ignoreCase = true) -> pins[group] = ConfigMatcher.BASE
                configSection(tree, config) == null -> problem = "No configuration '$config'."
                else -> pins[group] = config
            }
        }
        if (problem != null) s.sendMessage(r + problem)
        else s.sendMessage("${gr}$group on $base: ${if (config.equals("auto", true)) "closest match" else config}. $y/bwmg reload$gr applies it.")
    }

    private fun set(s: CommandSender, map: String, config: String, path: String, raw: String) {
        val value = parseValue(raw)
        if (!checkPath(s, path)) return
        writeOverride(s, map, config, path, value)
    }

    private fun setLoc(s: CommandSender, map: String, config: String, path: String, flags: List<String>) {
        if (s !is Player) {
            s.sendMessage("${r}Only players can store a location.")
            return
        }
        if (!checkPath(s, path)) return
        val l = s.location
        val center = "center" in flags
        val x = if (center) l.blockX + 0.5 else l.x
        val yy = if (center) l.blockY.toDouble() else l.y
        val z = if (center) l.blockZ + 0.5 else l.z
        // the same format as BedWars1058's own setup (ConfigManager#stringLocationArenaFormat), world independent
        val loc = "$x,$yy,$z,${l.yaw.toDouble()},${l.pitch.toDouble()}"
        if ("add" in flags) {
            val base = baseArenaName(s, map) ?: return
            var current: List<String> = emptyList()
            editMap(base) { tree ->
                val target = targetTree(tree, config) ?: return@editMap
                val existing = TreeMerge.get(target, path) as? List<*>
                // start from what the variant currently has (the base arena's list) so "add" really adds
                val start = existing?.map { it.toString() } ?: (baseValue(base, path) as? List<*>)?.map { it.toString() } ?: emptyList()
                current = start + loc
                TreeMerge.set(target, path, current)
            }
            s.sendMessage("${gr}$path of $base/$config now has ${current.size} location(s).")
        } else {
            writeOverride(s, map, config, path, loc)
        }
    }

    private fun unset(s: CommandSender, map: String, config: String, path: String) {
        val base = baseArenaName(s, map) ?: return
        var removed = false
        editMap(base) { tree ->
            val target = targetTree(tree, config) ?: return@editMap
            removed = TreeMerge.remove(target, path)
        }
        s.sendMessage(if (removed) "${gr}Removed $path from $base/$config." else "${y}$base/$config does not override $path.")
    }

    private fun drop(s: CommandSender, map: String, config: String, path: String) {
        val base = baseArenaName(s, map) ?: return
        var ok = false
        editMap(base) { tree ->
            val c = configSection(tree, config) ?: return@editMap
            val list = (c["remove"] as? List<*>)?.filterNotNull()?.map { it.toString() }?.toMutableList() ?: ArrayList()
            if (path !in list) list += path
            c["remove"] = list
            ok = true
        }
        s.sendMessage(if (ok) "${gr}$base/$config drops $path of the base arena. $y/bwmg reload$gr applies it." else "${r}No configuration '$config'.")
    }

    private fun writeOverride(s: CommandSender, map: String, config: String, path: String, value: Any?) {
        val base = baseArenaName(s, map) ?: return
        var ok = false
        editMap(base) { tree ->
            val target = targetTree(tree, config) ?: return@editMap
            TreeMerge.set(target, path, value)
            ok = true
        }
        if (ok) s.sendMessage("${gr}$base/$config: $path = $w$value$gr. $y/bwmg reload$gr applies it.")
        else s.sendMessage("${r}No configuration '$config'. Create it with /bwmg config create $base $config")
    }

    private fun checkPath(s: CommandSender, path: String): Boolean {
        val first = path.substringBefore('.')
        if (first.equals("group", ignoreCase = true) || first.equals(VariantPlanner.MARKER_SECTION, ignoreCase = true) || path.isBlank()) {
            s.sendMessage("$r'$path' is managed by BedWars1058-MultiGroup and cannot be overridden.")
            return false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Duplicates: copies of a map become configurations of the original
    // ---------------------------------------------------------------------------------------------

    private fun duplicates(s: CommandSender) {
        val det = plugin.merger.detector()
        val d = plugin.merger.detect()
        if (d.clusters.isEmpty()) {
            s.sendMessage("${gr}No duplicate arenas found.")
            return
        }
        for (c in d.clusters) {
            if (c.base == null) {
                s.sendMessage("$y! ${c.members.joinToString(", ") { "${it.name} (${it.group})" }}$g: ${c.note}")
                continue
            }
            s.sendMessage("$a${c.base}$g (group ${c.baseGroup}):")
            for (m in c.members) {
                val status = when {
                    det.isClear(c, m) -> "${gr}clear - /bwmg merge all"
                    m.group.equals(c.baseGroup, ignoreCase = true) -> "${y}same group, not mergeable"
                    else -> "${y}different layout? check, then /bwmg merge ${c.base} ${m.name}"
                }
                s.sendMessage("$g  ${w}${m.name}$g (group ${m.group}), ${Merger.percent(m.similarity)} alike: $status")
            }
        }
    }

    private fun merge(s: CommandSender, args: List<String>) {
        if (args.isEmpty()) {
            s.sendMessage("${r}Usage: /bwmg merge all [delete-maps] [confirm]  |  /bwmg merge <map> <copy> [config] [delete-map] [confirm]")
            return
        }
        if (args[0].equals("all", ignoreCase = true)) mergeAll(s, args.drop(1).map { it.lowercase(Locale.ROOT) }) else mergePair(s, args)
    }

    private fun isEnabled(arena: String): Boolean {
        val api = plugin.api ?: return false
        return api.arenaUtil.arenas.any { it.arenaName.equals(arena, ignoreCase = true) } ||
            api.arenaUtil.enableQueue.any { it.arenaName.equals(arena, ignoreCase = true) }
    }

    private fun mergePair(s: CommandSender, args: List<String>) {
        if (args.size < 2) {
            s.sendMessage("${r}Usage: /bwmg merge <map> <copy> [config] [delete-map] [confirm]")
            return
        }
        val flags = args.drop(2).map { it.lowercase(Locale.ROOT) }
        val confirm = "confirm" in flags
        val deleteMap = "delete-map" in flags || "delete-maps" in flags
        val config = args.drop(2).firstOrNull { it.lowercase(Locale.ROOT) !in FLAGS }
        val plan = try {
            plugin.merger.prepare(args[0], args[1], config)
        } catch (ex: Merger.MergeException) {
            s.sendMessage(r + (ex.message ?: "Cannot merge."))
            return
        }
        describe(s, plan)
        s.sendMessage(if (deleteMap) "${y}The world of ${plan.dup} is deleted through the restore adapter (${plugin.api?.restoreAdapter?.displayName ?: "?"})."
        else "${g}The world of ${plan.dup} is kept (add ${y}delete-map$g to free its space, or use $y/bwmg cleanup$g later).")
        if (!confirm) {
            s.sendMessage("${g}Run it with $y/bwmg merge ${plan.base} ${plan.dup} ${plan.configName}${if (deleteMap) " delete-map" else ""} confirm")
            return
        }
        if (isEnabled(plan.dup)) {
            s.sendMessage("${r}${plan.dup} is still enabled. Disable it first with /bw disableArena ${plan.dup} (after its game), then merge it.")
            return
        }
        val backup = plugin.merger.apply(plan)
        s.sendMessage("${gr}Merged ${plan.dup} into ${plan.base}. Its arena file is in ${backup.path}.")
        plugin.merger.setAsideClashingWorld(plan.variantName)?.let {
            s.sendMessage("${y}The world folder ${plan.variantName} was moved to ${it.path}; delete it to free its space.")
        }
        if (deleteMap) deleteWorldOf(s, plan.dup)
        reload(s)
    }

    private fun describe(s: CommandSender, plan: Merger.Plan) {
        s.sendMessage("$a--- merge ${plan.dup} into ${plan.base} ---")
        s.sendMessage("${g}Group ${w}${plan.group}$g -> variant arena $y${plan.variantName}$g, configuration $w${plan.configName}$g " +
            "(${Merger.countLeaves(plan.diff.overrides)} changed setting(s), ${plan.diff.removals.size} removed)")
        s.sendMessage("${g}${plan.dup}.yml is moved to plugins/${plugin.name}/backups/arenas/.")
    }

    private fun mergeAll(s: CommandSender, flags: List<String>) {
        val confirm = "confirm" in flags
        val deleteMaps = "delete-maps" in flags || "delete-map" in flags
        val det = plugin.merger.detector()
        val todo = ArrayList<Pair<String, String>>()
        val notes = ArrayList<String>()
        for (c in plugin.merger.detect().clusters) {
            if (c.base == null) {
                notes += "${c.members.joinToString(", ") { it.name }}: ${c.note}"
                continue
            }
            for (m in c.members) {
                if (det.isClear(c, m)) todo += c.base to m.name
                else notes += "${m.name} (${Merger.percent(m.similarity)} alike ${c.base}) is not clear; /bwmg duplicates"
            }
        }
        if (todo.isEmpty()) {
            s.sendMessage("${y}No clear duplicates to merge.")
            notes.forEach { s.sendMessage("$g- $it") }
            return
        }
        var done = 0
        for ((base, dup) in todo) {
            val plan = try {
                plugin.merger.prepare(base, dup)
            } catch (ex: Merger.MergeException) {
                s.sendMessage("$y$dup: ${ex.message}")
                continue
            }
            if (!confirm) {
                s.sendMessage("$g- ${w}$dup$g -> $a$base$g: group ${plan.group}, configuration ${plan.configName}, variant ${y}${plan.variantName}")
                continue
            }
            if (isEnabled(dup)) {
                s.sendMessage("$y$dup is still enabled: /bw disableArena $dup, then run this again.")
                continue
            }
            plugin.merger.apply(plan)
            plugin.merger.setAsideClashingWorld(plan.variantName)
            if (deleteMaps) deleteWorldOf(s, dup)
            s.sendMessage("${gr}Merged $dup into $base (configuration ${plan.configName}).")
            done++
        }
        notes.forEach { s.sendMessage("$g- $it") }
        if (!confirm) {
            s.sendMessage("${g}Apply with $y/bwmg merge all${if (deleteMaps) " delete-maps" else ""} confirm$g. Arena files go to backups/.")
        } else if (done > 0) {
            reload(s)
        }
    }

    /** Deletes the world called [arena] through the restore adapter that stores it (never an alias of another map). */
    private fun deleteWorldOf(s: CommandSender, arena: String): Boolean {
        val adapter = plugin.adapter
        val restore = adapter?.delegate ?: plugin.api?.restoreAdapter
        if (restore == null) {
            s.sendMessage("${y}BedWars1058 is not running: delete the world of $arena by hand.")
            return false
        }
        if (plugin.server.getWorld(arena) != null) {
            s.sendMessage("${y}The world $arena is loaded right now; use /bwmg cleanup after it is unloaded.")
            return false
        }
        if (!restore.isWorld(arena)) return false
        restore.deleteWorld(arena)
        plugin.bwFiles.cacheDir.listFiles()?.filter { it.name.equals("$arena.zip", ignoreCase = true) }?.forEach { it.delete() }
        s.sendMessage("${gr}Deleted the world of $arena (${restore.displayName}).")
        return true
    }

    /** Worlds of arenas that were merged earlier (`merged-from` in the map files) and are still stored. */
    private fun cleanup(s: CommandSender, args: List<String>) {
        val confirm = args.any { it.equals("confirm", ignoreCase = true) }
        val restore = plugin.adapter?.delegate
        if (restore == null) {
            s.sendMessage("${r}BedWars1058 is not running yet.")
            return
        }
        val leftovers = ArrayList<Pair<String, String>>() // map -> merged-from
        for ((_, f) in plugin.maps.files()) {
            val tree = YamlTrees.load(f) ?: continue
            val configs = tree["configs"] as? Map<*, *> ?: continue
            for ((_, v) in configs) {
                val from = (v as? Map<*, *>)?.get("merged-from")?.toString() ?: continue
                leftovers += f.name.removeSuffix(".yml") to from
            }
        }
        val present = leftovers.filter { (map, dup) ->
            !dup.equals(map, ignoreCase = true) && plugin.bwFiles.arenaFile(dup) == null && restore.isWorld(dup)
        }
        if (present.isEmpty()) {
            s.sendMessage("${gr}Nothing to clean up: no stored world belongs to a merged copy.")
            return
        }
        for ((map, dup) in present) {
            if (!confirm) s.sendMessage("$g- world ${w}$dup$g (merged into $a$map$g)")
            else deleteWorldOf(s, dup)
        }
        if (!confirm) s.sendMessage("${g}Delete them with $y/bwmg cleanup confirm$g. Their arenas stay in backups/arenas/; the maps live on as $a/bwmg$g configurations.")
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** The real name of a BedWars1058 arena that can be a map (exists, not generated), or null after telling [s]. */
    private fun baseArenaName(s: CommandSender, map: String): String? {
        val f = plugin.bwFiles.arenaFile(map)
        if (f == null) {
            s.sendMessage("${r}There is no arena '$map' in plugins/BedWars1058/Arenas.")
            return null
        }
        val t = YamlTrees.load(f)
        if (t != null && plugin.bwFiles.isGenerated(t)) {
            s.sendMessage("${r}'$map' is a generated variant. Use its map: ${(t[VariantPlanner.MARKER_SECTION] as? Map<*, *>)?.get("map")}")
            return null
        }
        return f.name.removeSuffix(".yml")
    }

    private fun definition(s: CommandSender, map: String): MapDefinition? {
        val d = currentDefinition(map)
        if (d == null) s.sendMessage("${r}There is no map file for '$map'. Create one with /bwmg create $map")
        return d
    }

    private fun currentDefinition(map: String): MapDefinition? {
        if (!plugin.maps.exists(map)) return null
        val f = plugin.maps.fileFor(map)
        val tree = YamlTrees.load(f) ?: return null
        return MapDefinition.parse(f.name.removeSuffix(".yml"), tree, ArrayList())
    }

    private fun editMap(map: String, block: (LinkedHashMap<String, Any?>) -> Unit) {
        val tree = plugin.maps.loadTree(map)
        block(tree)
        plugin.maps.saveTree(map, tree)
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(tree: MutableMap<String, Any?>, key: String): MutableMap<String, Any?> {
        val cur = tree[key]
        if (cur is MutableMap<*, *>) return cur as MutableMap<String, Any?>
        val m = if (cur is Map<*, *>) TreeMerge.deepCopy(TreeMerge.asTree(cur)) else LinkedHashMap()
        tree[key] = m
        return m
    }

    private fun configSection(tree: MutableMap<String, Any?>, name: String): MutableMap<String, Any?>? {
        val configs = section(tree, "configs")
        val k = configs.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        if (configs[k] == null) configs[k] = LinkedHashMap<String, Any?>()
        return section(configs, k)
    }

    /** The tree a `set`/`unset` writes to: `shared`, or `configs.<name>.overrides`. */
    private fun targetTree(tree: MutableMap<String, Any?>, config: String): MutableMap<String, Any?>? =
        if (config.equals(SHARED, ignoreCase = true)) section(tree, "shared") else configSection(tree, config)?.let { section(it, "overrides") }

    private fun baseValue(base: String, path: String): Any? =
        plugin.bwFiles.arenaFile(base)?.let { YamlTrees.load(it) }?.let { TreeMerge.get(it, path) }

    /** YAML semantics for a typed value: `2`, `true`, `1.5`, `[a, b]`, `"text"`. */
    private fun parseValue(raw: String): Any? = try {
        val y = YamlConfiguration()
        y.loadFromString("v: $raw")
        y.get("v")?.let { v -> if (v is org.bukkit.configuration.ConfigurationSection) YamlTrees.toTree(v) else v } ?: raw
    } catch (ex: Exception) {
        raw
    }

    // ---------------------------------------------------------------------------------------------
    // Tab completion
    // ---------------------------------------------------------------------------------------------

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val subs = listOf("status", "list", "info", "match", "reload", "create", "group", "config", "fix", "set", "setloc", "unset", "drop", "merge", "duplicates", "cleanup", "help")
        fun filter(options: Collection<String>, prefix: String) =
            options.filter { it.startsWith(prefix, ignoreCase = true) }.distinct().sorted()

        val sub = args.getOrNull(0)?.lowercase(Locale.ROOT) ?: return subs
        if (args.size == 1) return filter(subs, args[0])
        val last = args.last()
        val maps = plugin.maps.files().values.map { it.name.removeSuffix(".yml") }
        val arenas = plugin.bwFiles.arenaFiles().keys.filter { !plugin.registry.isKnown(it) }
        val groups = plugin.bwFiles.arenaGroups().orEmpty()
        fun configsOf(map: String) = currentDefinition(map)?.configs?.map { it.name }.orEmpty()

        return when (sub) {
            "info" -> if (args.size == 2) filter(maps, last) else emptyList()
            "create" -> if (args.size == 2) filter(arenas - maps.map { it.lowercase(Locale.ROOT) }.toSet(), last) else emptyList()
            "match" -> when (args.size) {
                2 -> filter(maps, last)
                3 -> filter(groups, last)
                else -> emptyList()
            }
            "group" -> when (args.size) {
                2 -> filter(listOf("add", "remove"), last)
                3 -> filter(maps, last)
                else -> if (args[1].equals("remove", true)) filter(currentDefinition(args[2])?.groups.orEmpty(), last) else filter(groups, last)
            }
            "config" -> when (args.size) {
                2 -> filter(listOf("create", "delete", "alias"), last)
                3 -> filter(maps, last)
                4 -> if (args[1].equals("create", true)) filter(groups.map { it.lowercase(Locale.ROOT) }, last) else filter(configsOf(args[2]), last)
                5 -> if (args[1].equals("create", true)) filter(configsOf(args[2]), last) else emptyList()
                else -> emptyList()
            }
            "fix" -> when (args.size) {
                2 -> filter(maps, last)
                3 -> filter(currentDefinition(args[1])?.groups.orEmpty(), last)
                4 -> filter(configsOf(args[1]) + listOf("base", "auto"), last)
                else -> emptyList()
            }
            "set", "setloc", "unset", "drop" -> when (args.size) {
                2 -> filter(maps, last)
                3 -> filter(configsOf(args[1]) + if (sub == "drop") emptyList() else listOf(SHARED), last)
                4 -> filter(PATH_HINTS, last)
                else -> if (sub == "setloc") filter(listOf("center", "add"), last) else emptyList()
            }
            "merge", "import" -> when {
                args.size == 2 -> filter(arenas + "all", last)
                args[1].equals("all", true) -> filter(listOf("delete-maps", "confirm"), last)
                args.size == 3 -> filter(arenas.filter { !it.equals(args[1], ignoreCase = true) }, last)
                else -> filter(listOf("delete-map", "confirm"), last)
            }
            "cleanup" -> filter(listOf("confirm"), last)
            else -> emptyList()
        }
    }

    companion object {
        const val SHARED = "shared"
        private val FLAGS = setOf("confirm", "delete-map", "delete-maps")
        private val PATH_HINTS = listOf(
            "maxInTeam", "minPlayers", "allowSpectate", "display-name", "worldBorder", "Team.", "generator.Diamond", "generator.Emerald",
            "waiting.Loc", "waiting.Pos1", "waiting.Pos2", "island-radius", "max-build-y", "y-kill-height",
        )
    }
}
