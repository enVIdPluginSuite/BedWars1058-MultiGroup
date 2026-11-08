package dev.etran.core

import java.util.Locale

/** A named arena configuration of a map that can be chosen for a group. */
data class ConfigCandidate(val name: String, val aliases: List<String> = emptyList())

/** How well one configuration fits one group. Higher is better; [EXACT] is the maximum a name can reach. */
data class Scored(val config: String, val score: Int, val term: String, val reason: String, val position: Int)

/**
 * The configuration chosen for a group.
 *
 * @property config the chosen configuration, or `null` for the map's own (base) arena configuration
 * @property ambiguous true when another configuration scored exactly the same (the tie was broken by [ConfigMatcher.rank])
 */
data class MatchResult(
    val config: String?,
    val score: Int,
    val reason: String,
    val ambiguous: Boolean = false,
    val runnerUp: String? = null,
)

/**
 * Picks the configuration of a map that "most closely matches" a group name.
 *
 * Group names in BedWars setups are free text (`Solo`, `SoloRush`, `Doubles_OneBlock`, `4v4v4v4`, `Duos-Lucky`...), so
 * the matcher compares *words*, not strings:
 *
 *  1. the exact name (ignoring case and punctuation) wins outright: `solo-rush` == `SoloRush`              -> 1000
 *  2. the same words in another order or spelling: `rush_solo` vs `SoloRush`, `duos` vs `Doubles`            -> 900
 *  3. shared words. Mostly the share of the CONFIG's words found in the group (a config called `solo` is a
 *     perfect description of part of `SoloRush`), a little for how much of the group is covered               -> < 900
 *  4. one name contained in the other (`lucky` in `luckyblocks`)                                              -> < 300
 *  5. typo tolerance (`doubels` ~ `doubles`)                                                                  -> < 300
 *
 * Words are split on punctuation and camelCase and normalised through a synonym table (`duos`, `2v2`, `pairs` ->
 * `doubles`; `NvNvN..` -> the team size) plus a simple plural rule. Ties are broken by where the matched word sits
 * in the group name (earlier wins: `SoloRush` prefers `solo` over `rush`), then by the longer name, then
 * alphabetically, so the result is always deterministic. [MatchResult.ambiguous] reports such ties.
 */
class ConfigMatcher(
    synonyms: Map<String, List<String>> = DEFAULT_SYNONYMS,
    /** Configurations scoring below this are not used; the group then gets the base configuration. */
    val minScore: Int = 150,
) {
    private val canonicalByForm: Map<String, String>

    init {
        val m = HashMap<String, String>()
        for ((canonical, forms) in synonyms) {
            val c = normalize(canonical)
            if (c.isEmpty()) continue
            m[c] = c
            for (f in forms) {
                val n = normalize(f)
                if (n.isNotEmpty()) m.putIfAbsent(n, c)
            }
        }
        canonicalByForm = m
    }

    /** Scores every term (name and aliases) of [candidate] against [group] and returns the best one. */
    fun score(group: String, candidate: ConfigCandidate): Scored {
        var best: Scored? = null
        for (term in listOf(candidate.name) + candidate.aliases) {
            val s = scoreTerm(group, term, candidate.name)
            if (best == null || s.score > best.score || (s.score == best.score && s.position < best.position)) best = s
        }
        return best ?: Scored(candidate.name, 0, candidate.name, "no name", Int.MAX_VALUE)
    }

    /** All candidates, best first, using the documented tie-breakers. */
    fun rank(group: String, candidates: Collection<ConfigCandidate>): List<Scored> =
        candidates.map { score(group, it) }.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenBy { it.position }
                .thenByDescending { normalize(it.term).length }
                .thenBy { it.config.lowercase(Locale.ROOT) },
        )

    /**
     * Chooses the configuration for [group].
     *
     * @param explicit a fixed choice from the map file (`group-configs`): a configuration name or [BASE]; wins when valid
     */
    fun match(group: String, candidates: Collection<ConfigCandidate>, explicit: String? = null): MatchResult {
        if (explicit != null) {
            if (explicit.equals(BASE, ignoreCase = true)) return MatchResult(null, EXACT, "set to the base configuration in group-configs")
            val fixed = candidates.firstOrNull { it.name.equals(explicit, ignoreCase = true) }
            if (fixed != null) return MatchResult(fixed.name, EXACT, "set in group-configs")
        }
        val ranked = rank(group, candidates)
        val top = ranked.firstOrNull()
        if (top == null || top.score < minScore) {
            val why = if (top == null) "this map has no configurations" else
                "no configuration is close enough (best: '${top.config}' with ${top.score}, needs $minScore)"
            return MatchResult(null, top?.score ?: 0, "base configuration: $why")
        }
        val second = ranked.getOrNull(1)
        val tie = second != null && second.score == top.score
        return MatchResult(top.config, top.score, top.reason, ambiguous = tie, runnerUp = second?.config)
    }

    // ---------------------------------------------------------------------------------------------

    private fun scoreTerm(group: String, term: String, config: String): Scored {
        val ng = normalize(group)
        val nt = normalize(term)
        if (ng.isEmpty() || nt.isEmpty()) return Scored(config, 0, term, "empty name", Int.MAX_VALUE)
        if (ng == nt) return Scored(config, EXACT, term, "exact name", 0)

        val gWords = words(group)
        val tWords = words(term)
        val gSet = gWords.toSet()
        val tSet = tWords.toSet()
        if (gSet == tSet) return Scored(config, SAME_WORDS, term, "same words (${tSet.joinToString(" ")})", 0)

        val common = gSet intersect tSet
        if (common.isNotEmpty()) {
            var score = 400 * common.size / tSet.size + 200 * common.size / gSet.size
            if (gSet.containsAll(tSet)) score += 100
            val pos = gWords.indexOfFirst { it in common }
            return Scored(config, minOf(score, SAME_WORDS - 1), term, "shares ${common.joinToString(", ") { "'$it'" }}", pos)
        }

        val shorter = minOf(ng.length, nt.length)
        val longer = maxOf(ng.length, nt.length)
        if (shorter >= 3 && (ng.contains(nt) || nt.contains(ng))) {
            val pos = if (ng.contains(nt)) ng.indexOf(nt) else 0
            return Scored(config, 150 + 149 * shorter / longer, term, "'${if (ng.contains(nt)) term else group}' is part of the other name", pos)
        }

        val similarity = 1.0 - levenshtein(ng, nt).toDouble() / longer
        if (similarity >= 0.7) {
            return Scored(config, (299 * similarity).toInt(), term, "similar spelling (${(similarity * 100).toInt()}%)", 0)
        }
        return Scored(config, 0, term, "no common words", Int.MAX_VALUE)
    }

    /** Words of a name, normalised: `Doubles_OneBlock` -> [doubles, one, block], `SoloRush` -> [solo, rush], `Duos` -> [doubles]. */
    fun words(name: String): List<String> {
        val spaced = name
            .replace(CAMEL_LOWER_UPPER, "$1 $2")
            .replace(CAMEL_ACRONYM, "$1 $2")
        return spaced.split(NON_ALNUM).filter { it.isNotEmpty() }.map { canonical(it.lowercase(Locale.ROOT)) }
    }

    private fun canonical(word: String): String {
        canonicalByForm[word]?.let { return it }
        TEAM_SIZE.matchEntire(word)?.let { m ->
            when (m.groupValues[1]) {
                "1" -> return canonicalByForm["solo"] ?: "solo"
                "2" -> return canonicalByForm["doubles"] ?: "doubles"
                "3" -> return canonicalByForm["trios"] ?: "trios"
                "4" -> return canonicalByForm["squads"] ?: "squads"
            }
        }
        val singular = singular(word)
        canonicalByForm[singular]?.let { return it }
        // match "squad" against a canonical "squads" too
        canonicalByForm[singular + "s"]?.let { return it }
        return singular
    }

    companion object {
        const val EXACT = 1000
        const val SAME_WORDS = 900

        /** Value for `group-configs` that means "use the map's own arena configuration". */
        const val BASE = "base"

        private val CAMEL_LOWER_UPPER = Regex("([a-z0-9])([A-Z])")
        private val CAMEL_ACRONYM = Regex("([A-Z]+)([A-Z][a-z])")
        private val NON_ALNUM = Regex("[^A-Za-z0-9]+")
        private val TEAM_SIZE = Regex("(\\d)(?:v\\1)+")

        val DEFAULT_SYNONYMS: Map<String, List<String>> = linkedMapOf(
            "solo" to listOf("solos", "single", "singles", "1v1", "1s"),
            "doubles" to listOf("double", "duo", "duos", "2v2", "2s", "pair", "pairs", "twos"),
            "trios" to listOf("trio", "triple", "triples", "3v3", "3s", "threes"),
            "squads" to listOf("squad", "quad", "quads", "4v4", "4s", "fours"),
        )

        /** Lower-case letters and digits only. */
        fun normalize(s: String): String = buildString(s.length) {
            for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar())
        }

        private fun singular(w: String): String =
            if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w[w.length - 2].isDigit()) w.dropLast(1) else w

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val t = prev
                prev = cur
                cur = t
            }
            return prev[b.length]
        }
    }
}
