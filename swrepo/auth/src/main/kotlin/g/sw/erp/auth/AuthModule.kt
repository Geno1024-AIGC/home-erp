package g.sw.erp.auth

import com.sun.net.httpserver.HttpExchange
import g.sw.db.Db
import g.sw.spi.ErpModule
import g.sw.spi.Handler
import g.sw.spi.Json
import g.sw.spi.MountContext
import g.sw.spi.respond
import java.util.Base64

/**
 * 成员与用户管理 / 认证: users, password login (PBKDF2) and SSH-key login
 * (ed25519 challenge/response), bearer-token sessions.
 *
 * Mounts at `/api/auth` and persists users in [Db] (collection `users`,
 * keyed by username). Todo: sessions/challenges are in-memory only.
 */
class AuthModule(private val db: Db) : ErpModule {

    override val name: String = "auth"
    override val requires: List<String> = emptyList()

    private data class SshKeyRecord(
        val id: String,
        val algorithm: String,
        val blob: String,   // base64 of the OpenSSH wire blob
        val comment: String,
        val fingerprint: String,
    )

    private data class UserRecord(
        val username: String,
        val displayName: String,
        val salt: String,          // hex
        val passwordHash: String,  // hex
        val keys: List<SshKeyRecord>,
    )

    private val users = db.collection("users", UserRecord::class.java)

    override fun mount(context: MountContext) {
        context.handle("POST", "/register", ::register)
        context.handle("POST", "/challenge", ::challenge)
        context.handle("POST", "/login", ::login)
        context.handle("POST", "/logout", requireAuth(::logout))
        context.handle("GET", "/me", requireAuth(::me))
        context.handle("GET", "/keys", requireAuth(::listKeys))
        context.handle("POST", "/keys", requireAuth(::addKey))
        context.handle("DELETE", "/keys", requireAuth(::deleteKey))
    }

    // ---------------------------------------------------------------- handlers

    private fun register(exchange: HttpExchange) {
        val body = readJson(exchange)
        val username = (body["username"] as? String)?.trim().orEmpty()
        val password = body["password"] as? String ?: ""
        val displayName = (body["displayName"] as? String)?.trim().orEmpty()
        if (!validUsername(username)) return exchange.respond(400, err("username must be non-empty and consist of letters/digits/_/-"))
        if (password.length < 4) return exchange.respond(400, err("password too short (min 4 chars)"))
        if (users.get(username) != null) return exchange.respond(409, err("user already exists"))

        val salt = Password.newSalt()
        val user = UserRecord(
            username = username,
            displayName = displayName.ifEmpty { username },
            salt = Hex.encode(salt),
            passwordHash = Password.hash(password, salt),
            keys = emptyList(),
        )
        users.put(username, user)
        exchange.respond(201, Json.write(mapOf("user" to publicUser(user))))
    }

    private fun challenge(exchange: HttpExchange) {
        val body = readJson(exchange)
        val username = (body["username"] as? String)?.trim().orEmpty()
        val user = users.get(username) ?: return exchange.respond(404, err("unknown user"))
        if (user.keys.isEmpty()) return exchange.respond(400, err("user has no ssh keys registered"))
        val challenge = Store.issueChallenge(username)
        exchange.respond(200, Json.write(mapOf("challenge" to challenge, "expiresIn" to 300L)))
    }

    private fun login(exchange: HttpExchange) {
        val body = readJson(exchange)
        when (body["method"]) {
            "password" -> loginPassword(exchange, body)
            "ssh" -> loginSsh(exchange, body)
            else -> exchange.respond(400, err("method must be 'password' or 'ssh'"))
        }
    }

    private fun loginPassword(exchange: HttpExchange, body: Map<String, Any?>) {
        val username = (body["username"] as? String)?.trim().orEmpty()
        val password = body["password"] as? String ?: ""
        val user = users.get(username)
        if (user == null || !Password.verify(password, Hex.decode(user.salt), user.passwordHash)) {
            return exchange.respond(401, err("bad username or password"))
        }
        issueSession(exchange, user)
    }

    private fun loginSsh(exchange: HttpExchange, body: Map<String, Any?>) {
        val username = (body["username"] as? String)?.trim().orEmpty()
        val challenge = body["challenge"] as? String ?: ""
        val fingerprint = body["fingerprint"] as? String ?: ""
        val signature = body["signature"] as? String ?: ""
        val user = users.get(username)
        if (user == null) return exchange.respond(401, err("bad credentials"))
        val key = user.keys.firstOrNull { it.fingerprint == fingerprint } ?: return exchange.respond(401, err("unknown key"))
        if (!Store.consumeChallenge(username, challenge)) return exchange.respond(401, err("challenge missing, expired, or already used"))
        val info = SshKeys.fromRegistered(key.algorithm, key.blob, key.comment, key.fingerprint)
            ?: return exchange.respond(401, err("key data invalid"))
        val signatureBytes = runCatching { Base64.getDecoder().decode(signature) }.getOrNull()
            ?: return exchange.respond(401, err("signature not valid base64"))
        if (!SshKeys.verify(info, challenge.toByteArray(Charsets.UTF_8), signatureBytes)) {
            return exchange.respond(401, err("signature verification failed"))
        }
        issueSession(exchange, user)
    }

    private fun logout(exchange: HttpExchange, username: String) {
        Store.revokeToken(bearer(exchange))
        exchange.respond(200, Json.write(mapOf("ok" to true)))
    }

    private fun me(exchange: HttpExchange, username: String) {
        val user = users.get(username)
        if (user == null) return exchange.respond(404, err("unknown user"))
        exchange.respond(200, Json.write(mapOf("user" to publicUser(user))))
    }

    private fun listKeys(exchange: HttpExchange, username: String) {
        val user = users.get(username) ?: return exchange.respond(404, err("unknown user"))
        exchange.respond(200, Json.write(mapOf("keys" to user.keys.map { publicKey(it) })))
    }

    private fun addKey(exchange: HttpExchange, username: String) {
        val body = readJson(exchange)
        val keyLine = (body["key"] as? String)?.trim().orEmpty()
        val info = runCatching { SshKeys.parse(keyLine) }.getOrElse {
            return exchange.respond(400, err("invalid ssh key: ${it.message}"))
        }
        val user = users.get(username) ?: return exchange.respond(404, err("unknown user"))
        if (user.keys.any { it.fingerprint == info.fingerprint }) {
            return exchange.respond(409, err("key already registered"))
        }
        val record = SshKeyRecord(
            id = RandomId.next(),
            algorithm = info.algorithm,
            blob = Base64.getEncoder().encodeToString(info.blob),
            comment = info.comment,
            fingerprint = info.fingerprint,
        )
        users.put(username, user.copy(keys = user.keys + record))
        exchange.respond(201, Json.write(mapOf("key" to publicKey(record))))
    }

    private fun deleteKey(exchange: HttpExchange, username: String) {
        val query = exchange.requestURI.rawQuery.orEmpty()
        val id = query.split('&').mapNotNull { pair ->
            val (k, v) = pair.split('=', limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
            if (k == "id" && !v.isNullOrEmpty()) v else null
        }.firstOrNull().orEmpty()
        if (id.isEmpty()) return exchange.respond(400, err("missing ?id="))
        val user = users.get(username) ?: return exchange.respond(404, err("unknown user"))
        if (user.keys.none { it.id == id }) return exchange.respond(404, err("key not found"))
        users.put(username, user.copy(keys = user.keys.filterNot { it.id == id }))
        exchange.respond(200, Json.write(mapOf("deleted" to id)))
    }

    // ---------------------------------------------------------------- helpers

    private fun issueSession(exchange: HttpExchange, user: UserRecord) {
        val token = Store.issueToken(user.username)
        exchange.respond(200, Json.write(mapOf("token" to token, "user" to publicUser(user))))
    }

    private fun publicUser(user: UserRecord): Map<String, Any?> = mapOf(
        "username" to user.username,
        "displayName" to user.displayName,
        "keys" to user.keys.map { publicKey(it) },
    )

    private fun publicKey(key: SshKeyRecord): Map<String, Any?> = mapOf(
        "id" to key.id,
        "algorithm" to key.algorithm,
        "comment" to key.comment,
        "fingerprint" to key.fingerprint,
    )

    private fun requireAuth(handler: (HttpExchange, String) -> Unit): Handler = { exchange ->
        val username = bearer(exchange).let(Store::resolveToken)
        if (username == null) exchange.respond(401, err("missing or invalid bearer token"))
        else handler(exchange, username)
    }

    private fun bearer(exchange: HttpExchange): String {
        val auth = exchange.requestHeaders.getFirst("Authorization") ?: return ""
        return auth.removePrefix("Bearer ").trim()
    }

    private fun readJson(exchange: HttpExchange): Map<String, Any?> {
        val text = exchange.requestBody.bufferedReader().use { it.readText() }
        return (runCatching { Json.parse(text) }.getOrNull() as? Map<*, *>)?.entries
            ?.associate { (k, v) -> k.toString() to v }
            ?: emptyMap()
    }

    private fun validUsername(username: String): Boolean = username.isNotEmpty() && username.all {
        it.isLetterOrDigit() || it == '_' || it == '-'
    }

    private fun err(message: String): String = Json.write(mapOf("error" to message))
}