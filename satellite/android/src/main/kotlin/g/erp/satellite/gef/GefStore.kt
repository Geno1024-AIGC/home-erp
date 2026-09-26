package g.erp.satellite.gef

import android.content.Context
import java.io.File

class GefStore(context: Context) {

    private val dir = File(context.filesDir, "gefs")
    private val maxBytes = 2 * 1024 * 1024

    fun list(): List<Gef.Bundle> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.endsWith(".gef") }
            ?.mapNotNull { runCatching { Gef.parse(it.readText()) }.getOrNull() }
            ?.sortedBy { it.id }
            ?: emptyList()
    }

    fun install(bytes: ByteArray): String {
        if (bytes.size > maxBytes) throw IllegalArgumentException("文件过大（超过 2MB）")
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

    fun uninstall(id: String) {
        File(dir, "$id.gef").delete()
    }
}