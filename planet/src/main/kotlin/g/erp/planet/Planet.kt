package g.erp.planet

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import g.sw.erp.topology.TopologyCache
import g.sw.relay.RelayPool
import g.sw.relay.RelayTimeoutException
import g.sw.relay.UnknownStarException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

/**
 * 行星 (Planet) — cloud relay + discovery server. External clients (卫星) reach
 * the home 恒星 (Star) through this server via `http://<planet>:<httpPort>/<alias>/…`,
 * even when the Star sits behind a NAT: the Star dials out to the tunnel port,
 * the Planet multiplexes inbound HTTP requests onto that connection and relays
 * the replies back. No state is persisted — stars re-announce on every dial.
 *
 * Usage: `java -jar planet.jar [--http=<port>] [--tunnel=<port>]`
 * Defaults: HTTP 9090 (satellite traffic + discovery), tunnel 9091 (Star dials in).
 */
object Planet {

    private const val DEFAULT_HTTP_PORT = 9090
    private const val DEFAULT_TUNNEL_PORT = 9091
    private const val DEFAULT_REFRESH_MS = 30_000L
    private val ALIAS = Regex("[a-zA-Z0-9_-]{1,64}")

    @JvmStatic
    fun main(args: Array<String>) {
        val httpPort = portArg(args, "http") ?: DEFAULT_HTTP_PORT
        val tunnelPort = portArg(args, "tunnel") ?: DEFAULT_TUNNEL_PORT
        val refreshMs = (portArg(args, "refresh")?.toLong() ?: DEFAULT_REFRESH_MS).coerceAtLeast(5_000L)

        val pool = RelayPool(ServerSocket(tunnelPort))
        pool.start()
        val topologyCache = TopologyCache()
        startTopologyPuller(pool, topologyCache, refreshMs)
        println("[planet] tunnel listening on $tunnelPort")

        val server = HttpServer.create(InetSocketAddress(httpPort), 0)
        server.createContext("/") { exchange -> route(exchange, pool, topologyCache) }
        server.start()
        println("[planet] http listening on $httpPort")
        println("[planet] to serve a star: open its relay to this tunnel port, it appears under its alias")
    }

    private fun portArg(args: Array<String>, name: String): Int? =
        args.firstNotNullOfOrNull { a ->
            if (a.startsWith("--$name=")) a.substringAfter("=").toIntOrNull() else null
        }

    private fun route(exchange: HttpExchange, pool: RelayPool, topologyCache: TopologyCache) {
        val path = exchange.requestURI.path ?: "/"
        try {
            when {
                path == "/" || path == "/planet" -> serveDiscovery(exchange, pool)
                path == "/planet/stars" -> serveStars(exchange, pool)
                path == "/planet/topology" -> serveTopology(exchange, topologyCache)
                path.startsWith("/planet/") -> sendJson(exchange, 404, mapOf("error" to "no such endpoint '${path}'"))
                else -> relay(exchange, pool, path)
            }
        } catch (e: Exception) {
            sendJson(exchange, 500, mapOf("error" to "relay failure: ${e.message}"))
        } finally {
            exchange.close()
        }
    }

    /**
     * Planets persist nothing: this loop periodically pulls each connected
     * Star's topology over its relay tunnel and keeps only an in-memory cache.
     */
    private fun startTopologyPuller(pool: RelayPool, cache: TopologyCache, refreshMs: Long) {
        Thread({
            while (true) {
                for (star in pool.stars()) {
                    try {
                        val response = pool.forward(
                            star.alias, "GET", "/api/topology",
                            emptyMap(), ByteArray(0),
                            timeoutMs = minOf(refreshMs, 10_000L),
                        )
                        val json = runCatching {
                            g.sw.spi.Json.parse(response.body.decodeToString()) as? Map<*, *>
                        }.getOrNull()
                        if (json != null) cache.update(star.alias, json.entries.associate { (k, v) -> k.toString() to v })
                    } catch (_: Exception) {
                        // tunnel or star momentarily unavailable; retry next round
                    }
                }
                try {
                    Thread.sleep(refreshMs)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }, "planet-topology").apply { isDaemon = true }.start()
    }

    private fun serveTopology(exchange: HttpExchange, cache: TopologyCache) {
        val snapshot = cache.snapshot()
        if (snapshot == null) {
            sendJson(exchange, 503, mapOf("error" to "no star topology cached yet"))
            return
        }
        sendJson(exchange, 200, snapshot)
    }

    private fun serveDiscovery(exchange: HttpExchange, pool: RelayPool) {
        val stars = pool.stars()
        val bars = stars.joinToString("") { star -> "<li><code>${star.alias}</code> <span>(${star.remote})</span></li>" }
        val base = "http://<host>:${exchange.localAddress.port}"
        val html = """
            <!doctype html><html lang="zh"><head><meta charset="utf-8"><title>行星 Planet</title></head>
            <body>
              <h1>行星 Planet</h1>
              <p>服务发现与中继。已连接的恒星:</p>
              <ul>${if (bars.isEmpty()) "<li>（暂无）</li>" else bars}</ul>
              <p>卫星把服务地址设为 <code>$base/&lt;alias&gt;</code> 即可经此访问恒星。</p>
            </body></html>
        """.trimIndent()
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        respond(exchange, 200, html.toByteArray(StandardCharsets.UTF_8))
    }

    private fun serveStars(exchange: HttpExchange, pool: RelayPool) {
        val payload = mapOf("stars" to pool.stars().map { mapOf("alias" to it.alias, "remote" to it.remote) })
        sendJson(exchange, 200, payload)
    }

    private fun relay(exchange: HttpExchange, pool: RelayPool, path: String) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || !ALIAS.matches(segments[0])) {
            sendJson(exchange, 404, mapOf("error" to "unknown star alias in '$path'"))
            return
        }
        val alias = segments[0]
        val target = buildString {
            append('/')
            append(segments.drop(1).joinToString("/"))
            exchange.requestURI.rawQuery?.let { append('?').append(it) }
        }
        val headers = exchange.requestHeaders.entries
            .filter { (k, _) -> k.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding", "accept-encoding", "upgrade") }
            .associate { (k, v) -> k to v.firstOrNull().orEmpty() }
        val body = exchange.requestBody.run { readBytes() }

        val response = try {
            pool.forward(alias, exchange.requestMethod, target, headers, body)
        } catch (e: UnknownStarException) {
            sendJson(exchange, 502, mapOf("error" to "star '$alias' is offline or unknown"))
            return
        } catch (e: RelayTimeoutException) {
            sendJson(exchange, 504, mapOf("error" to "star '$alias' timed out"))
            return
        }

        response.headers.forEach { (k, v) ->
            if (k.lowercase() !in setOf("transfer-encoding", "content-length", "connection")) {
                exchange.responseHeaders.set(k, v)
            }
        }
        respond(exchange, response.status, response.body)
    }

    private fun sendJson(exchange: HttpExchange, status: Int, payload: Map<String, Any?>) {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        respond(exchange, status, g.sw.spi.Json.write(payload).toByteArray(StandardCharsets.UTF_8))
    }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
        if (body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }
}