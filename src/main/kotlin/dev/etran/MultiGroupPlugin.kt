package dev.etran

import com.andrei1058.bedwars.api.BedWars
import com.andrei1058.bedwars.api.arena.GameState
import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.events.server.SetupSessionCloseEvent
import com.andrei1058.bedwars.api.server.ServerType
import dev.etran.bedwars.BedWarsFiles
import dev.etran.bedwars.GuardListener
import dev.etran.bedwars.MultiGroupAdapter
import dev.etran.bedwars.VariantWarningFilter
import dev.etran.command.MultiGroupCommand
import dev.etran.core.VariantRegistry
import dev.etran.integration.MapLoaderBridge
import dev.etran.world.VariantWorlds
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.logging.Logger

class MultiGroupPlugin : JavaPlugin() {

    val registry = VariantRegistry()

    @Volatile
    lateinit var settings: Settings
        private set

    lateinit var bwFiles: BedWarsFiles
        private set
    lateinit var maps: MapStore
        private set
    lateinit var bridge: MapLoaderBridge
        private set

    /** Set once BedWars1058 is enabled and the adapter is installed. */
    var api: BedWars? = null
        private set
    var adapter: MultiGroupAdapter? = null
        private set
    var worlds: VariantWorlds? = null
        private set
    var lastResult: Generator.Result? = null
        private set

    private var bwPlugin: Plugin? = null
    private var filteredLogger: Logger? = null
    private var ourFilter: VariantWarningFilter? = null

    // ---------------------------------------------------------------------------------------------
    // Startup
    //
    // onLoad   (before BedWars1058's onLoad, thanks to loadbefore): write the variant arena files and add missing
    //          groups to BedWars1058's config.yml, so BedWars1058 reads them on this very start.
    // onEnable (before BedWars1058's onEnable): hook BedWars1058-MapLoader, silence BedWars' warnings for variants.
    // hook     (BedWars1058 finished enabling): put the adapter in front of BedWars' restore adapter, load variants.
    // ---------------------------------------------------------------------------------------------

    override fun onLoad() {
        saveDefaultFiles()
        val warnings = ArrayList<String>()
        settings = readSettings(warnings)
        warnings.forEach { logger.warning(it) }
        maps = MapStore(File(dataFolder, "maps"))
        bridge = MapLoaderBridge(this, logger)

        val bw = server.pluginManager.getPlugin(BEDWARS) ?: return
        bwPlugin = bw
        bwFiles = BedWarsFiles(bw.dataFolder, logger)
        regenerate { groups -> addGroupsOnDisk(groups) }
    }

    override fun onEnable() {
        val bw = bwPlugin
        if (bw == null) {
            logger.severe("BedWars1058 is not installed. Disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }

        if (bridge.plugin != null) {
            if (bridge.registerAliases { registry.baseOf(it) }) {
                logger.info("BedWars1058-MapLoader ${bridge.version()} found: variants are loaded by it, just-in-time, from its map library.")
            } else {
                logger.warning("BedWars1058-MapLoader ${bridge.version()} has no alias hook (added in 3.6.0). Variants are loaded by this plugin " +
                    "from MapLoader's library and are not parked. Update BedWars1058-MapLoader to get just-in-time loading for variants.")
            }
        }

        installWarningFilter(bw)

        val command = MultiGroupCommand(this)
        getCommand("bwmg")?.let {
            it.setExecutor(command)
            it.tabCompleter = command
        }
        server.pluginManager.registerEvents(GuardListener(registry, { settings }, { settings.bedwarsCommands }), this)

        if (bw.isEnabled) {
            hook(bw)
        } else {
            server.pluginManager.registerEvents(object : Listener {
                // MONITOR: after BedWars1058-MapLoader (NORMAL) has installed its adapter, so it is the one wrapped
                @EventHandler(priority = EventPriority.MONITOR)
                fun onEnable(e: PluginEnableEvent) {
                    if (e.plugin !== bw) return
                    HandlerList.unregisterAll(this)
                    hook(bw)
                }
            }, this)
        }
    }

    private fun hook(bw: Plugin) {
        if (adapter != null) return
        val bedwars = server.servicesManager.getRegistration(BedWars::class.java)?.provider
        if (bedwars == null) {
            logger.severe("BedWars1058 did not register its API. Variants stay unavailable.")
            return
        }
        try {
            val w = VariantWorlds(this, bw, bwFiles, bridge, { settings }, logger)
            w.api = bedwars
            w.sweep()
            val current = bedwars.restoreAdapter
            val a = MultiGroupAdapter(this, bw, current, bedwars, registry, w, { settings }, { delegatesVariants() }, logger)
            bedwars.restoreAdapter = a
            api = bedwars
            worlds = w
            adapter = a
            logger.info("Hooked into BedWars1058 in front of '${current.displayName}'. ${registry.size()} variant arena(s).")
        } catch (ex: IllegalAccessError) {
            logger.severe("BedWars1058 refused the restore adapter (${ex.message}). Restart the server instead of reloading plugins.")
            return
        } catch (ex: LinkageError) {
            logger.severe("This BedWars1058 version is not compatible (${ex.javaClass.simpleName}: ${ex.message}).")
            return
        }
        server.pluginManager.registerEvents(object : Listener {
            /** The map itself was edited: its variants inherit its settings, so regenerate them once BedWars saved them. */
            @EventHandler(priority = EventPriority.MONITOR)
            fun onSetupClosed(e: SetupSessionCloseEvent) {
                val map = e.setupSession.worldName
                if (registry.ofMap(map).isEmpty()) return
                server.scheduler.runTaskLater(this@MultiGroupPlugin, Runnable {
                    logger.info("The map '$map' was set up; updating its variants.")
                    reload()
                }, 60L)
            }
        }, this)
        // BedWars1058-MapLoader registers every arena BedWars skipped at startup (variants included) by itself.
        if (!bridge.isActive()) registerMissing(registry.all().map { it.arenaName })
    }

    /** True when BedWars1058-MapLoader resolves and loads the variants (alias hook present and MapLoader running). */
    fun delegatesVariants(): Boolean = bridge.aliasHookActive && bridge.isActive()

    override fun onDisable() {
        removeWarningFilter()
        bridge.unregisterAliases()
        worlds?.shutdown()
        // The adapter stays installed: BedWars1058 may still disable arenas through it while the server stops.
    }

    // ---------------------------------------------------------------------------------------------
    // Generation / reload
    // ---------------------------------------------------------------------------------------------

    private fun regenerate(groupsAdder: (List<String>) -> List<String>): Generator.Result {
        val gen = Generator(bwFiles, maps, registry, logger)
        val r = gen.run(settings, groupsAdder)
        lastResult = r
        r.warnings.forEach { logger.warning(it) }
        val variantCount = r.plan.variants.size
        val mapCount = r.plan.variants.map { it.map.lowercase(Locale.ROOT) }.distinct().size
        logger.info("$variantCount variant arena(s) for $mapCount map(s)" +
            (if (r.written.isNotEmpty()) "; wrote ${r.written.size} arena file(s)" else "") +
            (if (r.removed.isNotEmpty()) "; removed ${r.removed.joinToString(", ")}" else "") + ".")
        if (settings.debug) {
            for (v in r.plan.variants) logger.info("[debug] ${v.arenaName}: map ${v.map}, group ${v.group}, config ${v.match.config ?: "base"} (${v.match.reason})")
        }
        return r
    }

    data class ReloadReport(val result: Generator.Result, val registered: Int, val restarted: List<String>, val waiting: List<String>)

    /** `/bwmg reload`: re-read everything, rewrite the arena files and bring the running arenas in line. */
    fun reload(): ReloadReport? {
        val warnings = ArrayList<String>()
        reloadConfig()
        settings = readSettings(warnings)
        warnings.forEach { logger.warning(it) }
        val bedwars = api
        val before = HashSet<String>()
        if (bedwars != null) {
            bedwars.arenaUtil.arenas.forEach { before += it.arenaName.lowercase(Locale.ROOT) }
            bedwars.arenaUtil.enableQueue.forEach { before += it.arenaName.lowercase(Locale.ROOT) }
        }
        // variants that existed before (an arena object may still exist for them, possibly parked by MapLoader)
        val known = (registry.all() + registry.retired()).map { it.key }.toSet()
        val r = regenerate { groups -> if (bedwars != null) addGroupsLive(bedwars, groups) else addGroupsOnDisk(groups) }
        if (bedwars == null) return ReloadReport(r, 0, emptyList(), emptyList())

        val restarted = ArrayList<String>()
        val waiting = ArrayList<String>()
        // removed or changed variants that are loaded and idle: restart them now (retired ones are then wound down,
        // changed ones come back with the new configuration). Busy ones follow when their game ends.
        val affected = registry.retired().map { it.key }.toSet() + r.changed
        for (a in bedwars.arenaUtil.arenas.toList()) {
            val key = a.arenaName.lowercase(Locale.ROOT)
            if (key !in affected) continue
            if (a.world == null) {
                waiting += a.arenaName // parked by BedWars1058-MapLoader: picks the change up after its next game
                continue
            }
            if (isIdle(a)) {
                try {
                    a.restart()
                    restarted += a.arenaName
                } catch (t: Throwable) {
                    logger.warning("Could not restart '${a.arenaName}': ${t.message}")
                }
            } else {
                waiting += a.arenaName
            }
        }
        val fresh = registry.all().filter { it.key !in known && it.key !in before }.map { it.arenaName }
        registerMissing(fresh)
        return ReloadReport(r, fresh.size, restarted, waiting)
    }

    private fun isIdle(a: IArena): Boolean =
        a.status == GameState.waiting && a.players.isEmpty() && a.spectators.isEmpty()

    /** Creates BedWars1058 arenas for [names] (a few per tick) unless they already exist. */
    private fun registerMissing(names: List<String>) {
        val bedwars = api ?: return
        if (names.isEmpty()) return
        if (bedwars.serverType == ServerType.BUNGEE && !bedwars.isAutoScale) {
            logger.info("BUNGEE server without auto-scale: BedWars1058 only runs the one arena it picked, so no variants are added.")
            return
        }
        val todo = ArrayDeque(names)
        object : BukkitRunnable() {
            override fun run() {
                if (!isEnabled) {
                    cancel()
                    return
                }
                var n = 0
                while (n < settings.registerPerTick && todo.isNotEmpty()) {
                    val name = todo.poll()
                    n++
                    if (!registry.isVariant(name) || exists(bedwars, name)) continue
                    try {
                        bedwars.arenaUtil.loadArena(name, null)
                    } catch (t: Throwable) {
                        logger.warning("Could not register variant arena '$name': ${t.message}")
                    }
                }
                if (todo.isEmpty()) cancel()
            }
        }.runTaskTimer(this, 1L, 1L)
    }

    private fun exists(bedwars: BedWars, name: String): Boolean =
        bedwars.arenaUtil.arenas.any { it.arenaName.equals(name, ignoreCase = true) } ||
            bedwars.arenaUtil.enableQueue.any { it.arenaName.equals(name, ignoreCase = true) }

    // --- arenaGroups ---

    private fun addGroupsOnDisk(groups: List<String>): List<String> {
        val before = bwFiles.arenaGroups() ?: emptyList()
        if (!bwFiles.addArenaGroupsOnDisk(groups)) return emptyList()
        return groups.filter { g -> before.none { it.equals(g, ignoreCase = true) } }
    }

    /** BedWars1058 is running: change its in-memory configuration (and save it), so group joins work right away. */
    private fun addGroupsLive(bedwars: BedWars, groups: List<String>): List<String> {
        return try {
            val cfg = bedwars.configs.mainConfig
            val yml = cfg.yml
            val current = yml.getStringList("arenaGroups")
            val added = groups.filter { g -> current.none { it.equals(g, ignoreCase = true) } }
            if (added.isEmpty()) return emptyList()
            yml.set("arenaGroups", current + added)
            cfg.save()
            added
        } catch (t: Throwable) {
            logger.warning("Could not add groups to BedWars1058's arenaGroups: ${t.message}")
            emptyList()
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun readSettings(warnings: MutableList<String>): Settings {
        val yml = YamlConfiguration()
        try {
            yml.load(File(dataFolder, "config.yml"))
        } catch (ex: Exception) {
            warnings += "config.yml could not be read (${ex.message}); using the defaults."
        }
        getResource("config.yml")?.let { res ->
            res.reader(Charsets.UTF_8).use { yml.setDefaults(YamlConfiguration.loadConfiguration(it)) }
        }
        return Settings.read(yml, warnings)
    }

    private fun saveDefaultFiles() {
        if (!File(dataFolder, "config.yml").exists()) saveResource("config.yml", false)
        if (!File(dataFolder, "maps/_example.yml").exists()) saveResource("maps/_example.yml", false)
    }

    private fun installWarningFilter(bw: Plugin) {
        val target = bw.logger
        val f = VariantWarningFilter({ registry.isKnown(it) }, target.filter)
        target.filter = f
        filteredLogger = target
        ourFilter = f
    }

    private fun removeWarningFilter() {
        val target = filteredLogger ?: return
        val f = ourFilter ?: return
        if (target.filter === f) target.filter = f.previous
        filteredLogger = null
        ourFilter = null
    }

    companion object {
        const val BEDWARS = "BedWars1058"
    }
}
