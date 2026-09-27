package g.erp.star

import com.sun.net.httpserver.HttpServer
import g.sw.db.Db
import g.sw.erp.auth.AuthModule
import g.sw.erp.chores.ChoresModule
import g.sw.erp.finances.FinancesModule
import g.sw.erp.inventory.InventoryModule
import g.sw.erp.members.MembersModule
import g.sw.erp.topology.TopologyModule
import g.sw.relay.RelayClient
import g.sw.spi.ErpModule
import g.sw.spi.Handler
import g.sw.spi.MountContext
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object Star {
    private const val DEFAULT_PORT = 8080
    private const val DEFAULT_ALIAS = "home"

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
        val alias = flagValue(args, "alias") ?: DEFAULT_ALIAS
        val httpHost = flagValue(args, "httpHost") ?: "localhost"
        val server = HttpServer.create(InetSocketAddress(port), 0)
        val router = Router()
        val db = Db.open(Path.of("data"))
        val topology = TopologyModule(db, alias, httpHost, port)
        planetFlags(args).forEach { (host, httpPort, tunnelPort) ->
            topology.registerPlanet(host, httpPort, host, tunnelPort)
        }
        val connector = PlanetConnector(topology, alias, port).apply { start() }

        try {
            assemble(
                router,
                listOf(
                    AuthModule(db),
                    topology,
                    MembersModule(),
                    InventoryModule(),
                    FinancesModule(),
                    ChoresModule(),
                ),
            )

            server.createContext("/", router::handle)
            server.start()
            println("Star (恒星) listening on http://localhost:$port")
            println("[star] topology: star '$alias' at $httpHost:$port, ${topology.listPlanets().size} planet(s) configured")
            if (topology.listPlanets().isEmpty()) println("[star] no --planet given; reachable only on the local network")
            while (true) Thread.sleep(3600_000L)
        } finally {
            connector.stop()
            server.stop(0)
            db.close()
        }
    }

    /** Keeps a relay tunnel alive to every registered planet; marks reachable on connect/disconnect. */
    private class PlanetConnector(
        private val topology: TopologyModule,
        private val starAlias: String,
        private val starPort: Int,
    ) {
        private val executor = Executors.newCachedThreadPool()
        private val clients = ConcurrentHashMap<String, RelayClient>()
        @Volatile private var alive = true
        private val thread = Thread(::run, "star-planets").apply { isDaemon = true }

        fun start() {
            thread.start()
        }

        fun stop() {
            alive = false
            clients.values.forEach { it.stop() }
            executor.shutdownNow()
        }

        private fun run() {
            while (alive) {
                try {
                    tick()
                } catch (e: Exception) {
                    println("[star] planet supervisor error: ${e.message}")
                }
                try {
                    Thread.sleep(5_000L)
                } catch (e: InterruptedException) {
                    alive = false
                }
            }
        }

        private fun tick() {
            val planets = topology.listPlanets()
            val names = planets.map { it.name }.toSet()
            clients.keys.filter { it !in names }.forEach { name ->
                clients.remove(name)?.stop()
            }
            for (node in planets) {
                if (clients.containsKey(node.name)) continue
                val tunnelHost = node.tunnelHost ?: continue
                val client = RelayClient(
                    planetHost = tunnelHost,
                    planetPort = node.tunnelPort,
                    alias = starAlias,
                    starPort = starPort,
                    executor = executor,
                    onStatus = { ok -> topology.markReachable(node.name, ok) },
                )
                clients[node.name] = client
                client.start()
                println("[star] dialing planet ${node.name} at $tunnelHost:${node.tunnelPort}")
            }
        }
    }

    private fun planetFlags(args: Array<String>): List<Triple<String, Int, Int>> =
        args.filter { it.startsWith("--planet=") }.map { a ->
            val parts = a.substringAfter("=").split(":")
            val host = parts[0]
            val httpPort = parts.getOrNull(1)?.toIntOrNull() ?: error("--planet expects host:httpPort[:tunnelPort]")
            val tunnelPort = parts.getOrNull(2)?.toIntOrNull() ?: (httpPort + 1)
            Triple(host, httpPort, tunnelPort)
        }

    private fun flagValue(args: Array<String>, name: String): String? =
        args.firstNotNullOfOrNull { a ->
            if (a.startsWith("--$name=")) a.substringAfter("=") else null
        }

    private fun assemble(router: Router, modules: List<ErpModule>) {
        dependencyOrder(modules).forEach { module ->
            val context: MountContext = object : MountContext {
                override val basePath: String = "/api/" + module.name
                override fun handle(method: String, path: String, handler: Handler) {
                    router.register(method, basePath + path, handler)
                }
            }
            println("[star] mounting module: ${module.name}")
            module.mount(context)
        }
    }

    private fun dependencyOrder(modules: List<ErpModule>): List<ErpModule> {
        val byName = modules.associateBy { it.name }
        val ordered = mutableListOf<ErpModule>()
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()

        fun visit(module: ErpModule) {
            if (module.name in visited) return
            if (!inStack.add(module.name)) error("Circular module dependency involving '${module.name}'")
            module.requires.forEach { required ->
                val dependency = byName[required]
                    ?: error("Module '${module.name}' requires unknown module '$required'")
                visit(dependency)
            }
            inStack.remove(module.name)
            visited.add(module.name)
            ordered.add(module)
        }

        modules.forEach { visit(it) }
        return ordered
    }
}