package g.erp.satellite

import android.app.Activity
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import g.erp.satellite.json.Json
import g.erp.satellite.update.InstallReceiver
import g.erp.satellite.update.Updater
import java.io.File
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

    private var selectedChannel: Updater.Channel = Updater.Channel.CANARY
    private var selectedSource: Updater.Source = Updater.SOURCES.first()
    private var foundRelease: Updater.Release? = null
    private lateinit var statusView: TextView
    private lateinit var downloadBtn: Button

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

        root.setOnTouchListener(DrawerGesture())

        setContentView(root)
    }

    private fun buildDrawer(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
            setPadding(0, dp(24), 0, dp(16))
            layoutParams = FrameLayout.LayoutParams(drawerWidth.toInt(), MATCH_PARENT)
            translationX = -drawerWidth
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
        drawer.animate().translationX(-drawerWidth).setDuration(180).withEndAction {
            drawer.visibility = View.GONE
        }.start()
    }

    private val drawerWidth: Float get() = minOf(resources.displayMetrics.widthPixels / 2, dp(280)).toFloat()

    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private fun revealProgress(translation: Float): Float = (translation + drawerWidth) / drawerWidth

    private fun settleDrawer(target: Float, openNow: Boolean) {
        drawer.animate().translationX(target).setDuration(180).withEndAction {
            if (!openNow) drawer.visibility = View.GONE
        }.start()
        scrim.animate().alpha(if (openNow) 1f else 0f).setDuration(180).withEndAction {
            if (!openNow) scrim.visibility = View.GONE
        }.start()
    }

    /** DrawerLayout-style edge drag: pull out part of the drawer, snap open/close on release. */
    private inner class DrawerGesture : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startTranslation = 0f
        private var potential = false
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    potential = drawer.visibility == View.VISIBLE || event.x < dp(24).toFloat()
                    downX = event.x
                    downY = event.y
                    startTranslation = drawer.translationX
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!potential) return false
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!dragging &&
                        kotlin.math.abs(dx) > touchSlop &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                    ) {
                        dragging = true
                        drawer.animate().cancel()
                        scrim.animate().cancel()
                        drawer.visibility = View.VISIBLE
                        scrim.visibility = View.VISIBLE
                    }
                    if (dragging) {
                        val target = (startTranslation + dx).coerceIn(-drawerWidth, 0f)
                        drawer.translationX = target
                        scrim.alpha = revealProgress(target)
                        return true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val wasDragging = dragging
                    dragging = false
                    potential = false
                    if (wasDragging) {
                        val openNow = revealProgress(drawer.translationX) >= 0.5f
                        settleDrawer(if (openNow) 0f else -drawerWidth, openNow)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val openNow = revealProgress(drawer.translationX) >= 0.5f
                        settleDrawer(if (openNow) 0f else -drawerWidth, openNow)
                    }
                    dragging = false
                    potential = false
                }
            }
            return false
        }
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
        if (feature === Features.SETTINGS) {
            showSettings()
            return
        }
        contentHost.removeViews(1, contentHost.childCount - 1)
        contentHost.addView(message("加载中…"))

        val request = feature
        executor.execute {
            val result = runCatching { StarClient.get(baseUrl, request.path) }
            runOnUiThread {
                if (current !== request) return@runOnUiThread
                contentHost.removeViews(1, contentHost.childCount - 1)
                result.fold(
                    onSuccess = { body -> render(request, body) },
                    onFailure = { e -> renderError(request, e) },
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

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        val prefs = getSharedPreferences("sat", Context.MODE_PRIVATE)
        selectedChannel = Updater.Channel.from(prefs.getString("channel", null) ?: "CANARY")
        selectedSource = Updater.sourceFrom(prefs.getString("source", null) ?: "github")
        foundRelease = null

        contentHost.removeViews(1, contentHost.childCount - 1)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        col.addView(section("更新"))

        val channelSpinner = spinner(
            labels = Updater.Channel.entries.map { it.label },
            selected = Updater.Channel.entries.indexOf(selectedChannel),
            onSelect = { index ->
                selectedChannel = Updater.Channel.entries[index]
                prefs.edit().putString("channel", selectedChannel.name).apply()
            },
        )
        col.addView(settingRow("更新渠道", channelSpinner))

        val sourceSpinner = spinner(
            labels = Updater.SOURCES.map { it.label },
            selected = Updater.SOURCES.indexOf(selectedSource),
            onSelect = { index ->
                selectedSource = Updater.SOURCES[index]
                prefs.edit().putString("source", selectedSource.id).apply()
            },
        )
        col.addView(settingRow("更新源", sourceSpinner))

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(4))
        }
        col.addView(statusView)

        col.addView(Button(this).apply {
            text = "检查更新"
            setOnClickListener { checkUpdate() }
        })

        downloadBtn = Button(this).apply {
            text = "下载并安装"
            visibility = View.GONE
            setOnClickListener { downloadAndInstall() }
        }
        col.addView(downloadBtn)

        val scroll = ScrollView(this).apply { addView(col) }
        contentHost.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun checkUpdate() {
        statusView.text = "检查中…"
        downloadBtn.visibility = View.GONE
        val wantChannel = selectedChannel
        val installed = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        executor.execute {
            var update = false
            val text = runCatching {
                val found = Updater.findFor(Updater.fetchReleases(), wantChannel)
                val current = Updater.parseVersion(installed)
                if (found == null) {
                    "更新渠道「${wantChannel.label}」暂无发布。"
                } else if (found.version == null) {
                    "发布版本号无法识别：${found.tag}。"
                } else if (current != null && found.version <= current) {
                    "已是最新版本 $current。"
                } else {
                    foundRelease = found
                    update = true
                    "发现新版本 ${found.version}（发布于 ${found.publishedAt}）。"
                }
            }.getOrElse { "检查失败：${it.message ?: "未知错误"}" }
            runOnUiThread {
                statusView.text = text
                if (update) downloadBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun downloadAndInstall() {
        val release = foundRelease ?: run {
            statusView.text = "请先检查更新。"
            return
        }
        val url = Updater.downloadUrl(selectedSource, release)
        val target = File(cacheDir, "updates/${release.apkName ?: "update.apk"}")
        downloadBtn.isEnabled = false
        executor.execute {
            val result = runCatching {
                Updater.download(url, target) { done, total ->
                    val pct = if (total > 0) " ${done * 100 / total}%" else ""
                    val totalText = if (total > 0) "${total / 1024}KB" else "?"
                    runOnUiThread { statusView.text = "下载中…$pct（${done / 1024}KB/$totalText）" }
                }
            }
            runOnUiThread {
                downloadBtn.isEnabled = true
                result.onSuccess {
                    if (!target.isFile || !target.canRead() || target.length() == 0L) {
                        statusView.text = "下载的文件无效（空文件），请换一个更新源重试。"
                        return@onSuccess
                    }
                    installPackage(target)
                }.onFailure { statusView.text = "下载失败：${it.message ?: "未知错误"}" }
            }
        }
    }

    private fun installPackage(apk: File) {
        val pm = packageManager
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            statusView.text = "请允许通知，安装结果会以通知提醒。"
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            statusView.text = "需要允许本应用安装未知应用。"
            val target = if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            startActivity(target)
            return
        }
        runCatching {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = pm.packageInstaller.createSession(params)
            pm.packageInstaller.openSession(id).use { session ->
                session.openWrite(apk.name, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                session.commit(installResultPendingIntent().intentSender)
            }
        }.onFailure {
            statusView.text = "发起安装失败：${it.message ?: "未知错误"}"
        }.onSuccess {
            statusView.text = "已提交安装，结果请看系统通知。"
        }
    }

    private fun installResultPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            0,
            Intent(InstallReceiver.ACTION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun settingRow(label: String, spinner: Spinner): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
        }, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun spinner(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            setAdapter(adapter)
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onSelect(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

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

    val SETTINGS = object : Feature("设置", "", "") {
        override fun row(item: Map<String, Any?>): String = ""
    }

    val ALL = listOf(MEMBERS, INVENTORY, FINANCES, CHORES, SETTINGS)
}