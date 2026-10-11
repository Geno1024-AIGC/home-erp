package g.sw.erp.members

import com.sun.net.httpserver.HttpExchange
import g.sw.db.Db
import g.sw.erp.auth.AuthModule
import g.sw.erp.auth.RandomId
import g.sw.spi.ErpModule
import g.sw.spi.Json
import g.sw.spi.MountContext
import g.sw.spi.respond
import g.sw.spi.requireAuth

/**
 * 家庭成员名册. A member is a person in the household; when [MemberRecord.username]
 * is set that person also owns a login account in `auth`, and the account's role
 * is what decides permissions — this module never invents roles of its own.
 *
 * Reading the roster needs any signed-in user; changing it needs an admin.
 */
class MembersModule(private val db: Db, private val auth: AuthModule) : ErpModule {
    override val name: String = "members"
    override val requires: List<String> = listOf("auth")

    private data class MemberRecord(val id: String, val name: String, val username: String?)

    private val members = db.collection("members", MemberRecord::class.java)

    override fun mount(context: MountContext) {
        context.handle("GET", "/family", requireAuth(auth) { exchange, _ -> listFamily(exchange) })
        context.handle("POST", "/members", requireAuth(auth, AuthModule.ROLE_ADMIN) { exchange, _ -> addMember(exchange) })
        context.handle("DELETE", "/members", requireAuth(auth, AuthModule.ROLE_ADMIN) { exchange, _ -> deleteMember(exchange) })
    }

    private fun listFamily(exchange: HttpExchange) {
        val list = members.entries()
            .mapNotNull { (_, member) -> member }
            .sortedBy { it.name }
            .map { member ->
                mapOf(
                    "id" to member.id,
                    "name" to member.name,
                    "username" to member.username,
                    "role" to member.username?.let(auth::roleOf),
                )
            }
        exchange.respond(200, Json.write(mapOf("members" to list)))
    }

    private fun addMember(exchange: HttpExchange) {
        val body = readJson(exchange)
        val name = (body["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return exchange.respond(400, err("name must be non-empty"))
        val username = (body["username"] as? String)?.trim()?.ifEmpty { null }
        if (username != null && auth.displayNameOf(username) == null) {
            return exchange.respond(400, err("no account named '$username'"))
        }
        val alreadyLinked = members.entries().any { (_, member) -> member?.username != null && member.username == username }
        if (alreadyLinked) return exchange.respond(409, err("account already linked to a member"))
        val record = MemberRecord(id = RandomId.next(), name = name, username = username)
        members.put(record.id, record)
        exchange.respond(201, Json.write(mapOf("member" to publicMember(record))))
    }

    private fun deleteMember(exchange: HttpExchange) {
        val id = query(exchange, "id")
        if (id.isEmpty()) return exchange.respond(400, err("missing ?id="))
        val member = members.get(id) ?: return exchange.respond(404, err("member not found"))
        members.delete(id)
        exchange.respond(200, Json.write(mapOf("deleted" to member.id)))
    }

    private fun publicMember(member: MemberRecord): Map<String, Any?> = mapOf(
        "id" to member.id,
        "name" to member.name,
        "username" to member.username,
    )

    private fun query(exchange: HttpExchange, name: String): String =
        exchange.requestURI.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { pair ->
            val (k, v) = pair.split('=', limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
            if (k == name && !v.isNullOrEmpty()) v else null
        }.orEmpty()

    private fun readJson(exchange: HttpExchange): Map<String, Any?> {
        val text = exchange.requestBody.bufferedReader().use { it.readText() }
        return (runCatching { Json.parse(text) }.getOrNull() as? Map<*, *>)?.entries
            ?.associate { (k, v) -> k.toString() to v }
            ?: emptyMap()
    }

    private fun err(message: String): String = Json.write(mapOf("error" to message))
}
