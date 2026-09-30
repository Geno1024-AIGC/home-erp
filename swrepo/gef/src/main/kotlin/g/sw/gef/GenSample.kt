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
                "action" to "url:GET /api/inventory/items",
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
                "action" to "url:GET /api/members/family",
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
        writeHtml(dir, "html-demo.gef", buildHtmlDemo())
        println("[gef] samples written to $dir")
    }

    private fun buildHtmlDemo(): HtmlGef.Package = HtmlGef.Package(
        id = "g.erp.satellite.demo",
        name = "HTML 演示",
        version = "0.1",
        summary = "zip + HTML + WebView 演示",
        entry = "index.html",
        icon = "icon",
        files = mapOf(
            "index.html" to HTML_INDEX.toByteArray(),
            "icon" to icon("inventory"),
        ),
    )

    private fun writeHtml(dir: Path, file: String, pkg: HtmlGef.Package) {
        val target = dir.resolve(file)
        Files.createDirectories(dir)
        val bytes = HtmlGef.pack(
            id = pkg.id, name = pkg.name, version = pkg.version, summary = pkg.summary,
            entry = pkg.entry, icon = pkg.icon, files = pkg.files,
        )
        Files.write(target, bytes)
        val back = HtmlGef.unpack(Files.readAllBytes(target))
        check(back == pkg) { "generated $file does not round-trip" }
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

    private val HTML_INDEX = """
<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>GEF HTML 演示</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;padding:max(16px,env(safe-area-inset-top)) 16px 32px;background:#f4f6f9;color:#222}
h1{font-size:18px;margin:0 0 6px}
p{font-size:14px;margin:4px 0}
button{margin:12px 0;padding:8px 14px;border:0;border-radius:6px;background:#2f6fed;color:#fff;font-size:14px}
table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px;overflow:hidden}
th,td{padding:8px 10px;border-bottom:1px solid #e4e7ec;text-align:left;font-size:14px}
th{background:#eef1f5;font-weight:600}
.error{color:#b3261e;font-size:13px}
.ok{color:#4d7c0f;font-size:13px}
ul{background:#fff;border-radius:8px;padding:8px 8px 8px 28px;font-size:14px}
</style>
</head>
<body>
<h1>GEF HTML 演示</h1>
<p class="ok">这个页面来自 zip 里的 index.html,由 WebView 渲染,数据都走 Erp 桥。</p>
<button onclick="loadAll()">重新加载</button>
<h2>库存</h2>
<div id="inventory"><span class="error">加载中…</span></div>
<h2>成员</h2>
<div id="members"><span class="error">加载中…</span></div>
<script>
function api(path, cb){ window.Erp.apiGet(path, function(err, data){ cb(err, data); }); }
function esc(s){ return String(s == null ? '' : s).replace(/[&<>"']/g, function(c){
  return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
function loadInventory(){
  api('/api/inventory/items', function(err, data){
    var box = document.getElementById('inventory');
    if (err) { box.innerHTML = '<span class="error">失败: ' + esc(err) + '</span>'; return; }
    var items = (data && data.items) || [];
    if (!items.length) { box.innerHTML = '<em>暂无物品</em>'; return; }
    var rows = items.map(function(it){ return '<tr><td>' + esc(it.name) + '</td><td>' + esc(it.qty) +
      '</td><td>' + esc(it.location) + '</td></tr>'; }).join('');
    box.innerHTML = '<table><thead><tr><th>名称</th><th>数量</th><th>位置</th></tr></thead><tbody>' + rows + '</tbody></table>';
  });
}
function loadMembers(){
  api('/api/members/family', function(err, data){
    var box = document.getElementById('members');
    if (err) { box.innerHTML = '<span class="error">失败: ' + esc(err) + '</span>'; return; }
    var list = (data && data.members) || [];
    if (!list.length) { box.innerHTML = '<em>暂无成员</em>'; return; }
    box.innerHTML = '<ul>' + list.map(function(m){ return '<li>' + esc(m.name) + '（' + esc(m.id) + '）</li>'; }).join('') + '</ul>';
  });
}
function loadAll(){ loadInventory(); loadMembers(); }
loadAll();
</script>
</body>
</html>
    """.trimIndent()

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