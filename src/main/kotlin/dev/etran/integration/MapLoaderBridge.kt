package dev.etran.integration

import dev.etran.bedwars.YamlTrees
import dev.etran.world.FileOps
import org.bukkit.plugin.Plugin
import java.io.File
import java.nio.file.Path
import java.util.Locale
import java.util.function.Function
import java.util.logging.Logger

/**
 * Optional cooperation with BedWars1058-MapLoader (the map library / just-in-time loader).
 *
 *  - MapLoader 3.6.0+ has an alias hook (`dev.bwworldloader.api.MapAliases`): variant arenas are then resolved to their
 *    base map inside MapLoader, so they are parked, listed and loaded just-in-time exactly like every other arena.
 *  - Older MapLoader versions have no hook: this plugin then loads variants itself, copying the base map out of
 *    MapLoader's library folder.
 *
 * Everything is done by name/reflection with JDK types only, so neither plugin depends on the other at compile time
 * (both shade and relocate Kotlin, so Kotlin types could not cross between them anyway).
 */
class MapLoaderBridge(private val owner: Plugin, private val log: Logger) {

    val plugin: Plugin? get() = owner.server.pluginManager.getPlugin(NAME)

    @Volatile
    var aliasHookActive: Boolean = false
        private set

    /** Registers [resolver] (arena name -> base map name or null) with MapLoader's alias hook. */
    fun registerAliases(resolver: (String) -> String?): Boolean {
        val ml = plugin ?: return false
        val api = aliasApi(ml) ?: return false
        return try {
            val fn = Function<String, String?> { name -> resolver(name) }
            api.getMethod("register", Plugin::class.java, Function::class.java).invoke(null, owner, fn)
            aliasHookActive = true
            true
        } catch (t: Throwable) {
            log.warning("BedWars1058-MapLoader's alias hook did not accept the variants (${t.javaClass.simpleName}: ${t.message}).")
            false
        }
    }

    fun unregisterAliases() {
        if (!aliasHookActive) return
        aliasHookActive = false
        val ml = plugin ?: return
        try {
            aliasApi(ml)?.getMethod("unregister", Plugin::class.java)?.invoke(null, owner)
        } catch (t: Throwable) {
            // MapLoader is going away as well
        }
    }

    private fun aliasApi(ml: Plugin): Class<*>? = try {
        Class.forName(ALIAS_CLASS, true, ml.javaClass.classLoader)
    } catch (t: Throwable) {
        null
    }

    /** True when MapLoader is enabled and has taken over BedWars1058's world loading. */
    fun isActive(): Boolean {
        val ml = plugin ?: return false
        if (!ml.isEnabled) return false
        return try {
            ml.javaClass.getMethod("getServices").invoke(ml) != null
        } catch (t: Throwable) {
            false
        }
    }

    fun version(): String? = plugin?.description?.version

    /** MapLoader's library folder (`storage.path`), read from the running plugin or from its config.yml. */
    fun libraryDir(): Path? {
        val ml = plugin ?: return null
        try {
            val services = ml.javaClass.getMethod("getServices").invoke(ml)
            if (services != null) {
                val store = services.javaClass.getMethod("getStore").invoke(services)
                val dir = store.javaClass.getMethod("getStorageDir").invoke(store)
                if (dir is Path) return dir
            }
        } catch (t: Throwable) {
            // fall back to the config file
        }
        val cfg = File(ml.dataFolder, "config.yml")
        val tree = if (cfg.isFile) YamlTrees.load(cfg) else null
        val raw = ((tree?.get("storage") as? Map<*, *>)?.get("path") as? String) ?: "bedwars_worlds"
        val p = File(raw).toPath()
        return (if (p.isAbsolute) p else owner.server.worldContainer.toPath().resolve(p)).toAbsolutePath().normalize()
    }

    /** A map in MapLoader's library: world folder `<lib>/<name>` or `<lib>/<name>.zip` (case-insensitive). */
    fun findInLibrary(name: String): Path? {
        val dir = libraryDir()?.toFile() ?: return null
        val kids = dir.listFiles() ?: return null
        val lower = name.lowercase(Locale.ROOT)
        kids.firstOrNull { it.isDirectory && it.name.lowercase(Locale.ROOT) == lower && FileOps.isWorldFolder(it) }?.let { return it.toPath() }
        kids.firstOrNull { it.isFile && it.name.lowercase(Locale.ROOT) == "$lower.zip" }?.let { return it.toPath() }
        return null
    }

    companion object {
        const val NAME = "BedWars1058-MapLoader"
        const val ALIAS_CLASS = "dev.bwworldloader.api.MapAliases"
    }
}
