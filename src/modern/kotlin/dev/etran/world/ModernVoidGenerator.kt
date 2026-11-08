package dev.etran.world

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import java.util.Random

/**
 * Void generator for Bukkit 1.13 and newer (ChunkData API). Lives in its own source set because it needs a newer
 * Bukkit API than the rest of the plugin, and is only ever loaded by name on servers that have `ChunkData`.
 */
class ModernVoidGenerator : ChunkGenerator() {
    @Deprecated("Deprecated in newer Bukkit versions, still called by them")
    override fun generateChunkData(world: World, random: Random, x: Int, z: Int, biome: BiomeGrid): ChunkData =
        createChunkData(world)

    override fun getFixedSpawnLocation(world: World, random: Random): Location = Location(world, 0.0, 64.0, 0.0)
}
