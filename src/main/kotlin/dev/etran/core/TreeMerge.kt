package dev.etran.core

/**
 * Operations on YAML documents represented as plain nested maps (`Map<String, Any?>` with lists and scalars as leaves).
 *
 * The plugin converts Bukkit configuration sections into such trees, so all of the layering logic stays free of
 * Bukkit and can be unit-tested.
 */
object TreeMerge {

    /**
     * Layers [override] on top of [base] and returns a new tree (the inputs are not modified).
     *
     *  - maps are merged key by key, recursively
     *  - lists and scalars replace the base value
     *  - a map at a path for which [replace] returns true replaces the base map entirely instead of being merged
     *    (used for `Team`: a variant with 4 teams must not inherit the other 4 teams of an 8-team base)
     */
    fun merge(
        base: Map<String, Any?>,
        override: Map<String, Any?>,
        replace: (String) -> Boolean = { false },
        prefix: String = "",
    ): LinkedHashMap<String, Any?> {
        val out = deepCopy(base)
        for ((k, v) in override) {
            val path = prefix + k
            val existing = out[k]
            out[k] = if (v is Map<*, *> && existing is Map<*, *> && !replace(path)) {
                merge(asTree(existing), asTree(v), replace, "$path.")
            } else {
                deepCopyValue(v)
            }
        }
        return out
    }

    /** Removes the value at a dotted [path] (`Team.Red`, `generator.Emerald`). Returns true if something was removed. */
    fun remove(tree: MutableMap<String, Any?>, path: String): Boolean {
        val parts = path.split('.')
        var node: MutableMap<String, Any?> = tree
        for (i in 0 until parts.size - 1) {
            @Suppress("UNCHECKED_CAST")
            node = node[parts[i]] as? MutableMap<String, Any?> ?: return false
        }
        if (!node.containsKey(parts.last())) return false
        node.remove(parts.last())
        return true
    }

    /** Reads the value at a dotted [path], or null. */
    fun get(tree: Map<String, Any?>, path: String): Any? {
        var node: Any? = tree
        for (p in path.split('.')) {
            node = (node as? Map<*, *>)?.get(p) ?: return null
        }
        return node
    }

    /** Sets [value] at a dotted [path], creating intermediate maps. */
    fun set(tree: MutableMap<String, Any?>, path: String, value: Any?) {
        val parts = path.split('.')
        var node: MutableMap<String, Any?> = tree
        for (i in 0 until parts.size - 1) {
            val next = node[parts[i]]
            @Suppress("UNCHECKED_CAST")
            node = if (next is MutableMap<*, *>) next as MutableMap<String, Any?> else LinkedHashMap<String, Any?>().also { node[parts[i]] = it }
        }
        node[parts.last()] = value
    }

    /** Result of [diff]: what has to be layered on a base (and removed from it) to obtain another tree. */
    data class Diff(val overrides: LinkedHashMap<String, Any?>, val removals: List<String>) {
        val isEmpty: Boolean get() = overrides.isEmpty() && removals.isEmpty()
    }

    /**
     * Computes the smallest override (plus removals) that turns [base] into [target] when applied with [merge] and
     * the same [replace] rule. Used to import an existing duplicate arena as a configuration: only what really
     * differs from the base arena is stored.
     */
    fun diff(base: Map<String, Any?>, target: Map<String, Any?>, replace: (String) -> Boolean = { false }, prefix: String = ""): Diff {
        val overrides = LinkedHashMap<String, Any?>()
        val removals = ArrayList<String>()
        for ((k, tv) in target) {
            val path = prefix + k
            val bv = base[k]
            if (!base.containsKey(k)) {
                overrides[k] = deepCopyValue(tv)
                continue
            }
            if (tv is Map<*, *> && bv is Map<*, *>) {
                if (replace(path)) {
                    if (!sameValue(bv, tv)) overrides[k] = deepCopyValue(tv)
                } else {
                    val sub = diff(asTree(bv), asTree(tv), replace, "$path.")
                    if (sub.overrides.isNotEmpty()) overrides[k] = sub.overrides
                    removals += sub.removals
                }
            } else if (!sameValue(bv, tv)) {
                overrides[k] = deepCopyValue(tv)
            }
        }
        for (k in base.keys) if (!target.containsKey(k)) removals += prefix + k
        return Diff(overrides, removals)
    }

    /** Structural equality that treats `1` and `1.0` alike (YAML round trips can change number types). */
    fun sameValue(a: Any?, b: Any?): Boolean {
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a is Map<*, *> && b is Map<*, *>) {
            if (a.size != b.size) return false
            return a.all { (k, v) -> b.containsKey(k) && sameValue(v, b[k]) }
        }
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return false
            return a.indices.all { sameValue(a[it], b[it]) }
        }
        return a == b
    }

    @Suppress("UNCHECKED_CAST")
    fun asTree(m: Map<*, *>): Map<String, Any?> =
        if (m.keys.all { it is String }) m as Map<String, Any?> else m.entries.associate { (k, v) -> k.toString() to v }

    fun deepCopy(m: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in m) out[k] = deepCopyValue(v)
        return out
    }

    private fun deepCopyValue(v: Any?): Any? = when (v) {
        is Map<*, *> -> deepCopy(asTree(v))
        is List<*> -> v.map { deepCopyValue(it) }
        else -> v
    }
}
