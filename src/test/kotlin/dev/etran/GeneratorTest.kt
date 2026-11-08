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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end generation on real files, with Bukkit's YAML implementation. */
class GeneratorTest {

    private lateinit var root: File
    private lateinit var bw: File
    private lateinit var mapsDir: File
    private val log = Logger.getLogger("test")

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("bwmg-test").toFile()
        bw = File(root, "plugins/BedWars1058").also { File(it, "Arenas").mkdirs() }
        mapsDir = File(root, "plugins/BedWars1058-MultiGroup/maps").also { it.mkdirs() }
        File(bw, "config.yml").writeText("arenaGroups:\n- Solo\n- Doubles\nlobbyServer: hub\n")
        File(bw, "Arenas/lighthouse.yml").writeText(
            """
            group: Solo
            display-name: ''
            maxInTeam: 1
            minPlayers: 2
            Team:
              Red:
                Color: RED
                Spawn: 10.5,65.0,0.5,90.0,0.0
              Blue:
                Color: BLUE
                Spawn: -9.5,65.0,0.5,-90.0,0.0
            generator:
              Diamond:
              - 0.5,70.0,20.5,0.0,0.0
            waiting:
              Loc: 0.5,100.0,0.5,0.0,0.0
            """.trimIndent(),
        )
        File(mapsDir, "Lighthouse.yml").writeText(
            """
            groups: [Doubles, SoloRush]
            configs:
              doubles:
                overrides:
                  maxInTeam: 2
              solo:
                overrides:
                  minPlayers: 3
            """.trimIndent(),
        )
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

    private fun run(registry: VariantRegistry = VariantRegistry()): Generator.Result {
        val files = BedWarsFiles(bw, log)
        return Generator(files, MapStore(mapsDir), registry, log).run(settings()) { groups ->
            if (files.addArenaGroupsOnDisk(groups)) groups else emptyList()
        }
    }

    @Test
    fun `variant files are written, groups registered, stale files removed`() {
        val registry = VariantRegistry()
        val r = run(registry)
        assertEquals(setOf("lighthouse-doubles", "lighthouse-solorush"), r.plan.variants.map { it.arenaName }.toSet())
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())

        val doubles = YamlTrees.load(File(bw, "Arenas/lighthouse-doubles.yml"))
        assertNotNull(doubles)
        assertEquals("Doubles", doubles["group"])
        assertEquals(2, doubles["maxInTeam"])
        assertEquals("Lighthouse", doubles["display-name"])
        assertEquals(listOf("0.5,70.0,20.5,0.0,0.0"), (doubles["generator"] as Map<*, *>)["Diamond"])

        val rush = YamlTrees.load(File(bw, "Arenas/lighthouse-solorush.yml"))!!
        assertEquals("SoloRush", rush["group"])
        assertEquals(3, rush["minPlayers"]) // "solo" is the closest configuration for SoloRush

        // SoloRush was added to BedWars1058's arenaGroups, the other settings survived
        val main = YamlTrees.load(File(bw, "config.yml"))!!
        assertEquals(listOf("Solo", "Doubles", "SoloRush"), main["arenaGroups"])
        assertEquals("hub", main["lobbyServer"])

        // a second run changes nothing
        val again = run(registry)
        assertTrue(again.written.isEmpty())
        assertTrue(again.changed.isEmpty())

        // removing a group removes exactly its generated file and nothing else
        File(mapsDir, "Lighthouse.yml").writeText("groups: [Doubles]\nconfigs:\n  doubles:\n    overrides:\n      maxInTeam: 2\n")
        val after = run(registry)
        assertEquals(listOf("lighthouse-solorush"), after.removed)
        assertFalse(File(bw, "Arenas/lighthouse-solorush.yml").exists())
        assertTrue(File(bw, "Arenas/lighthouse.yml").exists())
        assertTrue(registry.isRetired("lighthouse-solorush"))
        assertEquals("lighthouse", registry.baseOf("lighthouse-solorush"))
    }

    @Test
    fun `an existing arena with a variant's name is never touched`() {
        val own = "group: Doubles\nmaxInTeam: 2\n"
        File(bw, "Arenas/lighthouse-doubles.yml").writeText(own)
        val r = run()
        assertEquals(listOf("lighthouse-solorush"), r.plan.variants.map { it.arenaName })
        assertEquals(own, File(bw, "Arenas/lighthouse-doubles.yml").readText())
        assertTrue(r.warnings.any { it.contains("/bwmg import") })
    }

    @Test
    fun `BedWars rewriting a generated file keeps it recognised`() {
        run()
        // BedWars1058 loads the file, adds its defaults and saves it again
        val f = File(bw, "Arenas/lighthouse-doubles.yml")
        val y = YamlConfiguration.loadConfiguration(f)
        y.set("worldBorder", 300)
        y.save(f)
        val r = run()
        assertTrue(r.removed.isEmpty())
        assertTrue(r.written.contains("lighthouse-doubles"))
    }
}
