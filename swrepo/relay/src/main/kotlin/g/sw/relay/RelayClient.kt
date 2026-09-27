package g.sw.relay

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executor

/**
 * Star side of the relay tunnel: repeatedly dials the Planet until successful,
 * HELLOs with `alias`, then answers each REQUEST by calling the Star's own
 * local HTTP server (`http://127.0.0.1:<starPort><target>`) and relays the
 * response back. Re-dials with [retryMillis] pause after any disconnect.
 */
class RelayClient(
    private val planetHost: String,
    private val planetPort: Int,
    private val alias: String,
    private val starPort: Int,
    private val executor: Executor,
    private val retryMillis: Long = 5_000L,
) {

    @Volatile private var running = true
    private val thread = Thread(::dialLoop, "relay-client").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    fun stop() {
        running = false
    }

    private fun dialLoop() {
        while (running) {
            try {
                runSession()
            } catch (e: IOException) {
                if (running) println("[relay] planet connection lost: ${e.message}")
            }
            if (!running) break
            try {
                Thread.sleep(retryMillis)
            } catch (e: InterruptedException) {
                running = false
            }
        }
    }

    private fun runSession() {
        Socket(planetHost, planetPort).use { socket ->
            if (!running) return
            val conn = RelayConnection(socket)
            conn.sendHello(alias)
            while (running) {
                val frame = conn.read() ?: break
                when (frame.type) {
                    Protocol.REQUEST -> {
                        val request = parseRequest(frame)
                        executor.execute { answer(request, conn) }
                    }
                    Protocol.PING -> conn.sendPong()
                }
            }
        }
    }

    private fun parseRequest(frame: Frame): RelayRequest {
        val map = RelayConnection.payloadMap(frame)
        val id = (map["id"] as? Number)?.toLong() ?: throw IOException("request without id")
        val method = (map["method"] as? String)?.uppercase() ?: throw IOException("request without method")
        val target = (map["target"] as? String) ?: throw IOException("request without target")
        return RelayRequest(id, method, target, headersOf(map["headers"]), RelayConnection.b64(map["body"]))
    }

    private fun answer(request: RelayRequest, conn: RelayConnection) {
        val response = try {
            localCall(request)
        } catch (e: IOException) {
            RelayResponse(
                id = request.id,
                status = 502,
                headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
                body = """{"error":"relay: ${e.message?.replace("\"", "\\\"") ?: "bad response"}"}""".toByteArray(Charsets.UTF_8),
            )
        }
        try {
            conn.sendResponse(response)
        } catch (e: IOException) {
            // tunnel is gone; the read loop will notice on the next frame
        }
    }

    private fun localCall(request: RelayRequest): RelayResponse {
        val conn = URL("http://127.0.0.1:$starPort${request.target}").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            request.headers.filter { (k, _) ->
                k.lowercase() !in setOf("host", "content-length", "connection", "accept-encoding")
            }.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val shouldSendBody = request.body.isNotEmpty() ||
                request.method in setOf("POST", "PUT", "PATCH", "DELETE")
            if (shouldSendBody) {
                conn.doOutput = true
                if (request.body.isNotEmpty()) conn.outputStream.use { it.write(request.body) }
            }
            val status = conn.responseCode
            val body = if (status in 200..299) {
                conn.inputStream?.use { it.readBytes() } ?: ByteArray(0)
            } else {
                conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }
            val headers = conn.headerFields.entries
                .filter { it.key != null && it.key.lowercase() != "content-length" }
                .associate { it.key to (it.value.firstOrNull() ?: "") }
            return RelayResponse(request.id, status, headers, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun headersOf(value: Any?): Map<String, String> = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v.toString() }
        else -> emptyMap()
    }
}