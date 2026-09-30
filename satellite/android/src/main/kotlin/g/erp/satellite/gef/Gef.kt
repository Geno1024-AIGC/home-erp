package g.erp.satellite.gef

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import g.erp.satellite.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * One installed GEF package, either a native DSL bundle (v1 text container)
 * or a v0.1 html zip package; the renderer branches on the concrete type.
 */
sealed class GefPackage {
    abstract val id: String
    abstract val name: String
    abstract val version: String?
    val iconBitmap: Bitmap? get() = icon?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    protected abstract val icon: ByteArray?
}

class NativeGef(val bundle: Gef.Bundle) : GefPackage() {
    override val id get() = bundle.id
    override val name get() = bundle.name
    override val version get() = bundle.version
    override val icon get() = bundle.icon
}

class HtmlGef(
    override val id: String,
    override val name: String,
    override val version: String?,
    val summary: String?,
    val entry: String,
    val dir: File,
    override val icon: ByteArray?,
) : GefPackage()

/**
 * Tolerant satellite parser for the HTML GEF container (zip + manifest.json).
 * Returns null on anything malformed; applies the same safety rules as the
 * JVM side ([g.sw.gef.HtmlGef]): relative paths only, explicit entry file,
 * caps on total size and entry count.
 */
object HtmlGefParser {

    const val MANIFEST = "manifest.json"
    const val DEFAULT_ENTRY = "index.html"
    const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
    const val MAX_ENTRIES = 256

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    data class Package(
        val id: String,
        val name: String,
        val version: String? = null,
        val summary: String? = null,
        val entry: String = DEFAULT_ENTRY,
        val icon: String? = null,
        val files: Map<String, ByteArray>,
    )

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && ZIP_MAGIC.contentEquals(bytes.copyOfRange(0, 4))

    fun unpack(bytes: ByteArray): Package? = runCatching { doUnpack(bytes) }.getOrNull()

    private fun doUnpack(bytes: ByteArray): Package {
        if (!isZip(bytes)) throw IllegalArgumentException("not a zip")
        val files = LinkedHashMap<String, ByteArray>()
        var manifestJson: String? = null
        var total = 0
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                checkPath(name)
                if (files.containsKey(name)) throw IllegalArgumentException("duplicate entry")
                val data = zip.readBytes()
                total += data.size
                if (total > MAX_TOTAL_BYTES) throw IllegalArgumentException("too large")
                count++
                if (count > MAX_ENTRIES) throw IllegalArgumentException("too many entries")
                if (name == MANIFEST) manifestJson = data.decodeToString() else files[name] = data
            }
        }
        val manifest = manifestJson?.let {
            (Json.parse(it) as? Map<*, *>)
                ?: throw IllegalArgumentException("manifest not an object")
        } ?: throw IllegalArgumentException("missing manifest")
        val id = (manifest["id"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("bad id")
        if (id.contains('/') || id.contains('\\')) throw IllegalArgumentException("bad id")
        val name = (manifest["name"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("bad name")
        val type = manifest["type"] as? String
        if (type != null && type != "html") throw IllegalArgumentException("bad type")
        val entry = (manifest["entry"] as? String) ?: DEFAULT_ENTRY
        checkPath(entry)
        if (!files.containsKey(entry)) throw IllegalArgumentException("missing entry")
        val icon = manifest["icon"] as? String
        icon?.let {
            checkPath(it)
            if (!files.containsKey(it)) throw IllegalArgumentException("missing icon")
        }
        return Package(id, name, manifest["version"]?.toString(), manifest["summary"]?.toString(), entry, icon, files)
    }

    private fun checkPath(name: String) {
        if (name.isEmpty() || name.startsWith('/') || '\\' in name) throw IllegalArgumentException("unsafe path")
        if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) throw IllegalArgumentException("unsafe path")
    }
}

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