package dev.etran

import dev.etran.bedwars.BedWarsFiles
import dev.etran.bedwars.YamlTrees
import dev.etran.core.VariantRegistry
import dev.etran.world.FileOps
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergerTest {

    private lateinit var root: File
    private lateinit var bw: File
    private lateinit var mg: File
    private val log = Logger.getLogger("test")

    private fun arena(group: String, max: Int, x: Int = 10) = """
        group: $group
        maxInTeam: $max
        Team:
          Red:
            Spawn: $x.5,65.0,0.5,90.0,0.0
            Bed: ${x + 4}.5,65.0,0.5,0.0,0.0
          Blue:
            Spawn: -$x.5,65.0,0.5,-90.0,0.0
            Bed: -${x + 4}.5,65.0,0.5,0.0,0.0
        generator:
          Diamond:
          - 0.5,70.0,20.5,0.0,0.0
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("bwmg-merge").toFile()
        bw = File(root, "plugins/BedWars1058").also { File(it, "Arenas").mkdirs() }
        mg = File(root, "plugins/BedWars1058-MultiGroup").also { it.mkdirs() }
        File(bw, "config.yml").writeText("arenaGroups:\n- Solo\n- Doubles\n")
        File(bw, "Arenas/lighthouse.yml").writeText(arena("Solo", 1))
        // a copy of lighthouse for another group, named like a generated variant
        File(bw, "Arenas/lighthouse-doubles.yml").writeText(arena("Doubles", 2))
        // an unrelated map and a same-group copy
        File(bw, "Arenas/castle.yml").writeText(arena("Solo", 1, x = 80))
        File(bw, "Arenas/lighthouse_2.yml").writeText(arena("Solo", 1))
        // the copy's world is a plain folder in the server root, named like the future variant
        File(root, "lighthouse-doubles/region").mkdirs()
        File(root, "lighthouse-doubles/region/r.0.0.mca").writeText("map")
    }

    @AfterTest
    fun tearDown() {
        FileOps.deleteRecursively(root.toPath())
    }

    private fun settings(): Settings {
        val y = YamlConfiguration()
        y.loadFromString(File("src/main/resources/config.yml").readText())
        return Settings.read(y, ArrayList())
    }

    private fun merger(): Merger {
        val s = settings()
        return Merger(BedWarsFiles(bw, log), MapStore(File(mg, "maps")), { s }, mg, root, { false }, log)
    }

    @Test
    fun `startup merge turns the copy into a configuration and generates its variant`() {
        val result = merger().autoMerge()
        assertEquals(listOf("lighthouse-doubles"), result.merged.map { it.dup })
        assertTrue(result.needReview.any { it.contains("lighthouse_2") && it.contains("same group") })

        // the copy's arena file is backed up, not lost; the other arenas are untouched
        assertFalse(File(bw, "Arenas/lighthouse-doubles.yml").exists())
        assertEquals(1, File(mg, "backups/arenas").listFiles()!!.size)
        assertTrue(File(bw, "Arenas/castle.yml").exists())
        assertTrue(File(bw, "Arenas/lighthouse_2.yml").exists())

        // only the difference is stored
        val map = YamlTrees.load(File(mg, "maps/lighthouse.yml"))!!
        assertEquals(listOf("Doubles"), map["groups"])
        val cfg = ((map["configs"] as Map<*, *>)["doubles"] as Map<*, *>)
        assertEquals(mapOf("maxInTeam" to 2), cfg["overrides"])
        assertEquals("lighthouse-doubles", cfg["merged-from"])

        // the plain world folder that would block the variant was set aside
        assertFalse(File(root, "lighthouse-doubles").exists())
        assertEquals(1, File(mg, "backups/worlds").listFiles()!!.size)

        // generation now produces exactly the arena that was merged, with the old settings
        val files = BedWarsFiles(bw, log)
        val r = Generator(files, MapStore(File(mg, "maps")), VariantRegistry(), log).run(settings()) { emptyList() }
        assertEquals(listOf("lighthouse-doubles"), r.plan.variants.map { it.arenaName })
        val v = YamlTrees.load(File(bw, "Arenas/lighthouse-doubles.yml"))!!
        assertEquals("Doubles", v["group"])
        assertEquals(2, v["maxInTeam"])
        assertTrue(files.isGenerated(v))

        // running it again finds nothing new
        assertTrue(merger().autoMerge().merged.isEmpty())
    }

    @Test
    fun `merge problems are reported and change nothing`() {
        val m = merger()
        val ex = kotlin.runCatching { m.prepare("lighthouse", "lighthouse_2") }.exceptionOrNull()
        assertTrue(ex is Merger.MergeException && ex.message!!.contains("same group"))
        assertTrue(File(bw, "Arenas/lighthouse_2.yml").exists())
        assertFalse(File(mg, "maps/lighthouse.yml").exists())
    }
}
