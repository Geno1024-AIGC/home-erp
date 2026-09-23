package g.sw.spi

import com.sun.net.httpserver.HttpExchange

typealias Handler = (HttpExchange) -> Unit

interface MountContext {
    val basePath: String
    fun handle(method: String, path: String, handler: Handler)
}

interface ErpModule {
    val name: String
    val requires: List<String> get() = emptyList()
    fun mount(context: MountContext) {}
}