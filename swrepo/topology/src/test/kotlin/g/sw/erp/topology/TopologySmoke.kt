package g.sw.erp.topology

import com.sun.net.httpserver.HttpServer
import g.sw.db.Db
import g.sw.spi.ErpModule
import g.sw.spi.Handler
import g.sw.spi.Json
import g.sw.spi.MountContext
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * Self-test: DB-backed address book on the Star side, the HTTP surface a
 * satellite (and a Planet cache puller) uses, and the Planet's in-memory cache.
 */
object TopologySmoke {

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Files.createTempDirectory("topo-smoke")
        val db = Db.open(dir)
        val module = TopologyModule(db, "home", "localhost", 8080)

        val server = HttpServer.create(InetSocketAddress(0), 0)
        mountInto(module, server)
        server.start()

        val base = "http://127.0.0.1:${server.address.port}/api/topology"

        val first = get(base)
        check(starName(first) == "home") { "star name = ${starName(first)}" }
        check((first["planets"] as List<*>).isEmpty()) { "expected no planets yet" }

        val added = module.registerPlanet("planet-a.example", 9090, "planet-a.example", 9091)
        module.markReachable(added.name, true)
        check(module.listPlanets().size == 1) { "expected one planet" }

        val after = get(base)
        val planets = after["planets"] as List<*>
        check(planets.size == 1) { "expected one planet in http view" }
        val planet = planets[0] as Map<*, *>
        check(planet["name"] == added.name) { "wrong planet name: ${planet["name"]}" }
        check(planet["reachable"] == true) { "reachable flag lost on read" }

        val posted = post("$base/planets", """{"httpHost":"planet-b.example","httpPort":9092,"tunnelHost":"planet-b.example","tunnelPort":9093}""")
        check(httpStatus(posted) == 201) { "register http status = ${httpStatus(posted)}" }
        check(module.listPlanets().size == 2) { "expected two planets after registration" }

        // Planet side cache: pull a topology through the same JSON the tunnel would
        // deliver, then serve it back.
        val cache = TopologyCache()
        check(cache.snapshot() == null) { "empty cache should serve null" }
        val pulled = get(base)
        cache.update("home", toMap(pulled))
        check(cache.size == 1)
        val served = cache.snapshot()!!
        check(starName(served) == "home")
        check((served["planets"] as List<*>).size == 2) { "cache should merge both planets" }

        server.stop(0)
        db.close()
        println("[topology] smoke ok")
    }

    private fun mountInto(module: ErpModule, server: HttpServer) {
        module.mount(object : MountContext {
            override val basePath: String = "/api/" + module.name
            override fun handle(method: String, path: String, handler: Handler) {
                server.createContext(basePath + path) { exchange -> handler(exchange) }
            }
        })
    }

    private fun get(path: String): Map<String, Any?> {
        val conn = java.net.URL(path).openConnection() as java.net.HttpURLConnection
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return toMap(Json.parse(body) as Map<*, *>)
    }

    private fun post(path: String, body: String): java.net.HttpURLConnection {
        val conn = java.net.URL(path).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn
    }

    private fun httpStatus(conn: java.net.HttpURLConnection): Int {
        val status = conn.responseCode
        conn.disconnect()
        return status
    }

    private fun starName(topology: Map<String, Any?>): String? =
        (topology["star"] as? Map<*, *>)?.get("name")?.toString()

    private fun toMap(map: Map<*, *>): Map<String, Any?> = map.entries.associate { (k, v) -> k.toString() to v }
}