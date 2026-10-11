package g.sw.erp.chores

import com.sun.net.httpserver.HttpExchange
import g.sw.erp.auth.AuthModule
import g.sw.spi.ErpModule
import g.sw.spi.MountContext
import g.sw.spi.respond
import g.sw.spi.requireAuth

/** 家务/日程. Read-only canned data for now; every call needs a signed-in user. */
class ChoresModule(private val auth: AuthModule) : ErpModule {
    override val name: String = "chores"
    override val requires: List<String> = listOf("auth", "members")

    override fun mount(context: MountContext) {
        context.handle("GET", "/tasks", requireAuth(auth) { exchange, _ -> listTasks(exchange) })
    }

    private fun listTasks(exchange: HttpExchange) {
        exchange.respond(
            200,
            """{"tasks":[{"id":1,"title":"洗碗","assignee":"Alice","done":false}]}""",
        )
    }
}
