package g.erp.star

import com.sun.net.httpserver.HttpExchange
import g.sw.spi.Handler
import g.sw.spi.respond

class Router {

    private data class Route(val method: String, val path: String, val handler: Handler)

    private val routes = mutableListOf<Route>()

    fun register(method: String, path: String, handler: Handler) {
        routes += Route(method, path, handler)
    }

    fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        val route = routes.firstOrNull { it.method == method && it.path == path }
        if (route == null) {
            exchange.respond(404, """{"error":"not found"}""")
        } else {
            route.handler(exchange)
        }
        exchange.close()
    }
}