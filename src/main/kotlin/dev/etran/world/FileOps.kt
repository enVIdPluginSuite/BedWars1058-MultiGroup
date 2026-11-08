package dev.etran.world

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipFile

/** Blocking file helpers; run them on the IO executor, never on the main thread. */
object FileOps {

    /** Dropped into every runtime world copy this plugin creates, so only those are ever deleted. */
    const val MARKER = ".multigroup-runtime"

    /** A world folder: has `region` (up to 1.21), `dimensions` (newer layout) or `level.dat`. */
    fun isWorldFolder(dir: File): Boolean =
        dir.isDirectory && (File(dir, "region").isDirectory || File(dir, "dimensions").isDirectory || File(dir, "level.dat").isFile)

    /**
     * What is left out when a map is copied for play. `uid.dat` MUST go: two worlds with the same UID refuse to
     * load side by side, and a variant always runs next to its base map. `level.dat` is dropped like BedWars1058's
     * own adapter does (configurable).
     */
    fun runtimeSkip(deleteLevelDat: Boolean): (String) -> Boolean = { rel ->
        rel == MARKER || rel == ".maploader-runtime" || rel == ".maploader-setup" ||
            rel == "uid.dat" || rel == "session.lock" || rel.endsWith("/session.lock") ||
            (deleteLevelDat && (rel == "level.dat" || rel == "level.dat_old" || rel == "level.dat_mcr"))
    }

    fun copyDirectory(src: Path, dst: Path, skip: (String) -> Boolean = { false }) {
        Files.createDirectories(dst)
        Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = rel(src, dir)
                if (rel.isNotEmpty() && skip(rel)) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(dst.resolve(rel))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = rel(src, file)
                if (!skip(rel)) Files.copy(file, dst.resolve(rel), StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun rel(base: Path, p: Path): String = base.relativize(p).toString().replace('\\', '/')

    /** "" or the single wrapping folder (`mapname/`) that has to be stripped when extracting [zip]. */
    fun detectZipPrefix(zip: Path): String {
        ZipFile(zip.toFile()).use { zf ->
            var firstSegment: String? = null
            var rootHasWorldMarker = false
            var allSameSegment = true
            var wrappedMarker = false
            val markers = listOf("level.dat", "region/", "dimensions/")
            for (entry in zf.entries()) {
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue
                if (markers.any { name == it || name.startsWith(it) }) rootHasWorldMarker = true
                val slash = name.indexOf('/')
                if (slash <= 0) {
                    allSameSegment = false
                    continue
                }
                val seg = name.substring(0, slash + 1)
                if (firstSegment == null) firstSegment = seg else if (firstSegment != seg) allSameSegment = false
                val inner = name.substring(slash + 1)
                if (markers.any { inner == it || inner.startsWith(it) }) wrappedMarker = true
            }
            return if (!rootHasWorldMarker && allSameSegment && wrappedMarker && firstSegment != null) firstSegment else ""
        }
    }

    fun unzip(zip: Path, dst: Path, skip: (String) -> Boolean = { false }) {
        val prefix = detectZipPrefix(zip)
        val root = dst.toAbsolutePath().normalize()
        Files.createDirectories(root)
        ZipFile(zip.toFile()).use { zf ->
            for (entry in zf.entries()) {
                var name = entry.name.replace('\\', '/')
                if (prefix.isNotEmpty()) {
                    if (!name.startsWith(prefix)) continue
                    name = name.substring(prefix.length)
                }
                if (name.isEmpty() || name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue
                val out = root.resolve(name).normalize()
                if (!out.startsWith(root)) throw IOException("Zip entry '${entry.name}' escapes the target directory")
                if (skip(name.trimEnd('/'))) continue
                if (entry.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    zf.getInputStream(entry).use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
        }
    }

    /** Deletes [path] recursively, retrying briefly (Windows can hold files for a moment after a world unloads). */
    fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        var lastError: IOException? = null
        for (attempt in 1..5) {
            try {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
                return
            } catch (ex: IOException) {
                lastError = ex
                try {
                    Thread.sleep(100L * attempt)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw lastError ?: IOException("Could not delete $path")
    }
}
