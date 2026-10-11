package g.sw.spi

import com.sun.net.httpserver.HttpExchange

/**
 * Who is calling. `g.sw.erp.auth` is the only implementation; feature modules
 * depend on this SPI so they can demand a signed-in user — or a specific role
 * — without depending on auth's internals.
 */
interface Identity {
    /** Bearer token -> username, or null when the token is invalid or expired. */
    fun resolve(token: String): String?

    /** Username -> role name, or null when the account no longer exists. */
    fun roleOf(username: String): String?
}

/** The `Authorization: Bearer <token>` credential, or empty when absent. */
fun HttpExchange.bearerToken(): String =
    (requestHeaders.getFirst("Authorization") ?: "").removePrefix("Bearer ").trim()

/**
 * Wraps [handler] so it only runs for a signed-in user, optionally restricted
 * to one of [roles]; the username is passed through as the second argument.
 * An empty [roles] means "any signed-in user".
 */
fun requireAuth(
    identity: Identity,
    vararg roles: String,
    handler: (HttpExchange, String) -> Unit,
): Handler = { exchange ->
    val username = identity.resolve(exchange.bearerToken())
    when {
        username == null ->
            exchange.respond(401, Json.write(mapOf("error" to "missing or invalid bearer token")))
        identity.roleOf(username) == null ->
            exchange.respond(401, Json.write(mapOf("error" to "account no longer exists")))
        roles.isNotEmpty() && identity.roleOf(username) !in roles ->
            exchange.respond(403, Json.write(mapOf("error" to "role '${identity.roleOf(username)}' may not do that")))
        else -> handler(exchange, username)
    }
}
