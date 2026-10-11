package g.sw.erp.finances

import com.sun.net.httpserver.HttpExchange
import g.sw.erp.auth.AuthModule
import g.sw.spi.ErpModule
import g.sw.spi.MountContext
import g.sw.spi.respond
import g.sw.spi.requireAuth

/** 家庭账单/预算. Read-only canned data for now; every call needs a signed-in user. */
class FinancesModule(private val auth: AuthModule) : ErpModule {
    override val name: String = "finances"
    override val requires: List<String> = listOf("auth", "members")

    override fun mount(context: MountContext) {
        context.handle("GET", "/ledger", requireAuth(auth) { exchange, _ -> listLedger(exchange) })
    }

    private fun listLedger(exchange: HttpExchange) {
        exchange.respond(
            200,
            """{"ledger":[{"id":1,"amount":-19.9,"note":"超市"},{"id":2,"amount":5000.0,"note":"工资"}]}""",
        )
    }
}
