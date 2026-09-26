package g.sw.gef

import g.sw.spi.Json
import java.util.Base64

object Gef {

    const val MAGIC = "gef"
    const val CONTAINER_VERSION = "1.0"

    private val FRAME = Regex("""^====\s*([A-Za-z0-9][A-Za-z0-9_\-:]*)\s*====\s*$""")
    private val META_KEY = Regex("""^[A-Za-z0-9_][A-Za-z0-9_\-]*$""")
    private val RESERVED = setOf("id", "name", "version", "summary")

    data class Bundle(
        val id: String,
        val name: String,
        val version: String? = null,
        val summary: String? = null,
        val meta: Map<String, String> = emptyMap(),
        val icon: ByteArray? = null,
        val ui: String,
        val vms: Map<String, ByteArray> = emptyMap(),
    ) {
        override fun equals(other: Any?): Boolean = other is Bundle &&
            id == other.id && name == other.name && version == other.version &&
            summary == other.summary && meta == other.meta &&
            ui == other.ui &&
            bytesEqual(icon, other.icon) &&
            vms.keys == other.vms.keys &&
            vms.all { (k, v) -> bytesEqual(v, other.vms[k]) }

        override fun hashCode(): Int {
            var h = id.hashCode()
            h = 31 * h + name.hashCode()
            h = 31 * h + (version?.hashCode() ?: 0)
            h = 31 * h + meta.hashCode()
            h = 31 * h + (icon?.contentHashCode() ?: 0)
            h = 31 * h + ui.hashCode()
            h = 31 * h + vms.entries.sumOf { (k, v) -> k.hashCode() * 31 + v.contentHashCode() }
            return h
        }

        private fun bytesEqual(a: ByteArray?, b: ByteArray?): Boolean =
            a != null && b != null && a.contentEquals(b)
    }

    fun parse(text: String): Bundle {
        val lines = splitLines(text)
        if (lines.isEmpty() || lines[0].trim() != "$MAGIC $CONTAINER_VERSION") {
            throw IllegalArgumentException("bad magic, expected '$MAGIC $CONTAINER_VERSION'")
        }

        val meta = LinkedHashMap<String, String>()
        var inSegment = false
        var openSegment: String? = null
        var iconLines = ArrayList<String>()
        var iconSeen = false
        var uiLines = ArrayList<String>()
        var uiSeen = false
        var vmLines = LinkedHashMap<String, ArrayList<String>>()

        for (line in lines.drop(1)) {
            val frame = FRAME.matchEntire(line)
            if (frame != null) {
                val seg = frame.groupValues[1]
                when {
                    seg == "icon" -> {
                        if (iconSeen) throw IllegalArgumentException("duplicate 'icon' segment")
                        iconSeen = true
                        openSegment = seg
                    }
                    seg == "ui" -> {
                        if (uiSeen) throw IllegalArgumentException("duplicate 'ui' segment")
                        uiSeen = true
                        openSegment = seg
                    }
                    seg.startsWith("vm:") -> {
                        val engine = seg.removePrefix("vm:")
                        if (engine.isEmpty() || engine.contains(':')) {
                            throw IllegalArgumentException("bad vm segment name '$seg'")
                        }
                        if (vmLines.containsKey(engine)) {
                            throw IllegalArgumentException("duplicate 'vm:$engine' segment")
                        }
                        vmLines[engine] = ArrayList()
                        openSegment = seg
                    }
                    else -> throw IllegalArgumentException("unknown segment '$seg'")
                }
                inSegment = true
                continue
            }

            if (!inSegment) {
                if (line.isBlank()) continue
                val sep = line.indexOf(':')
                if (sep <= 0) throw IllegalArgumentException("bad metadata line: '$line'")
                val key = line.substring(0, sep).trim()
                val value = line.substring(sep + 1).trim()
                if (!META_KEY.matches(key)) throw IllegalArgumentException("bad metadata key '$key'")
                if (meta.put(key, value) != null) throw IllegalArgumentException("duplicate metadata key '$key'")
            } else {
                when (openSegment) {
                    "icon" -> iconLines.add(line)
                    "ui" -> uiLines.add(line)
                    else -> {
                        val engine = openSegment!!.removePrefix("vm:")
                        vmLines.getValue(engine).add(line)
                    }
                }
            }
        }

        if (!uiSeen) throw IllegalArgumentException("missing 'ui' segment")
        val id = meta["id"] ?: throw IllegalArgumentException("missing metadata 'id'")
        val name = meta["name"] ?: throw IllegalArgumentException("missing metadata 'name'")
        if (id.isBlank() || name.isBlank()) throw IllegalArgumentException("'id' and 'name' must not be blank")

        val ui = uiLines.joinToString("\n").trim('\n')
        val uiObj = try {
            Json.parse(ui)
        } catch (e: Exception) {
            throw IllegalArgumentException("'ui' segment is not a JSON value", e)
        }
        if (uiObj !is Map<*, *>) throw IllegalArgumentException("'ui' segment must be a JSON object")

        val icon = if (iconSeen) {
            val decoded = Base64.getDecoder().decode(iconLines.joinToString(""))

            if (decoded.isEmpty()) throw IllegalArgumentException("'icon' segment decodes to zero bytes")
            decoded
        } else null

        fun decode(engine: String): Pair<String, ByteArray> =
            engine to Base64.getDecoder().decode(vmLines.getValue(engine).joinToString(""))

        return Bundle(
            id = id,
            name = name,
            version = meta["version"],
            summary = meta["summary"],
            meta = meta.filterKeys { it !in RESERVED },
            icon = icon,
            ui = ui,
            vms = vmLines.keys.associate { decode(it) },
        )
    }

    fun write(bundle: Bundle): String {
        if (bundle.id.isBlank()) throw IllegalArgumentException("id must not be blank")
        if (bundle.name.isBlank()) throw IllegalArgumentException("name must not be blank")
        checkUi(bundle.ui)

        return buildString {
            append(MAGIC).append(' ').append(CONTAINER_VERSION).append('\n')
                .append('\n')
                .append("name: ").append(bundle.name).append('\n')
                .append("id: ").append(bundle.id).append('\n')
            bundle.version?.let { append("version: ").append(it).append('\n') }
            bundle.summary?.let { append("summary: ").append(it).append('\n') }
            bundle.meta.keys.sorted().forEach { append(it).append(": ").append(bundle.meta[it]).append('\n') }
            append('\n')

            bundle.icon?.let { bytes ->
                append("==== icon ====\n")
                append(wrap(Base64.getEncoder().encodeToString(bytes)))
                    .append('\n')
            }
            append("==== ui ====\n")
            append(bundle.ui.stripTrailingNewlines()).append('\n')
            bundle.vms.keys.sorted().forEach { engine ->
                append('\n')
                    .append("==== vm:").append(engine).append(" ====\n")
                    .append(wrap(Base64.getEncoder().encodeToString(bundle.vms.getValue(engine))))
                    .append('\n')
            }
        }
    }

    fun uiJson(bundle: Bundle): Any? = Json.parse(bundle.ui)

    private fun splitLines(text: String): List<String> {
        val parts = text.split('\n')
        val trimmed = if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        return trimmed.map { it.removeSuffix("\r") }
    }

    private fun checkUi(ui: String) {
        val obj = try {
            Json.parse(ui)
        } catch (e: Exception) {
            throw IllegalArgumentException("'ui' must be a JSON value", e)
        }
        if (obj !is Map<*, *>) throw IllegalArgumentException("'ui' must be a JSON object")
    }

    private fun wrap(b64: String): String = b64.chunked(76).joinToString("\n")

    private fun String.stripTrailingNewlines(): String = trimEnd('\r', '\n')
}