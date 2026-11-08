package dev.etran.bedwars

import java.util.Locale
import java.util.logging.Filter
import java.util.logging.LogRecord

/**
 * Hides BedWars1058's "There isn't any map called X" startup warning for variant arenas.
 *
 * BedWars1058 checks for every arena's world while it enables, before this plugin can take part, so it reports
 * every variant as missing. This plugin loads them right afterwards. Warnings about any other arena stay visible.
 * Chains to the filter that was installed before (BedWars1058-MapLoader installs a similar one).
 */
class VariantWarningFilter(
    private val isVariant: (String) -> Boolean,
    val previous: Filter?,
) : Filter {

    override fun isLoggable(record: LogRecord): Boolean {
        val message = record.message
        if (message != null) {
            val name = extractMapName(message)
            if (name != null && isVariant(name)) return false
        }
        return previous?.isLoggable(record) ?: true
    }

    companion object {
        private val pattern = Regex("there isn't any map called\\s+(.+)", RegexOption.IGNORE_CASE)
        private val colorCodes = Regex("(?i)[§&][0-9a-fk-or]")

        fun extractMapName(message: String): String? {
            val clean = message.replace(colorCodes, "")
            val raw = pattern.find(clean)?.groupValues?.get(1) ?: return null
            val name = raw.trim().trim('"', '\'', '.', '!', ':', ',').trim()
            return name.takeIf { it.isNotEmpty() && !it.lowercase(Locale.ROOT).contains(' ') }
        }
    }
}
