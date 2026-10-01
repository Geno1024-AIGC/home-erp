package g.erp.satellite.gef

import android.content.Context

/**
 * User-controlled display order of GEF packages, persisted in prefs as a
 * newline-separated id list. Packages not yet in the saved order append at the
 * end (by [GefStore]'s id-stable listing); every explicit move persists the
 * full resulting order, so a freshly installed package only leaves the tail
 * once the user moves it.
 */
class PackageOrder(context: Context) {

    private val prefs = context.getSharedPreferences("sat", Context.MODE_PRIVATE)

    fun load(): List<String> =
        (prefs.getString(KEY, null) ?: "").split('\n').filter { it.isNotBlank() }

    fun apply(packages: List<GefPackage>): List<GefPackage> {
        val byId = packages.associateBy { it.id }
        val inOrder = load().mapNotNull { byId[it] }
        val pinned = inOrder.map { it.id }.toSet()
        val rest = packages.filter { it.id !in pinned }
        return inOrder + rest
    }

    /** Swaps [from] with its neighbour on the given side and persists. */
    fun move(packages: List<GefPackage>, from: Int, delta: Int): List<GefPackage> {
        val ordered = apply(packages)
        require(from in ordered.indices) { "index out of range: $from" }
        val to = from + delta
        if (to !in ordered.indices) return ordered
        val moved = ordered.toMutableList()
        val tmp = moved[from]
        moved[from] = moved[to]
        moved[to] = tmp
        save(moved.map { it.id })
        return moved
    }

    /** Persists an explicit id order (e.g. after a drag in the drawer). */
    fun reorder(ids: List<String>) = save(ids)

    private fun save(ids: List<String>) {
        prefs.edit().putString(KEY, ids.joinToString("\n")).apply()
    }

    companion object {
        private const val KEY = "gefOrder"
    }
}