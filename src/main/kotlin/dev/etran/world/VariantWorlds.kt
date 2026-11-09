package dev.etran.world

import com.andrei1058.bedwars.api.BedWars
import com.andrei1058.bedwars.api.arena.IArena
import dev.etran.Settings
import dev.etran.bedwars.BedWarsFiles
import dev.etran.integration.MapLoaderBridge
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.generator.ChunkGenerator
import org.bukkit.plugin.Plugin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Brings variant arena worlds online when nobody else can: on stock BedWars1058 (and with BedWars1058-MapLoader
 * versions without the alias hook).
 *
 * A variant's world is a disposable copy of its base map, made in the server root only while the variant is loaded:
 * `<root>/<variant world>` with a [FileOps.MARKER] file. Only folders carrying that marker are ever deleted, so a
 * real map can never be lost. The map itself exists once:
 *  1. in BedWars1058-MapLoader's library, when that plugin is installed,
 *  2. otherwise as BedWars1058's pristine cache zip `plugins/BedWars1058/Cache/<map>.zip`,
 *  3. otherwise as the world folder `<root>/<map>`.
 */
class VariantWorlds(
    private val plugin: Plugin,
    private val bedwarsPlugin: Plugin,
    private val files: BedWarsFiles,
    private val bridge: MapLoaderBridge,
    private val settings: () -> Settings,
    private val log: Logger,
) {
    sealed class Source(val path: Path) {
        class Folder(path: Path) : Source(path)
        class Zip(path: Path) : Source(path)

        override fun toString(): String = path.toString()
    }

    private val serverRoot: File get() = plugin.server.worldContainer

    private val owned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val generator: ChunkGenerator? by lazy { VoidGenerators.create(log) }

    private val io: ExecutorService = Executors.newFixedThreadPool(settings().world.ioThreads, object : ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable): Thread = Thread(r, "BedWars1058-MultiGroup-IO-" + n.incrementAndGet()).also { it.isDaemon = true }
    })

    lateinit var api: BedWars

    // ---------------------------------------------------------------------------------------------
    // Sources
    // ---------------------------------------------------------------------------------------------

    /** Where the base map [map] can be copied from, or null if it exists nowhere. */
    fun findSource(map: String): Source? {
        if (bridge.plugin != null) {
            bridge.findInLibrary(map)?.let { return if (Files.isDirectory(it)) Source.Folder(it) else Source.Zip(it) }
        }
        val lower = map.lowercase(Locale.ROOT)
        files.cacheDir.listFiles()?.firstOrNull { it.isFile && it.name.lowercase(Locale.ROOT) == "$lower.zip" }?.let {
            return Source.Zip(it.toPath())
        }
        serverRoot.listFiles()?.firstOrNull {
            it.isDirectory && it.name.lowercase(Locale.ROOT) == lower && FileOps.isWorldFolder(it) && !File(it, FileOps.MARKER).exists()
        }?.let { return Source.Folder(it.toPath()) }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Load
    // ---------------------------------------------------------------------------------------------

    /** Loads the world of variant arena [a] (a copy of [map]). BedWars1058 initialises the arena on WorldLoadEvent. */
    fun load(a: IArena, map: String) {
        val worldName = a.worldName
        val existing = Bukkit.getWorld(worldName)
        if (existing != null) {
            sync { a.init(existing) }
            return
        }
        if (worldName.equals(map, ignoreCase = true)) {
            fail(a, "its world name is the name of its own base map")
            return
        }
        val source = findSource(map)
        if (source == null) {
            fail(a, "its map '$map' was found neither in BedWars1058-MapLoader's library, nor in plugins/BedWars1058/Cache, nor in the server root")
            return
        }
        val protectedNames = Bukkit.getWorlds().map { it.name.lowercase(Locale.ROOT) }.toSet()
        val deleteLevelDat = settings().world.deleteLevelDat
        debug("Copying map '$map' from $source for arena '${a.arenaName}' (world '$worldName')")
        io.execute {
            val error = try {
                materialize(source, worldName, protectedNames, deleteLevelDat)
                null
            } catch (t: Throwable) {
                t
            }
            sync {
                if (error != null) {
                    log.log(Level.SEVERE, "Could not prepare the world of arena '${a.arenaName}'", error)
                    fail(a, error.message ?: error.javaClass.simpleName)
                } else {
                    create(a)
                }
            }
        }
    }

    /** Blocking: copies [source] into `<root>/<worldName>`. Never touches a folder this plugin did not create. */
    private fun materialize(source: Source, worldName: String, protectedNames: Set<String>, deleteLevelDat: Boolean) {
        require(SAFE_NAME.matches(worldName)) { "Illegal world name '$worldName'" }
        check(worldName.lowercase(Locale.ROOT) !in protectedNames) { "A world called '$worldName' is already loaded" }
        val target = WorldLayout.worldDir(worldName, serverRoot)
        if (target.exists()) {
            check(File(target, FileOps.MARKER).exists()) {
                "A folder called '$worldName' already exists in the server root and was not created by BedWars1058-MultiGroup; it was not touched"
            }
            FileOps.deleteRecursively(target.toPath())
        }
        target.mkdirs()
        // marker first: a half-written copy left behind by a crash is still recognised as ours
        File(target, FileOps.MARKER).writeText(System.currentTimeMillis().toString())
        owned += worldName.lowercase(Locale.ROOT)
        val skip = FileOps.runtimeSkip(deleteLevelDat)
        when (source) {
            is Source.Folder -> FileOps.copyDirectory(source.path, target.toPath(), skip)
            is Source.Zip -> FileOps.unzip(source.path, target.toPath(), skip)
        }
        if (WorldLayout.normalize(target)) debug("Converted the world layout of '$worldName' for this server version")
        check(File(target, "region").isDirectory) {
            "The map at $source does not contain a world (no region folder)"
        }
    }

    private fun create(a: IArena) {
        val world = try {
            createWorld(a.worldName)
        } catch (t: Throwable) {
            log.log(Level.SEVERE, "Bukkit could not create the world of arena '${a.arenaName}'", t)
            null
        }
        if (world == null) {
            fail(a, "Bukkit could not create the world")
            return
        }
        // BedWars1058 normally initialised the arena in its WorldLoadEvent listener already.
        if (api.arenaUtil.enableQueue.contains(a)) {
            debug("BedWars1058 did not pick up world '${world.name}' by itself; initialising '${a.arenaName}'")
            a.init(world)
        }
    }

    private fun createWorld(worldName: String): World? {
        val s = settings().world
        val creator = WorldCreator(worldName)
        creator.generateStructures(false)
        if (s.voidGenerator) generator?.let { creator.generator(it) }
        val world = Bukkit.createWorld(creator) ?: return null
        if (s.keepSpawnInMemory) {
            try {
                @Suppress("DEPRECATION")
                world.keepSpawnInMemory = true
            } catch (t: Throwable) {
                // removed or a no-op on newer servers
            }
        }
        world.isAutoSave = false
        return world
    }

    /** The load of [a] cannot continue: report it and let BedWars1058 move on to the next queued arena. */
    private fun fail(a: IArena, reason: String) {
        log.severe("Cannot load variant arena '${a.arenaName}': $reason")
        deleteMarkedCopy(a.worldName)
        // next tick: removeFromEnableQueue starts the next arena immediately, which must not recurse into us
        sync { api.arenaUtil.removeFromEnableQueue(a) }
    }

    // ---------------------------------------------------------------------------------------------
    // Unload / cleanup
    // ---------------------------------------------------------------------------------------------

    /** Unloads [worldName] without saving and deletes its copy. Main thread; retried while players are moved out. */
    fun unloadAndDelete(worldName: String, attempt: Int = 0) {
        val main = Bukkit.getWorlds().firstOrNull()?.name
        if (main != null && main.equals(worldName, ignoreCase = true)) return
        val world = Bukkit.getWorld(worldName)
        if (world != null) {
            val ok = try {
                Bukkit.unloadWorld(world, false)
            } catch (t: Throwable) {
                false
            }
            if (!ok) {
                if (shuttingDown() || attempt >= 10) {
                    log.warning("Could not unload world '$worldName'; its copy is removed on the next start.")
                    return
                }
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { unloadAndDelete(worldName, attempt + 1) }, 20L)
                return
            }
        }
        val unloadedAt = System.currentTimeMillis()
        if (shuttingDown()) deleteCopy(worldName, unloadedAt) else io.execute { deleteCopy(worldName, unloadedAt) }
    }

    /** Deletes a copy (only if it carries our marker) unless a newer copy has been made since [notAfter]. */
    private fun deleteCopy(worldName: String, notAfter: Long? = null) {
        val dir = WorldLayout.worldDir(worldName, serverRoot)
        val marker = File(dir, FileOps.MARKER)
        if (!marker.exists()) return
        if (notAfter != null) {
            val created = try {
                marker.readText().trim().toLongOrNull()
            } catch (ex: Exception) {
                null
            }
            if (created != null && created >= notAfter) return // a new copy of the same name is in use
        }
        try {
            FileOps.deleteRecursively(dir.toPath())
            owned -= worldName.lowercase(Locale.ROOT)
        } catch (ex: Exception) {
            log.warning("Could not delete the copy '$worldName': ${ex.message}. It is removed on the next start.")
        }
    }

    private fun deleteMarkedCopy(worldName: String) {
        if (Bukkit.getWorld(worldName) != null) return
        if (shuttingDown()) deleteCopy(worldName) else io.execute { deleteCopy(worldName) }
    }

    /** Removes copies left behind by a crash. Startup only, before anything is loaded. */
    fun sweep(): Int {
        val loaded = Bukkit.getWorlds().map { it.name.lowercase(Locale.ROOT) }.toSet()
        var n = 0
        for (d in WorldLayout.runtimeContainers(serverRoot).flatMap { it.listFiles()?.toList() ?: emptyList() }) {
            if (!d.isDirectory || d.name.lowercase(Locale.ROOT) in loaded) continue
            if (!File(d, FileOps.MARKER).exists()) continue
            try {
                FileOps.deleteRecursively(d.toPath())
                n++
            } catch (ex: Exception) {
                log.warning("Could not remove the leftover copy '${d.name}': ${ex.message}")
            }
        }
        if (n > 0) log.info("Removed $n leftover variant world cop${if (n == 1) "y" else "ies"} from the server root.")
        return n
    }

    fun isOwned(worldName: String): Boolean =
        worldName.lowercase(Locale.ROOT) in owned || File(WorldLayout.worldDir(worldName, serverRoot), FileOps.MARKER).exists()

    fun shutdown() {
        io.shutdown()
        try {
            io.awaitTermination(15, TimeUnit.SECONDS)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** The server is stopping (or BedWars1058 is going away): scheduled tasks would never run any more. */
    private fun shuttingDown(): Boolean = !plugin.isEnabled || !bedwarsPlugin.isEnabled

    private fun sync(block: () -> Unit) {
        if (!shuttingDown()) Bukkit.getScheduler().runTask(plugin, Runnable { block() }) else block()
    }

    private fun debug(msg: String) {
        if (settings().debug) log.info("[debug] $msg")
    }

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9_.-]+")
    }
}
