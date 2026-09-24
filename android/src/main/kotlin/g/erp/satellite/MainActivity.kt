package g.erp.satellite

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import g.erp.satellite.json.Json
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var contentHost: LinearLayout
    private lateinit var titleView: TextView

    private var baseUrl = StarClient.DEFAULT_BASE
    private var current: Feature = Features.MEMBERS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (drawer.visibility == View.VISIBLE) closeDrawer()
            }
        }
        baseUrl = getSharedPreferences("sat", Context.MODE_PRIVATE)
            .getString("baseUrl", StarClient.DEFAULT_BASE)!!
        buildUi()
        show(current)
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(240, 242, 245))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(4), dp(12))
            setBackgroundColor(Color.WHITE)
            addView(TextView(this@MainActivity).apply {
                text = "☰"
                textSize = 22f
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { openDrawer() }
            })
            titleView = TextView(this@MainActivity).apply {
                textSize = 18f
                setTypeface(Typeface.DEFAULT_BOLD)
            }
            addView(titleView)
        }

        contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(contentHost)

        scrim = View(this).apply {
            setBackgroundColor(0x66000000)
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        drawer = buildDrawer()
        root.addView(drawer)

        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                drawer.visibility != View.VISIBLE && event.x < dp(32)
            ) {
                openDrawer()
            }
            false
        }

        setContentView(root)
    }

    private fun buildDrawer(): LinearLayout {
        val width = dp(280)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
            setPadding(0, dp(24), 0, dp(16))
            translationX = -width.toFloat()
            visibility = View.GONE
        }

        panel.addView(TextView(this).apply {
            text = "Home ERP 卫星"
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(24), 0, dp(24), dp(12))
        })

        for (feature in Features.ALL) {
            panel.addView(drawerItem(feature.title) {
                select(feature)
                closeDrawer()
            })
        }

        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        })

        panel.addView(TextView(this).apply {
            text = "恒星地址"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(dp(24), dp(8), dp(24), dp(2))
        })

        val url = EditText(this).apply {
            setText(baseUrl)
            setSingleLine(true)
        }
        val connect = Button(this).apply {
            text = "连接"
            setOnClickListener {
                baseUrl = url.text.toString().trim().ifEmpty { StarClient.DEFAULT_BASE }
                getSharedPreferences("sat", Context.MODE_PRIVATE)
                    .edit().putString("baseUrl", baseUrl).apply()
                closeDrawer()
                show(current)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            addView(url, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(connect)
        }
        panel.addView(row)

        var version = packageManager?.getPackageInfo(packageName, 0)?.versionName ?: "?"
        panel.addView(TextView(this).apply {
            text = "版本 $version"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        return panel
    }

    private fun drawerItem(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 16f
        setPadding(dp(24), dp(14), dp(24), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- drawer

    private fun openDrawer() {
        drawer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(200).start()
        drawer.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeDrawer() {
        scrim.animate().alpha(0f).setDuration(180).withEndAction {
            scrim.visibility = View.GONE
        }.start()
        drawer.animate().translationX(-dp(280).toFloat()).setDuration(180).withEndAction {
            drawer.visibility = View.GONE
        }.start()
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java", ReplaceWith("onBackInvokedDispatcher"))
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) closeDrawer() else super.onBackPressed()
    }

    // ---------------------------------------------------------------- screens

    private fun select(feature: Feature) {
        current = feature
        show(feature)
    }

    private fun show(feature: Feature) {
        titleView.text = feature.title
        contentHost.removeViews(1, contentHost.childCount - 1)
        contentHost.addView(message("加载中…"))

        executor.execute {
            val result = runCatching { StarClient.get(baseUrl, feature.path) }
            runOnUiThread {
                contentHost.removeViews(1, contentHost.childCount - 1)
                result.fold(
                    onSuccess = { body -> render(feature, body) },
                    onFailure = { e -> renderError(feature, e) },
                )
            }
        }
    }

    private fun render(feature: Feature, body: String) {
        val items = parseList(body, feature.listKey)
        if (items.isEmpty()) {
            contentHost.addView(message("暂无数据"))
            return
        }
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (item in items) {
            rows.addView(TextView(this).apply {
                text = feature.row(item)
                textSize = 15f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor(Color.WHITE)
            })
            rows.addView(View(this).apply {
                setBackgroundColor(Color.rgb(230, 232, 236))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
            })
        }
        contentHost.addView(rows, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun renderError(feature: Feature, e: Throwable) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }
        box.addView(TextView(this).apply {
            text = "无法连接恒星：${e.message ?: "未知错误"}"
            textSize = 14f
            setTextColor(Color.parseColor("#B3261E"))
            gravity = Gravity.CENTER_HORIZONTAL
        })
        box.addView(Button(this).apply {
            text = "重试"
            setOnClickListener { show(feature) }
        })
        contentHost.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun message(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.GRAY)
        gravity = Gravity.CENTER
        setPadding(0, dp(48), 0, 0)
    }

    private fun parseList(body: String, key: String): List<Map<String, Any?>> {
        val root = Json.parse(body) as? Map<*, *> ?: return emptyList()
        val list = root[key] as? List<*> ?: return emptyList()
        return list.mapNotNull { it as? Map<*, *> }.map { map ->
            map.entries.associate { (k, v) -> k.toString() to v }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

internal fun formatMoney(value: Double): String =
    String.format(Locale.ROOT, "%.2f", value)

internal fun intText(value: Any?): String = when (value) {
    is Double -> if (!value.isInfinite() && value == Math.floor(value)) value.toLong().toString() else value.toString()
    is Number -> value.toLong().toString()
    else -> "?"
}

internal abstract class Feature(
    val title: String,
    val path: String,
    val listKey: String,
) {
    abstract fun row(item: Map<String, Any?>): String
}

internal object Features {
    val MEMBERS = object : Feature("成员", "/api/members/family", "members") {
        override fun row(item: Map<String, Any?>): String =
            item["name"] as? String ?: item["id"]?.toString() ?: "?"
    }

    val INVENTORY = object : Feature("库存", "/api/inventory/items", "items") {
        override fun row(item: Map<String, Any?>): String = buildString {
            append(item["name"] as? String ?: "?")
            append("  ×")
            append(intText(item["qty"]))
            val location = item["location"] as? String
            if (!location.isNullOrEmpty()) {
                append("  ·  ")
                append(location)
            }
        }
    }

    val FINANCES = object : Feature("账单", "/api/finances/ledger", "ledger") {
        override fun row(item: Map<String, Any?>): String = buildString {
            val amount = item["amount"] as? Double ?: 0.0
            if (amount < 0) append("支出  ¥").append(formatMoney(-amount))
            else append("收入  ¥").append(formatMoney(amount))
            val note = item["note"] as? String
            if (!note.isNullOrEmpty()) {
                append("  ·  ")
                append(note)
            }
        }
    }

    val CHORES = object : Feature("家务", "/api/chores/tasks", "tasks") {
        override fun row(item: Map<String, Any?>): String = buildString {
            append(item["title"] as? String ?: "?")
            val assignee = item["assignee"] as? String
            if (!assignee.isNullOrEmpty()) {
                append("  ·  ")
                append(assignee)
            }
            append(if (item["done"] == true) "  已完成" else "  未完成")
        }
    }

    val ALL = listOf(MEMBERS, INVENTORY, FINANCES, CHORES)
}