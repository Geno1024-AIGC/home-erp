package g.sw.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Relay tunnel protocol v1: a single persistent TCP connection between a
 * Planet relay server and a Star relay client. Frames are length-prefixed
 * binary: `[4-byte BE length][1-byte type][payload]`, payload is a UTF-8 JSON
 * document. Request/response bodies travel as Base64 inside the JSON (see
 * [g.sw.spi.Json] which encodes [ByteArray] to Base64 on write).
 *
 * Frame types:
 *  - HELLO     `{"alias":"<star alias>"}` — first frame a Star sends
 *  - REQUEST   `{"id":N,"method","target","headers":{},"body":"<b64>"}` — Planet→Star
 *  - RESPONSE  `{"id":N,"status",S,"headers":{},"body":"<b64>"}` — Star→Planet
 *  - PING/PONG `{}` — liveness (replied in kind)
 */
internal object Protocol {
    const val HELLO = 1
    const val REQUEST = 2
    const val RESPONSE = 3
    const val PING = 4
    const val PONG = 5
}

const val MAX_FRAME_BYTES = 16 * 1024 * 1024

data class RelayRequest(
    val id: Long,
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
)

data class RelayResponse(
    val id: Long,
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
)

class Frame(val type: Int, val payload: String)

/**
 * A frame writer/reader owned by one side of a tunnel connection. Outbound
 * writes are serialized on an internal lock so several callers (e.g. concurrent
 * satellite forwards) may share one connection safely. Not thread-safe for
 * concurrent reads — each connection runs exactly one read loop.
 */
class RelayConnection(socket: Socket) {

    private val socket = socket
    private val input = DataInputStream(socket.getInputStream())
    private val outputLock = Any()
    private val output = DataOutputStream(socket.getOutputStream())

    fun remoteAddress(): String =
        "${socket.inetAddress?.hostAddress ?: "?"}:${socket.port}"

    fun send(type: Int, payload: String) {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        require(body.size + 1 <= MAX_FRAME_BYTES) { "frame too large: ${body.size + 1} bytes" }
        synchronized(outputLock) {
            output.writeInt(body.size + 1)
            output.writeByte(type)
            output.write(body)
            output.flush()
        }
    }

    fun sendJson(type: Int, payload: Any) = send(type, g.sw.spi.Json.write(payload))

    fun sendHello(alias: String) = sendJson(Protocol.HELLO, mapOf("alias" to alias))

    fun sendRequest(request: RelayRequest) = sendJson(Protocol.REQUEST, mapOf(
        "id" to request.id,
        "method" to request.method,
        "target" to request.target,
        "headers" to request.headers,
        "body" to request.body,
    ))

    fun sendResponse(response: RelayResponse) = sendJson(Protocol.RESPONSE, mapOf(
        "id" to response.id,
        "status" to response.status,
        "headers" to response.headers,
        "body" to response.body,
    ))

    fun sendPing() = sendJson(Protocol.PING, mapOf<String, String>())
    fun sendPong() = sendJson(Protocol.PONG, mapOf<String, String>())

    /**
     * Reads the next frame. Returns null when the peer closed the connection
     * cleanly (stream hit EOF between frames). Any mid-frame truncation or
     * malformed size is thrown as [IOException] / [IllegalArgumentException].
     */
    fun read(): Frame? {
        val length = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (length < 1 || length > MAX_FRAME_BYTES) throw IllegalArgumentException("bad frame length: $length")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val type = bytes[0].toInt() and 0xff
        val payload = String(bytes, 1, length - 1, StandardCharsets.UTF_8)
        return Frame(type, payload)
    }

    companion object {
        fun payloadMap(frame: Frame): Map<*, *> =
            g.sw.spi.Json.parse(frame.payload) as? Map<*, *>
                ?: throw IllegalArgumentException("expected JSON object payload, got: ${frame.payload.take(64)}")

        fun b64(body: Any?): ByteArray =
            java.util.Base64.getDecoder().decode((body as? String).orEmpty())
    }
}