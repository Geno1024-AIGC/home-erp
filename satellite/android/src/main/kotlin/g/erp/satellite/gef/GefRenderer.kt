package g.erp.satellite.gef

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import g.erp.satellite.json.Json

/**
 * Renders a GEF page (UI DSL v1 subset) into a LinearLayout.
 * Supported: page, text, image (icon/emoji), row, column, list, button, if.
 * Unknown nodes are skipped with a visible placeholder, per FORMAT.md.
 * List data is pre-fetched by the caller and passed in as [data] keyed by
 * `repeat`; buttons dispatch back via [onRefresh] / [onPost].
 */
class GefRenderer(
    private val activity: Activity,
    private val bundle: Gef.Bundle,
    private val data: Map<String, Any?>,
    private val onRefresh: () -> Unit,
    private val onPost: (String) -> Unit,
) {

    fun pageTitle(): String {
        val ui = ui()
        return (ui["title"] as? String)?.takeIf { it.isNotBlank() } ?: bundle.name
    }

    fun build(out: LinearLayout) {
        buildChildren(childrenOf(ui()), data, out)
    }

    private fun ui(): Map<*, *> =
        runCatching { Json.parse(bundle.ui) as? Map<*, *> }.getOrNull() ?: emptyMap<Any?, Any?>()

    private fun childrenOf(node: Map<*, *>): List<*> = node["children"] as? List<*> ?: emptyList<Any?>()

    private fun buildChildren(children: List<*>, context: Any?, out: LinearLayout) {
        for (child in children) (child as? Map<*, *>)?.let { buildNode(it, context, out) }
    }

    private fun buildNode(node: Map<*, *>, context: Any?, out: LinearLayout) {
        when (val type = node["type"]) {
            "text" -> out.addView(text(node, context), matchWidth())
            "image" -> out.addView(image(node), wrap())
            "row" -> {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                buildChildren(childrenOf(node), context, row)
                out.addView(row, matchWidth())
            }
            "column" -> {
                val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                buildChildren(childrenOf(node), context, column)
                out.addView(column, matchWidth())
            }
            "list" -> buildList(node, context, out)
            "button" -> out.addView(button(node), matchWidth())
            "if" -> if (truthy(bindValue(node["bind"], context))) buildChildren(childrenOf(node), context, out)
            else -> out.addView(TextView(activity).apply {
                text = if (type == null) "[空节点]" else "[暂不支持: $type]"
                textSize = 12f
                setTextColor(Color.GRAY)
            }, matchWidth())
        }
    }

    private fun text(node: Map<*, *>, context: Any?): TextView = TextView(activity).apply {
        val static = node["text"] as? String
        text = static ?: display(bindValue(node["bind"], context))
        textSize = 15f
        setTextColor(Color.rgb(30, 30, 30))
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    private fun image(node: Map<*, *>): View {
        val src = node["src"] as? String ?: ""
        if (src == "icon") {
            val icon = Gef.iconBitmap(bundle)
            if (icon != null) {
                return ImageView(activity).apply {
                    setImageBitmap(icon)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(12), dp(4), dp(4), dp(4)) }
                }
            }
        }
        val emoji = src.takeIf { it.startsWith("emoji:") }?.let { raw ->
            runCatching { String(Character.toChars(raw.removePrefix("emoji:").toInt(16))) }.getOrNull()
        }
        if (emoji != null) {
            return TextView(activity).apply {
                text = emoji
                textSize = dp(28).toFloat()
                setTypeface(Typeface.DEFAULT)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(12), dp(4), dp(4), dp(4)) }
            }
        }
        return ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(12), dp(4), dp(4), dp(4)) }
        }
    }

    private fun buildList(node: Map<*, *>, context: Any?, out: LinearLayout) {
        val repeat = node["repeat"] as? String ?: return
        val arr = (context as? Map<*, *>)?.get(repeat) as? List<*>
        if (arr.isNullOrEmpty()) {
            out.addView(TextView(activity).apply {
                text = "暂无数据"
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }, matchWidth())
            return
        }
        val item = node["item"] as? Map<*, *> ?: return
        for (element in arr) {
            buildNode(item, mapOf("item" to element), out)
            out.addView(View(activity).apply {
                setBackgroundColor(Color.rgb(230, 232, 236))
            }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        }
    }

    private fun button(node: Map<*, *>): Button = Button(activity).apply {
        val label = node["label"] as? String ?: ""
        val action = node["action"] as? String
        val parsed = urlAction(action)
        text = if (parsed == null && action != null) "$label（暂不可用）" else label
        isEnabled = parsed != null
        setOnClickListener {
            when (parsed?.first) {
                "GET" -> onRefresh()
                "POST" -> parsed.second?.let { onPost(it) }
            }
        }
    }

    private fun urlAction(action: String?): Pair<String, String>? {
        if (action == null || !action.startsWith("url:")) return null
        val rest = action.removePrefix("url:").trim()
        val sp = rest.indexOf(' ')
        if (sp <= 0) return null
        val method = rest.substring(0, sp).trim().uppercase()
        val path = rest.substring(sp + 1).trim()
        if (path.isEmpty()) return null
        return method to path
    }

    private fun bindValue(path: Any?, context: Any?): Any? {
        if (path !is String) return null
        var current = context
        for (segment in path.split('.')) {
            current = (current as? Map<*, *>)?.get(segment) ?: return null
        }
        return current
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    private fun display(value: Any?): String = when (value) {
        null -> ""
        is Double -> if (!value.isInfinite() && value == Math.floor(value)) value.toLong().toString() else value.toString()
        is Map<*, *> -> value.toString()
        else -> value.toString()
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun wrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private companion object {
        val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}