package g.erp.satellite

import java.net.HttpURLConnection
import java.net.URL

internal class ApiException(val code: Int, message: String) : Exception(message)

internal object StarClient {

    const val DEFAULT_BASE = "http://10.0.2.2:8080"
    const val ADMIN_TIMEOUT_MS = 5000

    fun get(baseUrl: String, path: String, token: String? = null, timeoutMs: Int = ADMIN_TIMEOUT_MS): String =
        request("GET", baseUrl, path, token, null, timeoutMs)

    fun post(baseUrl: String, path: String, body: String, token: String? = null, timeoutMs: Int = ADMIN_TIMEOUT_MS): String =
        request("POST", baseUrl, path, token, body, timeoutMs)

    private fun request(method: String, baseUrl: String, path: String, token: String?, body: String?, timeoutMs: Int): String {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs * 2
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                throw ApiException(code, errorMessage(code, detail))
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int, detail: String): String {
        val error = runCatching {
            (g.erp.satellite.json.Json.parse(detail) as? Map<*, *>)?.get("error")?.toString()
        }.getOrNull()
        return error ?: "HTTP $code"
    }
}