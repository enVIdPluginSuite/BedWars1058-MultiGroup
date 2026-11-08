package dev.etran.bedwars

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Converts between Bukkit configuration sections and plain nested maps (see [dev.etran.core.TreeMerge]). */
object YamlTrees {

    fun toTree(section: ConfigurationSection): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in section.getKeys(false)) out[key] = plain(section.get(key))
        return out
    }

    private fun plain(v: Any?): Any? = when (v) {
        is ConfigurationSection -> toTree(v)
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k.toString()] = plain(x) } }
        is List<*> -> v.map { plain(it) }
        else -> v
    }

    fun toYaml(tree: Map<String, Any?>): YamlConfiguration {
        val yml = YamlConfiguration()
        fill(yml, tree)
        return yml
    }

    private fun fill(section: ConfigurationSection, tree: Map<String, Any?>) {
        for ((k, v) in tree) {
            if (v is Map<*, *>) {
                val child = section.createSection(k)
                @Suppress("UNCHECKED_CAST")
                fill(child, v as Map<String, Any?>)
            } else {
                section.set(k, v)
            }
        }
    }

    /** Loads [file] as a tree; null when it cannot be read or parsed (the reason is added to [warnings]). */
    fun load(file: File, warnings: MutableList<String>? = null): LinkedHashMap<String, Any?>? {
        return try {
            val yml = YamlConfiguration()
            yml.loadFromString(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
            toTree(yml)
        } catch (ex: Exception) {
            warnings?.add("Could not read ${file.path}: ${ex.message}")
            null
        }
    }

    /**
     * Writes [tree] to [file] (UTF-8, through a temp file) unless the file already has exactly that content.
     * Returns true when the file was written.
     */
    fun save(file: File, tree: Map<String, Any?>, header: String? = null): Boolean {
        val body = toYaml(tree).saveToString()
        val text = if (header == null) body else header.lines().joinToString("\n") { "# $it" } + "\n" + body
        if (file.isFile) {
            val current = try {
                String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
            } catch (ex: Exception) {
                null
            }
            if (current == text) return false
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        Files.write(tmp.toPath(), text.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (ex: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return true
    }
}
