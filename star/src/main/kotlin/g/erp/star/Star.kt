package g.erp.star

import com.sun.net.httpserver.HttpServer
import g.sw.db.Db
import g.sw.erp.auth.AuthModule
import g.sw.erp.chores.ChoresModule
import g.sw.erp.finances.FinancesModule
import g.sw.erp.inventory.InventoryModule
import g.sw.erp.members.MembersModule
import g.sw.relay.RelayClient
import g.sw.spi.ErpModule
import g.sw.spi.Handler
import g.sw.spi.MountContext
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors

object Star {
    private const val DEFAULT_PORT = 8080
    private const val DEFAULT_ALIAS = "home"

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
        val planet = planetTarget(args)
        val alias = flagValue(args, "alias") ?: DEFAULT_ALIAS
        val server = HttpServer.create(InetSocketAddress(port), 0)
        val router = Router()
        val db = Db.open(Path.of("data"))

        val relayClient = if (planet != null) {
            RelayClient(
                planetHost = planet.first,
                planetPort = planet.second,
                alias = alias,
                starPort = port,
                executor = Executors.newCachedThreadPool(),
            ).also {
                it.start()
                println("[star] relay to planet ${planet.first}:${planet.second} as '$alias'")
            }
        } else {
            null
        }

        try {
            assemble(
                router,
                listOf(
                    AuthModule(db),
                    MembersModule(),
                    InventoryModule(),
                    FinancesModule(),
                    ChoresModule(),
                ),
            )

            server.createContext("/", router::handle)
            server.start()
            println("Star (恒星) listening on http://localhost:$port")
            if (relayClient == null) println("[star] no --planet given; reachable only on the local network")
            while (true) Thread.sleep(3600_000L)
        } finally {
            relayClient?.stop()
            server.stop(0)
            db.close()
        }
    }

    private fun planetTarget(args: Array<String>): Pair<String, Int>? =
        args.firstNotNullOfOrNull { a ->
            if (!a.startsWith("--planet=")) return@firstNotNullOfOrNull null
            val target = a.substringAfter("=")
            val colon = target.lastIndexOf(':')
            if (colon <= 0 || colon == target.lastIndex) error("--planet expects host:port")
            val host = target.substring(0, colon)
            val p = target.substring(colon + 1).toIntOrNull() ?: error("--planet expects host:port")
            host to p
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