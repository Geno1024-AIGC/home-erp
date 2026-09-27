package g.sw.relay

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

class UnknownStarException(val alias: String) :
    IOException("star '$alias' is not connected to this relay")

class RelayTimeoutException(val alias: String, timeoutMs: Long) :
    IOException("star '$alias' did not answer within ${timeoutMs}ms")

/**
 * Planet side of the relay tunnel: accepts Star connections, registers their
 * `alias`, and multiplexes [forward] requests onto the matching connection.
 * Each connection runs one read loop that completes pending request futures
 * from RESPONSE frames and answers PING.
 */
class RelayPool(private val server: ServerSocket) {

    data class StarInfo(val alias: String, val remote: String)

    @Volatile private var alive = true
    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<RelayResponse>>()
    private val nextId = AtomicLong(1)
    private val acceptThread = Thread(::acceptLoop, "relay-accept").apply { isDaemon = true }

    fun start() {
        acceptThread.start()
    }

    fun stop() {
        alive = false
        runCatching { server.close() }
    }

    fun port(): Int = server.localPort

    fun stars(): List<StarInfo> =
        connections.entries.mapNotNull { (alias, conn) ->
            StarInfo(alias, conn.remoteAddress())
        }.sortedBy { it.alias }

    fun forward(
        alias: String,
        method: String,
        target: String,
        headers: Map<String, String>,
        body: ByteArray,
        timeoutMs: Long = 30_000L,
    ): RelayResponse {
        val conn = connections[alias] ?: throw UnknownStarException(alias)
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<RelayResponse>()
        pending[id] = future
        try {
            conn.sendRequest(RelayRequest(id, method, target, headers, body))
        } catch (e: IOException) {
            pending.remove(id)
            throw IOException("tunnel to star '$alias' failed: ${e.message}", e)
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw RelayTimeoutException(alias, timeoutMs)
        } finally {
            pending.remove(id)
        }
    }

    private fun acceptLoop() {
        while (alive) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (alive) println("[relay] accept failed: ${e.message}")
                continue
            }
            Thread({ serve(socket) }, "relay-star").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        val conn = RelayConnection(socket)
        var alias = "?"
        try {
            val hello = conn.read() ?: return
            if (hello.type != Protocol.HELLO) throw IOException("expected HELLO first, got type ${hello.type}")
            alias = (RelayConnection.payloadMap(hello)["alias"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw IOException("HELLO is missing a non-blank 'alias'")
            connections[alias] = conn
            println("[relay] star connected: $alias from ${conn.remoteAddress()}")
            try {
                while (alive) {
                    val frame = conn.read() ?: break
                    when (frame.type) {
                        Protocol.RESPONSE -> {
                            val resp = parseResponse(frame)
                            pending.remove(resp.id)?.complete(resp)
                        }
                        Protocol.PING -> conn.sendPong()
                    }
                }
            } finally {
                connections.remove(alias, conn)
                println("[relay] star disconnected: $alias")
            }
        } catch (e: IOException) {
            println("[relay] tunnel error from $alias: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseResponse(frame: Frame): RelayResponse {
        val map = RelayConnection.payloadMap(frame)
        val id = (map["id"] as? Number)?.toLong() ?: throw IOException("response without id")
        val status = (map["status"] as? Number)?.toInt() ?: throw IOException("response without status: $id")
        return RelayResponse(id, status, headersOf(map["headers"]), RelayConnection.b64(map["body"]))
    }

    private fun headersOf(value: Any?): Map<String, String> = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v.toString() }
        else -> emptyMap()
    }
}