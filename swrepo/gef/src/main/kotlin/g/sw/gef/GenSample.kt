package g.sw.gef

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object GenSample {

    private val INVENTORY_UI: Any = mapOf(
        "type" to "page",
        "title" to "库存",
        "children" to listOf(
            mapOf(
                "type" to "text",
                "text" to "我的家居物品",
            ),
            mapOf(
                "type" to "list",
                "repeat" to "items",
                "item" to mapOf(
                    "type" to "row",
                    "children" to listOf(
                        mapOf("type" to "image", "src" to "icon"),
                        mapOf("type" to "text", "bind" to "item.name"),
                        mapOf("type" to "text", "bind" to "item.qty"),
                        mapOf("type" to "text", "bind" to "item.location"),
                    ),
                ),
            ),
            mapOf(
                "type" to "button",
                "label" to "添加物品",
                "action" to "url:POST /api/inventory/items",
            ),
        ),
    )

    private val MEMBERS_UI: Any = mapOf(
        "type" to "page",
        "title" to "成员",
        "children" to listOf(
            mapOf("type" to "text", "text" to "家庭成员"),
            mapOf(
                "type" to "list",
                "repeat" to "members",
                "item" to mapOf(
                    "type" to "row",
                    "children" to listOf(
                        mapOf("type" to "image", "src" to "emoji:1f464"),
                        mapOf("type" to "text", "bind" to "item.name"),
                        mapOf("type" to "text", "bind" to "item.id"),
                    ),
                ),
            ),
            mapOf("type" to "button", "label" to "刷新成员", "action" to "url:GET /api/members/family"),
            mapOf("type" to "button", "label" to "添加成员", "action" to "url:POST /api/members"),
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Paths.get(args.getOrElse(0) { "samples" })
        val inventory = Gef.Bundle(
            id = "g.sw.erp.inventory",
            name = "库存",
            version = "0.1",
            summary = "家居物品与库存",
            meta = mapOf("platforms" to "android,web"),
            icon = icon("inventory"),
            ui = prettyJson(INVENTORY_UI),
        )
        val members = Gef.Bundle(
            id = "g.sw.erp.members",
            name = "成员",
            version = "0.1",
            summary = "家庭成员与用户",
            meta = mapOf("platforms" to "android,web"),
            icon = icon("members"),
            ui = prettyJson(MEMBERS_UI),
        )
        write(dir, "inventory.gef", inventory)
        write(dir, "members.gef", members)
        println("[gef] samples written to $dir")
    }

    private fun write(dir: Path, file: String, bundle: Gef.Bundle) {
        val target = dir.resolve(file)
        Files.createDirectories(dir)
        Files.writeString(target, Gef.write(bundle))
        val back = Gef.parse(Files.readString(target))
        check(back == bundle) { "generated $file does not round-trip" }
    }

    private fun icon(name: String): ByteArray =
        (GenSample::class.java.getResourceAsStream("/icons/$name.png")
            ?: error("missing resource icon/$name.png")).readBytes()

    private fun prettyJson(value: Any?): String = pretty(value, "  ")

    private fun pretty(v: Any?, ind: String): String = when (v) {
        is Map<*, *> -> buildString {
            append('{')
            if (v.isNotEmpty()) {
                append('\n')
                val it = v.entries.iterator()
                while (it.hasNext()) {
                    val (k, x) = it.next()
                    append(ind).append(q(k.toString())).append(": ").append(pretty(x, ind + "  "))
                    if (it.hasNext()) append(',')
                    append('\n')
                }
                append(ind.dropLast(2))
            }
            append('}')
        }
        is List<*> -> buildString {
            append('[')
            if (v.isNotEmpty()) {
                append('\n')
                val it = v.iterator()
                while (it.hasNext()) {
                    append(ind).append(pretty(it.next(), ind + "  "))
                    if (it.hasNext()) append(',')
                    append('\n')
                }
                append(ind.dropLast(2))
            }
            append(']')
        }
        is String -> q(v)
        null -> "null"
        else -> v.toString()
    }

    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}