package g.erp.gefs

import g.sw.gef.HtmlGef
import g.sw.spi.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Packs the satellite feature packages shipped with every canary release.
 * Each feature is one v0.1 HTML zip GEF: a self-contained page that reads a
 * Star API list through `window.Erp` and renders a table. Everything is
 * generated from [FEATURES], so the whole feature list lives in one spec table.
 */
object FeaturePack {

    data class Column(val field: String, val label: String, val kind: String)

    data class AddSpec(val path: String, val placeholder: String, val button: String)

    data class Feature(
        val slug: String,
        val id: String,
        val name: String,
        val summary: String,
        val accent: String,
        val path: String,
        val listKey: String,
        val columns: List<Column>,
        val add: AddSpec? = null,
    )

    val FEATURES = listOf(
        Feature(
            slug = "members", id = "g.sw.erp.members", name = "成员",
            summary = "家庭成员名单", accent = "#3F51B5",
            path = "/api/members/family", listKey = "members",
            columns = listOf(Column("name", "成员名字", "text")),
            add = AddSpec("/api/members/members", "输入成员名字", "添加"),
        ),
        Feature(
            slug = "inventory", id = "g.sw.erp.inventory", name = "库存",
            summary = "家居物品与数量", accent = "#4CAF50",
            path = "/api/inventory/items", listKey = "items",
            columns = listOf(
                Column("name", "物品", "text"),
                Column("qty", "数量", "number"),
                Column("location", "位置", "text"),
            ),
        ),
        Feature(
            slug = "finances", id = "g.sw.erp.finances", name = "财务",
            summary = "收支流水", accent = "#FF9800",
            path = "/api/finances/ledger", listKey = "ledger",
            columns = listOf(
                Column("note", "说明", "text"),
                Column("amount", "金额", "money"),
            ),
        ),
        Feature(
            slug = "chores", id = "g.sw.erp.chores", name = "家务",
            summary = "家务与分工", accent = "#795548",
            path = "/api/chores/tasks", listKey = "tasks",
            columns = listOf(
                Column("title", "家务", "text"),
                Column("assignee", "负责人", "text"),
                Column("done", "状态", "check"),
            ),
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = Path.of(args[0])
        val version = args.getOrElse(1) { "0.1.dev" }
        Files.createDirectories(outDir)
        for (feature in FEATURES) {
            val pack = HtmlGef.pack(
                id = feature.id,
                name = feature.name,
                version = version,
                summary = feature.summary,
                entry = "index.html",
                icon = "icon.png",
                files = linkedMapOf(
                    "index.html" to page(feature).toByteArray(Charsets.UTF_8),
                    "icon.png" to png(feature),
                ),
            )
            val round = HtmlGef.unpack(pack)
            check(round.id == feature.id) { "round-trip id mismatch for ${feature.slug}" }
            check(round.files.keys == setOf("index.html", "icon.png")) { "unexpected assets for ${feature.slug}" }
            Files.write(outDir.resolve("${feature.slug}.gef"), pack)
        }
        println("[gefs] packed ${FEATURES.size} feature packages v$version -> ${outDir.toAbsolutePath()}")
    }

    private fun page(f: Feature): String {
        val config = linkedMapOf<String, Any?>(
            "title" to f.name,
            "summary" to f.summary,
            "accent" to f.accent,
            "path" to f.path,
            "key" to f.listKey,
            "columns" to f.columns.map { linkedMapOf("f" to it.field, "l" to it.label, "k" to it.kind) },
            "add" to f.add?.let {
                linkedMapOf("path" to it.path, "placeholder" to it.placeholder, "button" to it.button)
            },
        )
        return PAGE_TEMPLATE.replace("__CONFIG__", Json.write(config))
    }

    /**
     * Renders the feature mark as a 64x64 antialiased PNG: a rounded accent
     * plate with a white geometric mark on it. Every mark is composed of
     * circles, half-discs, round-rects and round-capped capsules drawn at
     * [SUPERSAMPLE]x and box-filtered down, so curves and diagonals stay
     * smooth instead of stepping like a bitmap glyph.
     */
    private fun png(feature: Feature): ByteArray {
        val w = 64
        val h = 64
        val s = SUPERSAMPLE
        val n = w * s

        val plate = parseColor(feature.accent)
        val paper = WHITE // the drawer row background
        val ink = WHITE

        val big = IntArray(n * n) { paper }
        fillRoundRect(big, n, n / 24, n / 24, n - n / 24, n - n / 24, n * 4 / 16, plate)
        drawMark(feature.slug, big, n, s, plate, ink)

        // box-filter down to 64x64
        val raw = ByteArray(h * (1 + w * 3))
        var i = 0
        for (y in 0 until h) {
            raw[i++] = 0
            for (x in 0 until w) {
                var sr = 0L
                var sg = 0L
                var sb = 0L
                for (dy in 0 until s) {
                    for (dx in 0 until s) {
                        val c = big[(y * s + dy) * n + (x * s + dx)]
                        sr += (c shr 16) and 0xFF
                        sg += (c shr 8) and 0xFF
                        sb += c and 0xFF
                    }
                }
                val cnt = s * s
                raw[i++] = (sr / cnt).toByte()
                raw[i++] = (sg / cnt).toByte()
                raw[i++] = (sb / cnt).toByte()
            }
        }
        val ihdr = byteBuffer(w, h) + byteArrayOf(8, 2, 0, 0, 0)
        val bytes = PNG_SIGNATURE + chunk("IHDR", ihdr) + chunk("IDAT", deflate(raw)) + chunk("IEND", byteArrayOf())
        val image = javax.imageio.ImageIO.read(bytes.inputStream())
        check(image != null && image.width == w && image.height == h) { "generated icon is not a decodable ${w}x$h png" }
        return bytes
    }

    /**
     * Paints the white feature mark for [slug] over the accent plate, using a
     * handful of primitives in 64-space coordinates. Marks never touch the
     * plate border, so the rounded corners stay clean at the drawer's 22dp.
     */
    private fun drawMark(slug: String, px: IntArray, n: Int, s: Int, plate: Int, ink: Int) {
        fun disc(cx: Float, cy: Float, r: Float, color: Int) {
            val ax = cx * s
            val ay = cy * s
            val ar = r * s
            val x0 = (ax - ar).toInt().coerceAtLeast(0)
            val x1 = (ax + ar).toInt().coerceAtMost(n - 1)
            val y0 = (ay - ar).toInt().coerceAtLeast(0)
            val y1 = (ay + ar).toInt().coerceAtMost(n - 1)
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val dx = x + 0.5f - ax
                    val dy = y + 0.5f - ay
                    if (dx * dx + dy * dy <= ar * ar) px[y * n + x] = color
                }
            }
        }

        // upper half of a disc: the dome of a pair of shoulders
        fun dome(cx: Float, cy: Float, r: Float, color: Int) {
            val ax = cx * s
            val ay = cy * s
            val ar = r * s
            val x0 = (ax - ar).toInt().coerceAtLeast(0)
            val x1 = (ax + ar).toInt().coerceAtMost(n - 1)
            val y0 = (ay - ar).toInt().coerceAtLeast(0)
            val y1 = ay.toInt().coerceAtMost(n - 1)
            for (y in y0..y1) {
                if (y + 0.5f > ay) continue
                for (x in x0..x1) {
                    val dx = x + 0.5f - ax
                    val dy = y + 0.5f - ay
                    if (dx * dx + dy * dy <= ar * ar) px[y * n + x] = color
                }
            }
        }

        fun rrect(x0: Float, y0: Float, x1: Float, y1: Float, r: Float, color: Int) {
            val ax0 = x0 * s
            val ay0 = y0 * s
            val ax1 = x1 * s
            val ay1 = y1 * s
            val ar = r * s
            val px0 = ax0.toInt().coerceAtLeast(0)
            val px1 = ax1.toInt().coerceAtMost(n - 1)
            val py0 = ay0.toInt().coerceAtLeast(0)
            val py1 = ay1.toInt().coerceAtMost(n - 1)
            for (y in py0..py1) {
                for (x in px0..px1) {
                    val cx = x + 0.5f
                    val cy = y + 0.5f
                    if (cx < ax0 || cx > ax1 || cy < ay0 || cy > ay1) continue
                    val dx = (ax0 + ar - cx).coerceAtLeast(0f).coerceAtLeast(cx - (ax1 - ar))
                    val dy = (ay0 + ar - cy).coerceAtLeast(0f).coerceAtLeast(cy - (ay1 - ar))
                    if (dx * dx + dy * dy <= ar * ar) px[y * n + x] = color
                }
            }
        }

        // round-capped stroke from (x0,y0) to (x1,y1)
        fun capsule(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
            val ax = x0 * s
            val ay = y0 * s
            val bx = x1 * s
            val by = y1 * s
            val hw = w * s / 2
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy
            val x0i = (minOf(ax, bx) - hw).toInt().coerceAtLeast(0)
            val x1i = (maxOf(ax, bx) + hw).toInt().coerceAtMost(n - 1)
            val y0i = (minOf(ay, by) - hw).toInt().coerceAtLeast(0)
            val y1i = (maxOf(ay, by) + hw).toInt().coerceAtMost(n - 1)
            for (y in y0i..y1i) {
                for (x in x0i..x1i) {
                    val dx = x + 0.5f - ax
                    val dy = y + 0.5f - ay
                    val t = if (len2 <= 0f) 0f else ((dx * vx + dy * vy) / len2).coerceIn(0f, 1f)
                    val ex = dx - t * vx
                    val ey = dy - t * vy
                    if (ex * ex + ey * ey <= hw * hw) px[y * n + x] = color
                }
            }
        }

        fun person(headX: Float, headY: Float, headR: Float, shoulderY: Float, shoulderR: Float, color: Int) {
            disc(headX, headY, headR, color)
            dome(headX, shoulderY, shoulderR, color)
        }

        when (slug) {
            // one member in front, one behind: the halo carves the separation
            "members" -> {
                person(43f, 23f, 6.5f, 43f, 10f, ink)
                person(23f, 25f, 10.5f, 48f, 16f, plate)
                person(23f, 25f, 7.5f, 48f, 12f, ink)
            }
            // a parcel: lid seam plus the tape strip running over the lid
            "inventory" -> {
                rrect(11f, 15f, 53f, 49f, 5f, ink)
                rrect(11f, 25f, 53f, 29f, 0f, plate)
                rrect(29f, 15f, 35f, 25f, 0f, plate)
            }
            // three ascending bars on a baseline
            "finances" -> {
                rrect(13f, 46f, 51f, 50f, 2f, ink)
                rrect(14.5f, 35f, 23.5f, 48f, 2f, ink)
                rrect(27.5f, 26f, 36.5f, 48f, 2f, ink)
                rrect(40.5f, 16f, 49.5f, 48f, 2f, ink)
            }
            // a bold check mark
            "chores" -> {
                capsule(15f, 34f, 25f, 44f, 7f, ink)
                capsule(25f, 44f, 48f, 18f, 7f, ink)
            }
        }
    }

    /** Fills a rounded rect with a solid colour. */
    private fun fillRoundRect(px: IntArray, n: Int, x0: Int, y0: Int, x1: Int, y1: Int, r: Int, color: Int) {
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val dx = when {
                    x < x0 + r -> x0 + r - x
                    x >= x1 - r -> x - (x1 - r - 1)
                    else -> 0
                }
                val dy = when {
                    y < y0 + r -> y0 + r - y
                    y >= y1 - r -> y - (y1 - r - 1)
                    else -> 0
                }
                val inside = dx * dx + dy * dy <= r * r + 4
                if (inside) px[y * n + x] = color
            }
        }
    }

    private fun parseColor(hex: String): Int {
        val v = hex.removePrefix("#").toLong(16)
        return (0xFFL shl 24 or (v and 0xFFFFFF)).toInt()
    }

    private const val WHITE = 0xFFFFFF
    private const val SUPERSAMPLE = 8

    private fun byteBuffer(vararg values: Int): ByteArray =
        ByteArray(values.size * 4).also { acc ->
            var idx = 0
            for (v in values) {
                acc[idx++] = (v ushr 24).toByte()
                acc[idx++] = (v ushr 16).toByte()
                acc[idx++] = (v ushr 8).toByte()
                acc[idx++] = v.toByte()
            }
        }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value
        return byteBuffer(data.size) + typeBytes + data + byteBuffer(crc.toInt())
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, false)
        d.setInput(data)
        d.finish()
        val out = java.io.ByteArrayOutputStream(data.size / 2)
        val buf = ByteArray(8192)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    private val PNG_SIGNATURE: ByteArray =
        byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
}

internal const val PAGE_TEMPLATE: String = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{title}</title>
<style>
:root { --accent: #4CAF50; }
body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif; background: #f2f2f7; color: #1c1c1e; }
header { background: var(--accent); color: #fff; padding: 14px 16px; display: flex; align-items: center; justify-content: space-between; }
header h1 { margin: 0; font-size: 18px; font-weight: 600; }
header button { background: rgba(255,255,255,.22); color: #fff; border: 0; border-radius: 8px; padding: 6px 14px; font-size: 13px; }
.card { background: #fff; border-radius: 12px; margin: 12px; padding: 12px 14px; box-shadow: 0 1px 3px rgba(0,0,0,.08); }
table { width: 100%; border-collapse: collapse; font-size: 14px; }
th { text-align: left; color: #8e8e93; font-weight: 500; padding: 6px 4px; }
td { padding: 8px 4px; border-top: 1px solid #eee; }
td.money { text-align: right; font-variant-numeric: tabular-nums; }
td.money.up { color: #e53935; }
td.money.down { color: #43a047; }
.status { margin: 4px 16px 12px; font-size: 12px; color: #8e8e93; min-height: 16px; }
.empty { text-align: center; color: #b0b0b4; padding: 18px 0; }
.add { display: flex; gap: 8px; }
input { flex: 1; border: 1px solid #d1d1d6; border-radius: 8px; padding: 8px 10px; font-size: 14px; }
.add button { background: var(--accent); color: #fff; border: 0; border-radius: 8px; padding: 8px 16px; font-size: 14px; }
</style>
</head>
<body>
<header><h1 id="title"></h1><button onclick="load()">刷新</button></header>
<div class="card"><table id="tbl"></table></div>
<div class="card" id="addCard" style="display:none">
  <div class="add"><input id="addInput" placeholder=""><button id="addBtn"></button></div>
</div>
<div class="status" id="status"></div>
<script>
const C = __CONFIG__;
document.title = C.title;
document.getElementById('title').textContent = C.title;
document.documentElement.style.setProperty('--accent', C.accent);
const tbl = document.getElementById('tbl');

function load() {
  status('加载中…');
  Erp.apiGet(C.path, 'onResp');
}
function onResp(data, err) {
  if (err) { render([], err); return; }
  let obj;
  try { obj = JSON.parse(data); } catch (e) { render([], '返回数据无法解析'); return; }
  const list = obj[C.key];
  if (!Array.isArray(list)) { render([], '返回数据缺少 ' + C.key); return; }
  render(list, null);
}
function render(rows, err) {
  if (err) {
    status(err === '401' ? '未登录或登录已过期，请到 设置 → 账号 登录。' : err);
  } else {
    status('共 ' + rows.length + ' 条');
  }
  let html = '<tr>';
  for (const c of C.columns) html += '<th>' + c.l + '</th>';
  html += '</tr>';
  if (rows.length === 0) {
    html += '<tr><td class="empty" colspan="' + C.columns.length + '">暂无数据</td></tr>';
  } else {
    for (const row of rows) {
      html += '<tr>';
      for (const c of C.columns) html += '<td class="' + cellClass(c.k, row[c.f]) + '">' + cell(c.k, row[c.f]) + '</td>';
      html += '</tr>';
    }
  }
  tbl.innerHTML = html;
}
function cell(k, v) {
  if (k === 'money') {
    const n = Number(v) || 0;
    return (n < 0 ? '-¥' + (-n) : '+¥' + n);
  }
  if (k === 'check') return v ? '✔ 已完成' : '—';
  if (v === null || v === undefined) return '';
  return String(v);
}
function cellClass(k, v) {
  if (k === 'money') return 'money ' + ((Number(v) || 0) < 0 ? 'down' : 'up');
  return '';
}
function status(t) { document.getElementById('status').textContent = t; }
function addEntry() {
  const input = document.getElementById('addInput');
  const value = input.value.trim();
  if (!value) { status('请输入内容。'); return; }
  input.disabled = true;
  Erp.apiPost(C.add.path, value, 'onSent');
}
function onSent(data, err) {
  const input = document.getElementById('addInput');
  input.disabled = false;
  if (err) { status(err); return; }
  input.value = '';
  status('已添加');
  load();
}
if (C.add) {
  document.getElementById('addCard').style.display = 'block';
  document.getElementById('addInput').placeholder = C.add.placeholder;
  document.getElementById('addBtn').textContent = C.add.button;
  document.getElementById('addBtn').onclick = addEntry;
  document.getElementById('addInput').addEventListener('keydown', function (e) { if (e.key === 'Enter') addEntry(); });
}
load();
</script>
</body>
</html>"""