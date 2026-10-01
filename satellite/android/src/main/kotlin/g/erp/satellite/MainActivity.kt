package g.erp.satellite

import android.app.Activity
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import g.erp.satellite.gef.ErpBridge
import g.erp.satellite.gef.Gef
import g.erp.satellite.gef.GefPackage
import g.erp.satellite.gef.GefRenderer
import g.erp.satellite.gef.GefStore
import g.erp.satellite.gef.HtmlGef
import g.erp.satellite.gef.NativeGef
import g.erp.satellite.gef.PackageOrder
import g.erp.satellite.gef.RepoSync
import g.erp.satellite.json.Json
import g.erp.satellite.update.InstallReceiver
import g.erp.satellite.update.Updater
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var drawer: LinearLayout
    private lateinit var drawerItems: LinearLayout
    private lateinit var scrim: View
    private lateinit var contentHost: LinearLayout
    private lateinit var titleView: TextView

    private var baseUrl = StarClient.DEFAULT_BASE
    private lateinit var savedUrls: MutableList<String>
    private lateinit var connectionLabel: TextView

    private sealed class Nav {
        object Settings : Nav()
        class Package(val pkg: GefPackage) : Nav()
    }

    private lateinit var current: Nav

    private var selectedChannel: Updater.Channel = Updater.Channel.CANARY
    private var selectedSource: Updater.Source = Updater.SOURCES.first()
    private var foundRelease: Updater.Release? = null
    private lateinit var statusView: TextView
    private lateinit var downloadBtn: Button

    private var authToken: String? = null
    private var authUser: String? = null

    private val store by lazy { GefStore(this) }

    private val packageOrder by lazy { PackageOrder(this) }
    private var repoFlash: String? = null

    private var repoSyncStatus: String? = null
    private val pickGefRequest = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (drawer.visibility == View.VISIBLE) closeDrawer()
            }
        }
        savedUrls = loadUrls().also {
            val legacy = getSharedPreferences("sat", Context.MODE_PRIVATE).getString("baseUrl", null)
            if (it.isEmpty() && !legacy.isNullOrEmpty()) {
                it.add(legacy)
                saveUrls()
            }
        }
        baseUrl = savedUrls.firstOrNull() ?: StarClient.DEFAULT_BASE
        authToken = prefs().getString("authToken", null)
        authUser = prefs().getString("authUser", null)
        current = store.list().firstOrNull()?.let { Nav.Package(it) } ?: Nav.Settings
        buildUi()
        show(current)
        bootConnection()
    }

    /**
     * 拓扑感知连接:每次启动先直连已存的恒星,不可达再挨个试行星;
     * 拿到拓扑后写回本地。整个过程单线程走 [executor],避免与界面请求竞争。
     */
    private fun bootConnection() {
        connectionLabel?.text = "正在探测连接…"
        executor.execute {
            val chosen = probeConnection()
            setBaseUrlSafely(chosen?.first)
            runOnUiThread {
                connectionLabel?.text = if (chosen == null) {
                    "无法连接恒星，请检查网络或手动选择地址"
                } else {
                    val via = chosen.second
                    "当前连接：$via ${chosen.first}"
                }
            }
        }
    }

    private fun setBaseUrlSafely(newBase: String?) {
        if (newBase.isNullOrBlank()) return
        baseUrl = newBase
        if (!savedUrls.contains(newBase)) {
            savedUrls.add(newBase)
            saveUrls()
        }
    }

    /** 返回 (数据入口, 直连/经行星 标签),探测失败返回 null。短路优先,总耗时受每个候选 [Topology.CONNECT_TIMEOUT_MS] 限制。 */
    private fun probeConnection(): Pair<String, String>? {
        val stored = Topology.load(this)
        val star = Topology.star(stored)
        val starName = Topology.starName(stored) ?: star?.get("name")?.toString() ?: "home"
        val candidates = LinkedHashMap<String, String>() // dataBase -> relyBase

        fun addCandidate(dataBase: String, relyBase: String) {
            if (dataBase.isNotBlank() && !candidates.containsKey(dataBase)) candidates[dataBase] = relyBase
        }

        star?.let { addCandidate(Topology.httpBase(it), Topology.httpBase(it)) }
        savedUrls.toList().forEach { addCandidate(it, it) }
        val planets = Topology.planets(stored)
            .sortedWith(compareByDescending { if (Topology.isReachable(it)) 1 else 0 })
        for (p in planets) {
            addCandidate(Topology.proxyBase(p, starName), Topology.httpBase(p))
        }

        for ((dataBase, relyBase) in candidates) {
            val isPlanet = dataBase != relyBase
            val path = if (isPlanet) "/planet/topology" else "/api/topology"
            val body = runCatching {
                StarClient.get(relyBase, path, null, Topology.CONNECT_TIMEOUT_MS)
            }.getOrNull() ?: continue
            val topology = normalizeTopology(body) ?: continue
            Topology.save(this, topology)
            return dataBase to if (isPlanet) "经行星" else "恒星直连"
        }
        return null
    }

    private fun normalizeTopology(body: String): Map<String, Any?>? {
        val root = Json.parse(body)
        val map = root as? Map<*, *> ?: return null
        if (map["star"] == null && (map["planets"] as? List<*>)?.isEmpty() != false) return null
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in map) out[k.toString()] = v
        return out
    }

    private fun prefs() = getSharedPreferences("sat", Context.MODE_PRIVATE)

    // Accessed from ErpBridge (single-thread [executor] serialises probe/data tasks).
    internal fun currentToken(): String? = authToken
    internal val currentBase: String get() = baseUrl
    internal val worker get() = executor

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

        drawerItems = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(drawerItems)

        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        })

        var version = packageManager?.getPackageInfo(packageName, 0)?.versionName ?: "?"
        panel.addView(TextView(this).apply {
            text = "版本 $version"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        return panel
    }

    private fun fillDrawerItems() {
        drawerItems.removeAllViews()
        val installed = packageOrder.apply(store.list())
        if (installed.isEmpty()) {
            drawerItems.addView(TextView(this).apply {
                text = "未安装功能包，请到 设置 → 软件仓库 安装"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(dp(24), dp(8), dp(24), dp(8))
            })
        } else {
            for (pkg in installed) {
                drawerItems.addView(drawerItem(pkg.name, pkg.iconBitmap) {
                    select(Nav.Package(pkg))
                    closeDrawer()
                })
            }
        }
        drawerItems.addView(drawerItem("设置") {
            select(Nav.Settings)
            closeDrawer()
        })
    }

    private fun drawerItem(label: String, icon: Bitmap? = null, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
        if (icon != null) {
            row.addView(ImageView(this).apply {
                setImageBitmap(icon)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { setMargins(0, 0, dp(10), 0) }
            })
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 16f
        })
        return row
    }

    // ---------------------------------------------------------------- drawer

    private fun openDrawer() {
        fillDrawerItems()
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
                        fillDrawerItems()
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

    private fun select(nav: Nav) {
        current = nav
        show(nav)
    }

    private fun show(nav: Nav) {
        when (nav) {
            Nav.Settings -> showSettings()
            is Nav.Package -> showPackage(nav.pkg)
        }
    }

    private fun showPackage(pkg: GefPackage, note: String? = null) {
        current = Nav.Package(pkg)
        if (savedUrls.isEmpty()) {
            showSetup()
            return
        }
        if (pkg is HtmlGef) {
            showHtmlPackage(pkg, note)
            return
        }
        val bundle = (pkg as NativeGef).bundle
        titleView.text = runCatching { GefRenderer(this, bundle, emptyMap(), {}, {}).pageTitle() }.getOrNull() ?: bundle.name
        contentHost.removeViews(1, contentHost.childCount - 1)
        contentHost.addView(message("加载中…"))
        executor.execute {
            val result = runCatching { fetchPageData(bundle) }
            runOnUiThread {
                val nav = current
                if (nav !is Nav.Package || nav.pkg.id != bundle.id) return@runOnUiThread
                contentHost.removeViews(1, contentHost.childCount - 1)
                result.fold(
                    onSuccess = { data -> renderPackage(bundle, data, note) },
                    onFailure = { e ->
                        if (e is ApiException && e.code == 401) {
                            authToken = null
                            authUser = null
                            prefs().edit().remove("authToken").remove("authUser").apply()
                            needLoginBox()
                        } else {
                            packageErrorBox(bundle, e)
                        }
                    },
                )
            }
        }
    }

    /**
     * v0.1 GEF: python the entry HTML into a locked-down WebView. The page has
     * no direct network access — every Star call goes through [ErpBridge].
     */
    private fun showHtmlPackage(pkg: HtmlGef, note: String?) {
        titleView.text = pkg.name
        contentHost.removeViews(1, contentHost.childCount - 1)
        if (note != null) {
            contentHost.addView(TextView(this).apply {
                text = note
                textSize = 13f
                setTextColor(Color.parseColor("#B3261E"))
                setPadding(dp(16), dp(8), dp(16), 0)
            })
        }
        val web = WebView(this)
        web.addJavascriptInterface(ErpBridge(this, web), "Erp")
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.setAllowFileAccessFromFileURLs(false)
        web.settings.setAllowUniversalAccessFromFileURLs(false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val scheme = request.url?.scheme
                if (scheme == "http" || scheme == "https" || scheme == "ws" || scheme == "wss") {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
        }
        contentHost.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        web.loadUrl("file://" + pkg.dir.absolutePath + "/" + pkg.entry)
    }

    private fun fetchPageData(bundle: Gef.Bundle): Map<String, Any?> {
        val page = runCatching { Json.parse(bundle.ui) as? Map<*, *> }.getOrNull() ?: return emptyMap()
        val lists = page["children"] as? List<*> ?: return emptyMap()
        val data = LinkedHashMap<String, Any?>()
        for (child in lists) {
            val node = child as? Map<*, *> ?: continue
            if (node["type"] != "list") continue
            val repeat = node["repeat"] as? String ?: continue
            if (repeat.isBlank() || data.containsKey(repeat)) continue
            val parsed = urlAction(node["action"] as? String)
            if (parsed?.first != "GET") continue
            val body = StarClient.get(baseUrl, parsed.second, authToken)
            val root = Json.parse(body) as? Map<*, *>
            data[repeat] = root?.get(repeat)
        }
        return data
    }

    private fun urlAction(action: String?): Pair<String, String>? {
        if (action == null || !action.startsWith("url:")) return null
        val rest = action.removePrefix("url:").trim()
        val sp = rest.indexOf(' ')
        if (sp <= 0) return null
        return rest.substring(0, sp).trim().uppercase() to rest.substring(sp + 1).trim()
    }

    private fun renderPackage(bundle: Gef.Bundle, data: Map<String, Any?>, note: String?) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        if (note != null) {
            col.addView(TextView(this).apply {
                text = note
                textSize = 13f
                setTextColor(Color.parseColor("#B3261E"))
                setPadding(0, 0, 0, dp(8))
            })
        }
        val renderer = GefRenderer(
            this, bundle, data,
            onRefresh = { showPackage(NativeGef(bundle)) },
            onPost = { path -> postPackage(bundle, path) },
        )
        renderer.build(col)
        contentHost.addView(ScrollView(this).apply { addView(col) },
            LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun postPackage(bundle: Gef.Bundle, path: String) {
        contentHost.removeViews(1, contentHost.childCount - 1)
        contentHost.addView(message("提交中…"))
        executor.execute {
            val result = runCatching { StarClient.post(baseUrl, path, "{}", authToken) }
            runOnUiThread {
                val nav = current
                if (nav !is Nav.Package || nav.pkg.id != bundle.id) return@runOnUiThread
                if (result.isFailure && result.exceptionOrNull() is ApiException &&
                    (result.exceptionOrNull() as ApiException).code == 401
                ) {
                    authToken = null
                    authUser = null
                    prefs().edit().remove("authToken").remove("authUser").apply()
                    needLoginBox()
                    return@runOnUiThread
                }
                showPackage(NativeGef(bundle), result.fold(
                    onSuccess = { null },
                    onFailure = { "操作失败：${it.message ?: "未知错误"}" },
                ))
            }
        }
    }

    private fun packageErrorBox(bundle: Gef.Bundle, e: Throwable) {
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
            setOnClickListener { showPackage(NativeGef(bundle)) }
        })
        contentHost.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun needLoginBox() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }
        box.addView(TextView(this).apply {
            text = "该功能需要登录后使用"
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        box.addView(Button(this).apply {
            text = "去设置登录"
            setOnClickListener { select(Nav.Settings) }
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- star setup

    private fun showSetup() {
        contentHost.removeViews(1, contentHost.childCount - 1)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(16))
        }
        col.addView(TextView(this).apply {
            text = "首次使用，请先连接到恒星"
            textSize = 17f
            setTypeface(Typeface.DEFAULT_BOLD)
        })
        col.addView(TextView(this).apply {
            text = "输入恒星 HTTP 地址（如 http://192.168.1.10:8080）。多个地址可在 设置 → 恒星 中管理。"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, dp(16))
        })
        val input = EditText(this).apply {
            hint = "http://192.168.1.10:8080"
            setSingleLine(true)
        }
        col.addView(input)
        col.addView(Button(this).apply {
            text = "连接"
            setOnClickListener {
                val url = input.text.toString()
                if (url.isBlank()) {
                    input.error = "请输入恒星地址"
                    return@setOnClickListener
                }
                addUrl(url)
                show(current)
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentHost.addView(col, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun loadUrls(): MutableList<String> {
        val text = getSharedPreferences("sat", Context.MODE_PRIVATE).getString("baseUrlList", "")
        return text.orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    }

    private fun saveUrls() {
        getSharedPreferences("sat", Context.MODE_PRIVATE).edit()
            .putString("baseUrlList", savedUrls.joinToString("\n"))
            .putString("baseUrl", baseUrl)
            .apply()
    }

    private fun addUrl(url: String) {
        val u = url.trim().trimEnd('/')
        if (u.isEmpty()) return
        if (!savedUrls.contains(u)) savedUrls.add(u)
        baseUrl = u
        saveUrls()
        showSettings()
    }

    private fun removeUrl() {
        if (savedUrls.isEmpty()) return
        savedUrls.remove(baseUrl)
        if (savedUrls.isEmpty()) {
            baseUrl = StarClient.DEFAULT_BASE
            savedUrls.add(baseUrl)
        } else {
            baseUrl = savedUrls.first()
        }
        saveUrls()
        showSettings()
    }

    private fun selectUrl(url: String) {
        if (url == baseUrl) return
        if (!savedUrls.contains(url)) return
        baseUrl = url
        saveUrls()
        showSettings()
    }

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        titleView.text = "设置"
        val prefs = getSharedPreferences("sat", Context.MODE_PRIVATE)
        selectedChannel = Updater.Channel.from(prefs.getString("channel", null) ?: "CANARY")
        selectedSource = Updater.sourceFrom(prefs.getString("source", null) ?: "github")
        foundRelease = null

        contentHost.removeViews(1, contentHost.childCount - 1)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        buildAccountSection(col)

        col.addView(section("恒星 · 连接"))

        connectionLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(4))
        }
        col.addView(connectionLabel)

        col.addView(Button(this).apply {
            text = "重新探测连接"
            setOnClickListener { bootConnection() }
        })

        col.addView(TextView(this).apply {
            text = "自动按 恒星直连 → 全部行星 顺序探测最短延迟可达的入口，在设置里可手动维护恒星地址。"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(4))
        })

        val urlSpinner = spinner(
            labels = savedUrls,
            selected = savedUrls.indexOfFirst { it == baseUrl }.coerceAtLeast(0),
            onSelect = { index -> selectUrl(savedUrls[index]) },
        )
        col.addView(settingRow("选择恒星", urlSpinner))

        val newUrl = EditText(this).apply {
            hint = "http://192.168.1.10:8080"
            setSingleLine(true)
        }
        val addBtn = Button(this).apply {
            text = "添加"
            setOnClickListener { addUrl(newUrl.text.toString()) }
        }
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(newUrl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(addBtn)
        }
        col.addView(addRow)
        col.addView(Button(this).apply {
            text = "删除当前恒星"
            setOnClickListener { removeUrl() }
        })

        buildRepoSection(col)

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

    // ---------------------------------------------------------------- software repo

    private fun buildRepoSection(col: LinearLayout) {
        col.addView(section("软件仓库"))
        val installed = packageOrder.apply(store.list())
        col.addView(TextView(this).apply {
            text = "已安装的 GEF 功能包：${installed.size}"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(4))
        })
        if (installed.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "还没有安装任何功能包"
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        for ((index, pkg) in installed.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val icon = pkg.iconBitmap
            row.addView(ImageView(this).apply {
                if (icon != null) setImageBitmap(icon)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = pkg.name
                    textSize = 15f
                })
                addView(TextView(this@MainActivity).apply {
                    text = pkg.id + (pkg.version?.let { "  v$it" } ?: "")
                    textSize = 12f
                    setTextColor(Color.GRAY)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
            row.addView(moveButton("上移", index, -1, index > 0))
            row.addView(moveButton("下移", index, +1, index < installed.size - 1))
            row.addView(Button(this).apply {
                text = "卸载"
                setOnClickListener { confirmUninstall(pkg) }
            })
            col.addView(row)
        }
        col.addView(Button(this).apply {
            text = "安装功能包…"
            setOnClickListener { pickGef() }
        })
        col.addView(Button(this).apply {
            text = "从发布同步功能包…"
            setOnClickListener { syncGefFromRelease() }
        })
        repoSyncStatus?.let { status ->
            col.addView(TextView(this).apply {
                text = status
                textSize = 13f
                setTextColor(if (status.startsWith("同步失败")) Color.parseColor("#E53935") else Color.parseColor("#4CAF50"))
                setPadding(0, dp(6), 0, dp(4))
            })
        }
        repoFlash?.let { flash ->
            col.addView(TextView(this).apply {
                text = flash
                textSize = 13f
                setTextColor(Color.parseColor("#4CAF50"))
                setPadding(0, dp(4), 0, 0)
            })
            repoFlash = null
        }
    }

    private fun moveButton(label: String, index: Int, delta: Int, enabled: Boolean): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isEnabled = enabled
            setOnClickListener {
                packageOrder.move(store.list(), index, delta)
                repoFlash = "已调整功能包顺序"
                showSettings()
            }
        }

    private fun pickGef() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, pickGefRequest) }.onFailure {
            repoFlash = "无法打开文件选择器：${it.message ?: "未知错误"}"
            showSettings()
        }
    }

    private fun syncGefFromRelease() {
        val channel = selectedChannel
        val source = selectedSource
        repoSyncStatus = "正在连接 GitHub 获取发布信息…"
        showSettings()
        executor.execute {
            val result = runCatching {
                RepoSync.sync(store, cacheDir, channel, source) { line ->
                    runOnUiThread {
                        repoSyncStatus = line
                        showSettings()
                    }
                }
            }
            runOnUiThread {
                repoSyncStatus = result.fold(
                    onSuccess = { o ->
                        buildString {
                            append("同步完成。")
                            if (o.installed) append("更新 ${o.applied.size} 个：${o.applied.joinToString("、")}")
                            if (o.skipped.isNotEmpty()) append("\n已是最新 ${o.skipped.size} 个：${o.skipped.joinToString("、")}")
                            if (o.failed) append("\n失败 ${o.errors.size} 个：${o.errors.joinToString("；")}")
                        }
                    },
                    onFailure = { "同步失败：${it.message ?: "未知错误"}" },
                )
                showSettings()
            }
        }
    }

    private fun confirmUninstall(pkg: GefPackage) {
        AlertDialog.Builder(this)
            .setTitle("卸载功能包")
            .setMessage("确定卸载「${pkg.name}」（${pkg.id}）？")
            .setPositiveButton("卸载") { _, _ ->
                store.uninstall(pkg.id)
                repoFlash = "已卸载「${pkg.name}」"
                showSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @Deprecated("Deprecated in Java", ReplaceWith("registerForActivityResult"))
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickGefRequest || resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        executor.execute {
            val read = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取所选文件")
            }
            runOnUiThread {
                val bytes = read.getOrNull()
                repoFlash = if (bytes == null) {
                    "读取文件失败：${read.exceptionOrNull()?.message ?: "未知错误"}"
                } else {
                    val id = runCatching { store.install(bytes) }
                    if (id.isSuccess) "已安装：${id.getOrThrow()}" else "安装失败：${id.exceptionOrNull()?.message ?: "未知错误"}"
                }
                showSettings()
            }
        }
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
        if (Build.VERSION.SDK_INT >= 29) {
            openInstallerViaDownloads(apk)
        } else {
            sessionInstall(apk)
        }
    }

    @SuppressLint("NewApi")
    private fun openInstallerViaDownloads(apk: File) {
        val resolver = contentResolver
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "update"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "new-home-$version.apk")
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val dst = resolver.insert(collection, values)
        if (dst == null) {
            sessionInstall(apk)
            return
        }
        val copied = runCatching {
            resolver.openOutputStream(dst)!!.use { out -> apk.inputStream().use { it.copyTo(out, 64 * 1024) } }
        }
        if (copied.isFailure) {
            resolver.delete(dst, null, null)
            statusView.text = "写入下载目录失败：${copied.exceptionOrNull()?.message ?: "未知错误"}"
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dst, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            resolver.delete(dst, null, null)
            statusView.text = "无法打开系统安装器：${it.message ?: "未知错误"}"
        }.onSuccess {
            statusView.text = "已交给系统安装器，请在安装窗口中确认。"
        }
    }

    private fun sessionInstall(apk: File) {
        runCatching {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = packageManager.packageInstaller.createSession(params)
            packageManager.packageInstaller.openSession(id).use { session ->
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

    // ---------------------------------------------------------------- account

    private fun buildAccountSection(col: LinearLayout) {
        col.addView(section("账号"))
        if (authToken == null) {
            val name = EditText(this).apply {
                hint = "用户名"
                setSingleLine(true)
                setInputType(android.text.InputType.TYPE_CLASS_TEXT)
            }
            val pass = EditText(this).apply {
                hint = "密码"
                setSingleLine(true)
                setInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            }
            val status = TextView(this).apply {
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, dp(4), 0, dp(4))
            }
            col.addView(name)
            col.addView(pass)
            col.addView(status)
            col.addView(Button(this).apply {
                text = "登录"
                setOnClickListener {
                    val user = name.text.toString().trim()
                    val password = pass.text.toString()
                    if (user.isEmpty() || password.isEmpty()) {
                        status.text = "请输入用户名和密码"
                        return@setOnClickListener
                    }
                    status.text = "登录中…"
                    executor.execute {
                        val result = runCatching {
                            val body = "{\"method\":\"password\",\"username\":\"" + Json.escape(user) +
                                "\",\"password\":\"" + Json.escape(password) + "\"}"
                            StarClient.post(baseUrl, "/api/auth/login", body)
                        }
                        runOnUiThread {
                            result.fold(
                                onSuccess = { resp ->
                                    val root = Json.parse(resp) as? Map<*, *>
                                    val token = root?.get("token") as? String
                                    val display = (root?.get("user") as? Map<*, *>)?.get("displayName") as? String ?: user
                                    if (token == null) {
                                        status.text = "登录响应异常，请重试"
                                        return@fold
                                    }
                                    authToken = token
                                    authUser = display
                                    prefs().edit().putString("authToken", token).putString("authUser", display).apply()
                                    showSettings()
                                },
                                onFailure = { e ->
                                    status.text = if (e is ApiException) "登录失败：${e.message}" else "无法连接恒星：${e.message ?: "未知错误"}"
                                },
                            )
                        }
                    }
                }
            })
        } else {
            col.addView(TextView(this).apply {
                text = "已登录：${authUser.orEmpty()}"
                textSize = 14f
                setPadding(0, 0, 0, dp(4))
            })
            col.addView(Button(this).apply {
                text = "登出"
                setOnClickListener {
                    val token = authToken
                    executor.execute {
                        runCatching { StarClient.post(baseUrl, "/api/auth/logout", "{}", token ?: "") }
                        runOnUiThread {
                            authToken = null
                            authUser = null
                            prefs().edit().remove("authToken").remove("authUser").apply()
                            showSettings()
                        }
                    }
                }
            })
        }
        col.addView(View(this).apply {
            setBackgroundColor(Color.rgb(230, 232, 236))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
        })
    }

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