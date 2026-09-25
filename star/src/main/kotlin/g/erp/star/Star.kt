package g.erp.star

import com.sun.net.httpserver.HttpServer
import g.sw.db.Db
import g.sw.erp.auth.AuthModule
import g.sw.erp.chores.ChoresModule
import g.sw.erp.finances.FinancesModule
import g.sw.erp.inventory.InventoryModule
import g.sw.erp.members.MembersModule
import g.sw.spi.ErpModule
import g.sw.spi.Handler
import g.sw.spi.MountContext
import java.net.InetSocketAddress
import java.nio.file.Path

object Star {
    private const val DEFAULT_PORT = 8080

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
        val server = HttpServer.create(InetSocketAddress(port), 0)
        val router = Router()
        val db = Db.open(Path.of("data"))

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
            while (true) Thread.sleep(3600_000L)
        } finally {
            server.stop(0)
            db.close()
        }
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