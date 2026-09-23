package g.sw.erp.inventory

import com.sun.net.httpserver.HttpExchange
import g.sw.spi.ErpModule
import g.sw.spi.MountContext
import g.sw.spi.respond

class InventoryModule : ErpModule {
    override val name: String = "inventory"
    override val requires: List<String> = listOf("members")

    override fun mount(context: MountContext) {
        context.handle("GET", "/items", ::listItems)
    }

    private fun listItems(exchange: HttpExchange) {
        exchange.respond(
            200,
            """{"items":[{"id":1,"name":"大米","qty":2,"location":"厨房"},{"id":2,"name":"牛奶","qty":6,"location":"冰箱"}]}""",
        )
    }
}