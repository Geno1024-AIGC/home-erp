package g.sw.spi

import com.sun.net.httpserver.HttpExchange

fun HttpExchange.respond(code: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}