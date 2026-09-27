package g.sw.relay

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * In-process Planet↔Star relay round trip over loopback sockets: a stub Star
 * HTTP server behind a RelayClient tunnels requests from a RelayPool back and
 * forth; checks GET, POST with body/headers and unknown-star failure.
 */
object RelaySmoke {

    @JvmStatic
    fun main(args: Array<String>) {
        val stub = HttpServer.create(InetSocketAddress(0), 0)
        stub.createContext("/") { exchange ->
            val body = String(exchange.requestBody.run { readBytes() }, Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("""{"ok":true,"echo":"$body"}""".toByteArray(Charsets.UTF_8))
            exchange.close()
        }
        stub.start()

        val serverSocket = ServerSocket(0)
        val pool = RelayPool(serverSocket)
        pool.start()
        val executor = Executors.newSingleThreadExecutor()
        val client = RelayClient("127.0.0.1", serverSocket.localPort, "home", stub.address.port, executor, retryMillis = 100)
        client.start()
        try {
            waitForStar(pool, "home")

            val response = pool.forward("home", "GET", "/api/x", mapOf("X-Test" to "1"), ByteArray(0))
            check(response.status == 200) { "status=${response.status}" }
            check(response.body.decodeToString().contains("\"ok\":true")) { "body=${response.body.decodeToString()}" }

            val post = pool.forward(
                "home", "POST", "/api/x",
                mapOf("Content-Type" to "application/json"),
                """{"n":1}""".toByteArray(),
            )
            check(post.status == 200) { "post status=${post.status}" }
            check(post.body.decodeToString().contains("""{"n":1}""")) { "post echo=${post.body.decodeToString()}" }

            runCatching { pool.forward("nosuch", "GET", "/api/x", emptyMap(), ByteArray(0)) }
                .onSuccess { error("expected UnknownStarException for 'nosuch'") }
                .onFailure { check(it is UnknownStarException) { "wrong exception: $it" } }

            println("[relay] smoke ok")
        } finally {
            client.stop()
            pool.stop()
            executor.shutdownNow()
            stub.stop(0)
        }
    }

    private fun waitForStar(pool: RelayPool, alias: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (pool.stars().any { it.alias == alias }) return
            Thread.sleep(20)
        }
        error("star '$alias' never connected")
    }
}