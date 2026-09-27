package g.erp.satellite

import android.content.Context
import g.erp.satellite.json.Json
import java.io.File

/**
 * 卫星拓扑:本地持久化的“恒星 + 全部行星”清单。每次启动优先连恒星,
 * 连不上就按顺序试行星,拿到新拓扑后写回本地(卫星也存储数据)。
 */
internal object Topology {

    const val CONNECT_TIMEOUT_MS = 2500

    fun file(ctx: Context): File = File(ctx.filesDir, "topology.json")

    fun load(ctx: Context): Map<String, Any?>? {
        val f = file(ctx)
        if (!f.isFile || !f.canRead()) return null
        val parsed = runCatching { Json.parse(f.readText()) }.getOrNull()
        return normalize(parsed)?.takeIf { it.isNotEmpty() }
    }

    fun save(ctx: Context, topology: Map<String, Any?>) {
        runCatching { file(ctx).writeText(Json.write(topology)) }
    }

    fun star(topology: Map<String, Any?>?): Map<String, Any?>? =
        normalize(topology?.get("star"))

    fun planets(topology: Map<String, Any?>?): List<Map<String, Any?>> =
        (topology?.get("planets") as? List<*>).orEmpty().mapNotNull { normalize(it) }

    fun httpBase(node: Map<String, Any?>): String =
        "http://${node["httpHost"]}:${node["httpPort"]}"

    fun proxyBase(node: Map<String, Any?>, starName: String?): String =
        "http://${node["httpHost"]}:${node["httpPort"]}/${starName.orEmpty()}".trimEnd('/')

    fun isReachable(node: Map<String, Any?>): Boolean = node["reachable"] != false

    fun starName(topology: Map<String, Any?>?): String? =
        star(topology)?.get("name")?.toString()

    /** 把外部 JSON 归一化为稳定 map(端口转为 Int)。 */
    fun normalize(raw: Any?): Map<String, Any?>? {
        val map = raw as? Map<*, *> ?: return null
        val out = LinkedHashMap<String, Any?>()
        map["name"]?.let { out["name"] = it.toString() }
        map["httpHost"]?.let { out["httpHost"] = it.toString() }
        (map["httpPort"] as? Number)?.let { out["httpPort"] = it.toInt() }
        map["reachable"]?.let { out["reachable"] = it }
        return out.takeIf { it.isNotEmpty() }
    }
}