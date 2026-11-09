package dev.etran

import dev.etran.world.WorldLayout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WorldLayoutTest {
    @Test
    fun `26 1 keeps region data at the dimension folder root and drops level files`(@TempDir dir: File) {
        File(dir, "region").mkdirs(); File(dir, "region/r.0.0.mca").writeText("x")
        File(dir, "level.dat").writeText("x"); File(dir, "DIM-1").mkdirs()
        WorldLayout.normalize(dir, modern = true)
        assertTrue(File(dir, "region/r.0.0.mca").isFile)
        assertFalse(File(dir, "level.dat").exists())
        assertFalse(File(dir, "DIM-1").exists())
    }

    @Test
    fun `new layout map is flattened on 26 1 and converted back on older servers`(@TempDir a: File, @TempDir b: File) {
        for (d in listOf(a, b)) {
            File(d, "dimensions/minecraft/overworld/region").mkdirs()
            File(d, "dimensions/minecraft/overworld/region/r.0.0.mca").writeText("x")
        }
        WorldLayout.normalize(a, modern = true)
        assertTrue(File(a, "region/r.0.0.mca").isFile)
        WorldLayout.normalize(b, modern = false)
        assertTrue(File(b, "region/r.0.0.mca").isFile)
    }
}
