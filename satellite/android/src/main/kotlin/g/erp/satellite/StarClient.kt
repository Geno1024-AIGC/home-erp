package g.erp.satellite

import java.net.HttpURLConnection
import java.net.URL

internal object StarClient {

    const val DEFAULT_BASE = "http://10.0.2.2:8080"

    fun get(baseUrl: String, path: String): String {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}