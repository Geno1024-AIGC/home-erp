package g.sw.erp.inventory

import com.sun.net.httpserver.HttpExchange
import g.sw.erp.auth.AuthModule
import g.sw.spi.ErpModule
import g.sw.spi.MountContext
import g.sw.spi.respond
import g.sw.spi.requireAuth

/** 家居物品/库存. Read-only canned data for now; every call needs a signed-in user. */
class InventoryModule(private val auth: AuthModule) : ErpModule {
    override val name: String = "inventory"
    override val requires: List<String> = listOf("auth", "members")

    override fun mount(context: MountContext) {
        context.handle("GET", "/items", requireAuth(auth) { exchange, _ -> listItems(exchange) })
    }

    private fun listItems(exchange: HttpExchange) {
        exchange.respond(
            200,
            """{"items":[{"id":1,"name":"大米","qty":2,"location":"厨房"},{"id":2,"name":"牛奶","qty":6,"location":"冰箱"}]}""",
        )
    }
}
