package g.erp.satellite.gef

import android.content.Context
import g.erp.satellite.json.Json
import java.io.File

/**
 * Installs, lists and removes GEF packages of both kinds from `filesDir/gefs`:
 * native v1 text bundles land as `<id>.gef` files, v0.1 html zips as `<id>/`
 * directories (extracted: manifest.json + web assets). Format is sniffed by the
 * leading zip magic, so a *.gef file picker accepts either.
 */
class GefStore(context: Context) {

    private val dir = File(context.filesDir, "gefs")
    private val maxBytes = 2 * 1024 * 1024

    class Probe(val id: String, val name: String, val version: String?)

    fun list(): List<GefPackage> {
        if (!dir.isDirectory) return emptyList()
        val native = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gef") }
            ?.mapNotNull { runCatching { Gef.parse(it.readText()) }.getOrNull() }
            ?.map { NativeGef(it) }
            ?: emptyList()
        val html = dir.listFiles()
            ?.filter { it.isDirectory && File(it, HtmlGefParser.MANIFEST).isFile }
            ?.mapNotNull { HtmlGefStorage.read(it) }
            ?: emptyList()
        return (native + html).sortedBy { it.id }
    }

    fun install(bytes: ByteArray): String {
        if (bytes.size > maxBytes) throw IllegalArgumentException("文件过大（超过 2MB）")
        return if (HtmlGefParser.isZip(bytes)) installHtml(bytes) else installNative(bytes)
    }

    /** Reads id/name/version from a .gef without installing it. */
    fun probe(bytes: ByteArray): Probe =
        if (HtmlGefParser.isZip(bytes)) {
            val pkg = HtmlGefParser.unpack(bytes) ?: throw IllegalArgumentException("不是有效的 HTML GEF 包")
            Probe(pkg.id, pkg.name, pkg.version)
        } else {
            val bundle = Gef.parse(bytes.decodeToString()) ?: throw IllegalArgumentException("不是有效的 GEF 文件")
            Probe(bundle.id, bundle.name, bundle.version)
        }

    fun versionOf(id: String): String? = list().firstOrNull { it.id == id }?.version

    private fun installNative(bytes: ByteArray): String {
        val bundle = Gef.parse(bytes.decodeToString()) ?: throw IllegalArgumentException("不是有效的 GEF 文件")
        dir.mkdirs()
        val target = File(dir, "${bundle.id}.gef")
        val tmp = File(dir, "${bundle.id}.tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("写入党失败")
        }
        return bundle.id
    }

    private fun installHtml(bytes: ByteArray): String {
        val pkg = HtmlGefParser.unpack(bytes) ?: throw IllegalArgumentException("不是有效的 HTML GEF 包")
        dir.mkdirs()
        val target = File(dir, pkg.id)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        File(target, HtmlGefParser.MANIFEST).writeText(manifestJson(pkg))
        for ((name, data) in pkg.files) {
            val out = File(target, name)
            out.parentFile?.takeIf { it != target }?.mkdirs()
            out.writeBytes(data)
        }
        return pkg.id
    }

    fun uninstall(id: String) {
        File(dir, "$id.gef").delete()
        File(dir, id).deleteRecursively()
    }

    private fun manifestJson(pkg: HtmlGefParser.Package): String = Json.write(
        linkedMapOf<String, Any?>(
            "id" to pkg.id,
            "name" to pkg.name,
        ).apply {
            pkg.version?.let { put("version", it) }
            pkg.summary?.let { put("summary", it) }
            put("type", "html")
            if (pkg.entry != HtmlGefParser.DEFAULT_ENTRY) put("entry", pkg.entry)
            pkg.icon?.let { put("icon", it) }
        },
    )
}

internal object HtmlGefStorage {

    fun read(dir: File): HtmlGef? = runCatching {
        val manifest = Json.parse(File(dir, HtmlGefParser.MANIFEST).readText()) as? Map<*, *> ?: return null
        val id = (manifest["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val name = (manifest["name"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val entry = (manifest["entry"] as? String) ?: HtmlGefParser.DEFAULT_ENTRY
        if (!File(dir, entry).isFile) return null
        val icon = (manifest["icon"] as? String)
            ?.takeIf { File(dir, it).isFile }
            ?.let { File(dir, it).readBytes() }
        HtmlGef(id, name, manifest["version"]?.toString(), manifest["summary"]?.toString(), entry, dir, icon)
    }.getOrNull()
}