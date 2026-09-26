package g.erp.satellite.gef

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import g.erp.satellite.json.Json

/**
 * Best-effort satellite port of the GEF container (swrepo:gef FORMAT.md v1).
 * Tolerant: returns null on anything it does not understand; vm: segments are
 * read but ignored until a VM engine exists on Android.
 */
object Gef {

    data class Bundle(
        val id: String,
        val name: String,
        val version: String? = null,
        val summary: String? = null,
        val meta: Map<String, String> = emptyMap(),
        val icon: ByteArray? = null,
        val ui: String,
    )

    private val FRAME = Regex("""^====\s*([A-Za-z0-9][A-Za-z0-9_\-:]*)\s*====\s*$""")
    private val RESERVED = setOf("id", "name", "version", "summary")

    fun parse(text: String): Bundle? {
        val lines = splitLines(text)
        if (lines.isEmpty() || lines[0].trim() != "gef 1.0") return null

        val meta = LinkedHashMap<String, String>()
        val iconLines = ArrayList<String>()
        val uiLines = ArrayList<String>()
        var inSegment = false
        var open: String? = null
        var uiSeen = false

        for (line in lines.drop(1)) {
            val frame = FRAME.matchEntire(line)
            if (frame != null) {
                open = when (frame.groupValues[1]) {
                    "icon" -> "icon"
                    "ui" -> {
                        uiSeen = true
                        "ui"
                    }
                    else -> "other"
                }
                inSegment = true
                continue
            }
            if (!inSegment) {
                if (line.isBlank()) continue
                val sep = line.indexOf(':')
                if (sep <= 0) return null
                meta[line.substring(0, sep).trim()] = line.substring(sep + 1).trim()
            } else {
                when (open) {
                    "icon" -> iconLines.add(line)
                    "ui" -> uiLines.add(line)
                }
            }
        }

        if (!uiSeen) return null
        val id = meta["id"] ?: return null
        val name = meta["name"] ?: return null
        if (id.isBlank() || name.isBlank()) return null

        val ui = uiLines.joinToString("\n").trim('\n')
        if (runCatching { Json.parse(ui) }.getOrNull() !is Map<*, *>) return null

        val icon = if (iconLines.isNotEmpty()) {
            runCatching {
                val clean = iconLines.joinToString("").filterNot { it.isWhitespace() }
                Base64.decode(clean, Base64.NO_WRAP)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        } else null

        return Bundle(
            id = id,
            name = name,
            version = meta["version"],
            summary = meta["summary"],
            meta = meta.filterKeys { it !in RESERVED },
            icon = icon,
            ui = ui,
        )
    }

    fun iconBitmap(bundle: Bundle): Bitmap? =
        bundle.icon?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    fun splitLines(text: String): List<String> {
        val parts = text.split('\n')
        val trimmed = if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        return trimmed.map { it.removeSuffix("\r") }
    }
}