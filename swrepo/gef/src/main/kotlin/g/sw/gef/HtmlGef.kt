package g.sw.gef

import g.sw.spi.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * GEF v0.1: an HTML zip package — one zip holding a `manifest.json` plus web
 * assets (entry HTML page, CSS/JS, icon). Ships the manifest as a JSON object
 * so the Ruby of packaging stays data-driven; consumers sniff the zip magic
 * (see [isZip]) and render the page with a WebView instead of the native DSL.
 *
 * This JVM side packs and validates; the Android satellite keeps a small
 * tolerant port of it. Safeguards: relative paths only (no traversal),
 * duplicate/directory entries rejected, hard caps on total size and entry
 * count.
 */
object HtmlGef {

    const val MANIFEST = "manifest.json"
    const val DEFAULT_ENTRY = "index.html"
    const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
    const val MAX_ENTRIES = 256

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** 2000-01-01T00:00:00Z — keeps packed zips byte-reproducible (no build-time stamps). */
    private const val FIXED_ENTRY_TIME = 946_684_800_000L

    data class Package(
        val id: String,
        val name: String,
        val version: String? = null,
        val summary: String? = null,
        val entry: String = DEFAULT_ENTRY,
        val icon: String? = null,
        val files: Map<String, ByteArray>,
    ) {
        override fun equals(other: Any?): Boolean = other is Package &&
            id == other.id && name == other.name && version == other.version &&
            summary == other.summary && entry == other.entry && icon == other.icon &&
            files.keys == other.files.keys &&
            files.all { (k, v) -> v.contentEquals(other.files[k]) }

        override fun hashCode(): Int {
            var h = id.hashCode()
            h = 31 * h + name.hashCode()
            h = 31 * h + (version?.hashCode() ?: 0)
            h = 31 * h + (summary?.hashCode() ?: 0)
            h = 31 * h + entry.hashCode()
            h = 31 * h + (icon?.hashCode() ?: 0)
            h = 31 * h + files.entries.sumOf { (k, v) -> k.hashCode() * 31 + v.contentHashCode() }
            return h
        }
    }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && ZIP_MAGIC.contentEquals(bytes.copyOfRange(0, 4))

    fun pack(
        id: String,
        name: String,
        version: String? = null,
        summary: String? = null,
        entry: String = DEFAULT_ENTRY,
        icon: String? = null,
        files: Map<String, ByteArray>,
    ): ByteArray {
        if (id.isBlank()) throw IllegalArgumentException("id must not be blank")
        if (name.isBlank()) throw IllegalArgumentException("name must not be blank")
        checkEntry(entry)
        icon?.let { checkEntry(it) }
        files.keys.forEach { checkEntry(it) }
        if (files.totalSize() > MAX_TOTAL_BYTES) throw IllegalArgumentException("files exceed $MAX_TOTAL_BYTES bytes")
        if (files.size > MAX_ENTRIES) throw IllegalArgumentException("too many files: ${files.size}")

        val manifest = linkedMapOf<String, Any?>(
            "id" to id,
            "name" to name,
        )
        version?.let { manifest["version"] = it }
        summary?.let { manifest["summary"] = it }
        manifest["type"] = "html"
        if (entry != DEFAULT_ENTRY) manifest["entry"] = entry
        icon?.let { manifest["icon"] = it }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            writeEntry(zip, MANIFEST, Json.write(manifest).toByteArray(Charsets.UTF_8))
            files.keys.sorted().forEach { name ->
                writeEntry(zip, name, files.getValue(name))
            }
        }
        return out.toByteArray()
    }

    fun unpack(bytes: ByteArray): Package {
        if (!isZip(bytes)) throw IllegalArgumentException("not a zip (magic mismatch)")

        val files = LinkedHashMap<String, ByteArray>()
        var manifestJson: String? = null
        var total = 0
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val name = entry.name
                    checkEntry(name)
                    if (files.containsKey(name)) throw IllegalArgumentException("duplicate entry '$name'")
                    val data = zip.readBytes()
                    total += data.size
                    if (total > MAX_TOTAL_BYTES) throw IllegalArgumentException("uncompressed size exceeds $MAX_TOTAL_BYTES bytes")
                    count++
                    if (count > MAX_ENTRIES) throw IllegalArgumentException("too many entries: $count")
                    if (name == MANIFEST) manifestJson = data.decodeToString() else files[name] = data
                }
            }
        }

        val manifest = manifestJson?.let {
            runCatching { Json.parse(it) as? Map<*, *> }.getOrThrow()
                ?: throw IllegalArgumentException("manifest must be a JSON object")
        } ?: throw IllegalArgumentException("missing $MANIFEST")
        val id = (manifest["id"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("manifest 'id' missing or blank")
        if (id.contains('/') || id.contains('\\')) throw IllegalArgumentException("manifest 'id' must be a single path segment")
        val name = (manifest["name"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("manifest 'name' missing or blank")
        val type = manifest["type"] as? String
        if (type != null && type != "html") throw IllegalArgumentException("unsupported GEF type '$type'")
        val entry = (manifest["entry"] as? String) ?: DEFAULT_ENTRY
        checkEntry(entry)
        if (!files.containsKey(entry)) throw IllegalArgumentException("entry file '$entry' not found")
        val icon = manifest["icon"] as? String
        icon?.let {
            checkEntry(it)
            if (!files.containsKey(it)) throw IllegalArgumentException("icon file '$it' not found")
        }
        if (!files.containsKey(MANIFEST) && manifestJson != null) {
            // manifest never lands in the asset map; nothing to add
        }
        return Package(id, name, store(manifest["version"]), store(manifest["summary"]), entry, icon, files)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name).also { it.time = FIXED_ENTRY_TIME })
        zip.write(data)
        zip.closeEntry()
    }

    private fun store(value: Any?): String? = value?.toString()

    private fun checkEntry(name: String) {
        if (name.isEmpty() || name.startsWith('/') || '\\' in name) {
            throw IllegalArgumentException("unsafe entry name '$name'")
        }
        if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw IllegalArgumentException("unsafe entry name '$name'")
        }
    }

    private fun Map<String, ByteArray>.totalSize(): Long =
        values.sumOf { it.size.toLong() }
}