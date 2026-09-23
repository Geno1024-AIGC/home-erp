package g.sw.erp.finances

import com.sun.net.httpserver.HttpExchange
import g.sw.spi.ErpModule
import g.sw.spi.MountContext
import g.sw.spi.respond

class FinancesModule : ErpModule {
    override val name: String = "finances"
    override val requires: List<String> = listOf("members")

    override fun mount(context: MountContext) {
        context.handle("GET", "/ledger", ::listLedger)
    }

    private fun listLedger(exchange: HttpExchange) {
        exchange.respond(
            200,
            """{"ledger":[{"id":1,"amount":-19.9,"note":"超市"},{"id":2,"amount":5000.0,"note":"工资"}]}""",
        )
    }
}