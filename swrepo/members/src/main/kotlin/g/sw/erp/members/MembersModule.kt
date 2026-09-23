package g.sw.erp.members

import com.sun.net.httpserver.HttpExchange
import g.sw.spi.ErpModule
import g.sw.spi.MountContext
import g.sw.spi.respond

class MembersModule : ErpModule {
    override val name: String = "members"
    override val requires: List<String> = emptyList()

    override fun mount(context: MountContext) {
        context.handle("GET", "/family", ::listFamily)
        context.handle("POST", "/members", ::addMember)
    }

    private fun listFamily(exchange: HttpExchange) {
        exchange.respond(200, """{"members":[{"id":1,"name":"Alice"},{"id":2,"name":"Bob"}]}""")
    }

    private fun addMember(exchange: HttpExchange) {
        val name = exchange.requestBody.bufferedReader().readText().trim()
        exchange.respond(201, """{"member":{"name":"$name"},"action":"added"}""")
    }
}