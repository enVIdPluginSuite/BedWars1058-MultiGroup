package dev.etran.bedwars

import dev.etran.Settings
import dev.etran.core.VariantRegistry
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent
import java.util.Locale

/**
 * Stops BedWars1058 commands that make no sense for a generated variant arena before BedWars1058 sees them:
 *  - `/bw setupArena <variant>`: a variant has no map of its own (BedWars1058 would create an empty void map)
 *  - `/bw deleteArena <variant>`: the file would simply be generated again; the group has to be removed instead
 */
class GuardListener(
    private val registry: VariantRegistry,
    private val settings: () -> Settings,
    private val commandLabels: () -> List<String>,
) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerCommand(e: PlayerCommandPreprocessEvent) {
        if (blocked(e.player, e.message.removePrefix("/"))) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onConsoleCommand(e: ServerCommandEvent) {
        if (blocked(e.sender, e.command.removePrefix("/"))) e.isCancelled = true
    }

    private fun blocked(sender: CommandSender, line: String): Boolean {
        val parts = line.trim().split(WHITESPACE)
        if (parts.size < 3) return false
        val label = parts[0].lowercase(Locale.ROOT).substringAfter(':')
        if (commandLabels().none { it.equals(label, ignoreCase = true) }) return false
        val sub = parts[1].lowercase(Locale.ROOT)
        if (sub != "setuparena" && sub != "deletearena") return false
        val v = registry[parts[2]] ?: return false
        val m = settings().messages
        val template = if (sub == "setuparena") m.setupBlocked else m.deleteBlocked
        sender.sendMessage(m.format(template, "arena" to v.arenaName, "map" to v.map, "group" to v.group))
        return true
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
