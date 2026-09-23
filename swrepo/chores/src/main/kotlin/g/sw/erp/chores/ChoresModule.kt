package g.sw.erp.chores

import com.sun.net.httpserver.HttpExchange
import g.sw.spi.ErpModule
import g.sw.spi.MountContext
import g.sw.spi.respond

class ChoresModule : ErpModule {
    override val name: String = "chores"
    override val requires: List<String> = listOf("members")

    override fun mount(context: MountContext) {
        context.handle("GET", "/tasks", ::listTasks)
    }

    private fun listTasks(exchange: HttpExchange) {
        exchange.respond(
            200,
            """{"tasks":[{"id":1,"title":"洗碗","assignee":"Alice","done":false}]}""",
        )
    }
}