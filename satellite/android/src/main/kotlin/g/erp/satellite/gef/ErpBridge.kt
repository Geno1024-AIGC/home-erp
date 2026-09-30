package g.erp.satellite.gef

import android.webkit.JavascriptInterface
import android.webkit.WebView
import g.erp.satellite.MainActivity
import g.erp.satellite.StarClient
import g.erp.satellite.json.Json

/**
 * The only channel an HTML GEF has to the Star. Page JS calls
 * `window.Erp.apiGet('/api/…', function (err, data) { … })`; Kotlin runs the
 * request with the current baseUrl + authToken and Post-executed the callback
 * as `cb(<data>, null)` / `cb(null, "<error>")`. Callback identifiers are
 * validated so page content can never shape the executed JS.
 */
internal class ErpBridge(
    private val activity: MainActivity,
    private val web: WebView,
) {

    private val callback = Regex("[A-Za-z_][A-Za-z0-9_]*")

    @JavascriptInterface
    fun getToken(): String? = activity.currentToken()

    @JavascriptInterface
    fun apiGet(path: String, callback: String) = exec("GET", path, null, callback)

    @JavascriptInterface
    fun apiPost(path: String, body: String, callback: String) = exec("POST", path, body, callback)

    private fun exec(method: String, path: String, body: String?, callback: String) {
        if (!this.callback.matches(callback)) return
        val token = activity.currentToken()
        activity.worker.execute {
            val result = runCatching {
                if (method == "GET") {
                    StarClient.get(activity.currentBase, path, token)
                } else {
                    StarClient.post(activity.currentBase, path, body ?: "{}", token)
                }
            }
            val js = result.fold(
                onSuccess = { data -> "$callback($data, null)" },
                onFailure = { e -> "$callback(null, ${Json.write(e.message ?: "错误")})" },
            )
            activity.runOnUiThread { web.evaluateJavascript(js, null) }
        }
    }
}