package g.sw.erp.auth

import com.sun.net.httpserver.HttpExchange
import g.sw.db.Db
import g.sw.spi.ErpModule
import g.sw.spi.Handler
import g.sw.spi.Identity
import g.sw.spi.Json
import g.sw.spi.MountContext
import g.sw.spi.bearerToken
import g.sw.spi.respond
import g.sw.spi.requireAuth
import java.util.Base64

/**
 * 成员与用户管理 / 认证: accounts with roles, password login (PBKDF2), SSH-key
 * login (ed25519 challenge/response) and persisted bearer-token sessions.
 *
 * Mounts at `/api/auth` and persists accounts in [Db] (collection `users`,
 * keyed by username). Implements [Identity] so every other module can demand a
 * signed-in user, or a role, through `g.sw.spi.requireAuth`.
 *
 * Roles: [ROLE_ADMIN] manages accounts; everyone else is [ROLE_MEMBER]. The
 * very first account to register becomes the bootstrap admin, so a fresh Star
 * needs no seeded credentials.
 */
class AuthModule(private val db: Db) : ErpModule, Identity {

    override val name: String = "auth"
    override val requires: List<String> = emptyList()

    private data class SshKeyRecord(
        val id: String,
        val algorithm: String,
        val blob: String,   // base64 of the OpenSSH wire blob
        val comment: String,
        val fingerprint: String,
    )

    /**
     * [role] is nullable only so records written before roles existed still
     * decode; read it through [roleOf].
     */
    private data class UserRecord(
        val username: String,
        val displayName: String,
        val salt: String,          // hex
        val passwordHash: String,  // hex
        val keys: List<SshKeyRecord>,
        val role: String?,
    )

    private val users = db.collection("users", UserRecord::class.java)
    private val store = Store(db)

    override fun mount(context: MountContext) {
        context.handle("POST", "/register", ::register)
        context.handle("POST", "/challenge", ::challenge)
        context.handle("POST", "/login", ::login)
        context.handle("POST", "/logout", requireAuth(this, handler = ::logout))
        context.handle("GET", "/me", requireAuth(this, handler = ::me))
        context.handle("GET", "/keys", requireAuth(this, handler = ::listKeys))
        context.handle("POST", "/keys", requireAuth(this, handler = ::addKey))
        context.handle("DELETE", "/keys", requireAuth(this, handler = ::deleteKey))
        context.handle("GET", "/users", requireAuth(this, ROLE_ADMIN, handler = ::listUsers))
        context.handle("POST", "/users", requireAuth(this, ROLE_ADMIN, handler = ::createUser))
        context.handle("POST", "/users/role", requireAuth(this, ROLE_ADMIN, handler = ::setRole))
        context.handle("DELETE", "/users", requireAuth(this, ROLE_ADMIN, handler = ::deleteUser))
    }

    // ---------------------------------------------------------------- Identity

    override fun resolve(token: String): String? = store.resolveToken(token)

    override fun roleOf(username: String): String? = users.get(username)?.let { it.role ?: ROLE_MEMBER }

    /** The account's display name, or null when there is no such account. */
    fun displayNameOf(username: String): String? = users.get(username)?.displayName

    // ---------------------------------------------------------------- handlers

    private fun register(exchange: HttpExchange) {
        val body = readJson(exchange)
        val keys = parseKeys(exchange, body) ?: return
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
            keys = keys,
            // whoever claims the empty Star becomes its admin
            role = if (users.size == 0) ROLE_ADMIN else ROLE_MEMBER,
        )
        users.put(username, user)
        exchange.respond(201, Json.write(mapOf("user" to publicUser(user))))
    }

    private fun challenge(exchange: HttpExchange) {
        val body = readJson(exchange)
        val username = (body["username"] as? String)?.trim().orEmpty()
        val user = users.get(username) ?: return exchange.respond(404, err("unknown user"))
        if (user.keys.isEmpty()) return exchange.respond(400, err("user has no ssh keys registered"))
        val challenge = store.issueChallenge(username)
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
        if (!store.consumeChallenge(username, challenge)) return exchange.respond(401, err("challenge missing, expired, or already used"))
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
        store.revokeToken(exchange.bearerToken())
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
        val record = parseKey(keyLine).getOrElse {
            return exchange.respond(400, err("invalid ssh key: ${it.message}"))
        }
        val user = users.get(username) ?: return exchange.respond(404, err("unknown user"))
        if (user.keys.any { it.fingerprint == record.fingerprint }) {
            return exchange.respond(409, err("key already registered"))
        }
        users.put(username, user.copy(keys = user.keys + record))
        exchange.respond(201, Json.write(mapOf("key" to publicKey(record))))
    }

    private fun deleteKey(exchange: HttpExchange, username: String) {
        val id = query(exchange, "id")
        if (id.isEmpty()) return exchange.respond(400, err("missing ?id="))
        val user = users.get(username) ?: return exchange.respond(404, err("unknown user"))
        if (user.keys.none { it.id == id }) return exchange.respond(404, err("key not found"))
        users.put(username, user.copy(keys = user.keys.filterNot { it.id == id }))
        exchange.respond(200, Json.write(mapOf("deleted" to id)))
    }

    // -------------------------------------------------- account management

    private fun listUsers(exchange: HttpExchange, username: String) {
        val list = users.entries().mapNotNull { (_, user) -> user }.sortedBy { it.username }
        exchange.respond(200, Json.write(mapOf("users" to list.map { accountSummary(it) })))
    }

    private fun createUser(exchange: HttpExchange, username: String) {
        val body = readJson(exchange)
        val name = (body["username"] as? String)?.trim().orEmpty()
        val password = body["password"] as? String ?: ""
        val displayName = (body["displayName"] as? String)?.trim().orEmpty()
        val role = (body["role"] as? String)?.trim()?.ifEmpty { null } ?: ROLE_MEMBER
        if (!validUsername(name)) return exchange.respond(400, err("username must be non-empty and consist of letters/digits/_/-"))
        if (password.length < 4) return exchange.respond(400, err("password too short (min 4 chars)"))
        if (role !in ROLES) return exchange.respond(400, err("role must be one of ${ROLES.joinToString("/")}"))
        if (users.get(name) != null) return exchange.respond(409, err("user already exists"))

        val salt = Password.newSalt()
        val user = UserRecord(
            username = name,
            displayName = displayName.ifEmpty { name },
            salt = Hex.encode(salt),
            passwordHash = Password.hash(password, salt),
            keys = emptyList(),
            role = role,
        )
        users.put(name, user)
        exchange.respond(201, Json.write(mapOf("user" to publicUser(user))))
    }

    private fun setRole(exchange: HttpExchange, username: String) {
        val body = readJson(exchange)
        val name = (body["username"] as? String)?.trim().orEmpty()
        val role = (body["role"] as? String)?.trim().orEmpty()
        if (role !in ROLES) return exchange.respond(400, err("role must be one of ${ROLES.joinToString("/")}"))
        val user = users.get(name) ?: return exchange.respond(404, err("unknown user"))
        if (user.role ?: ROLE_MEMBER == ROLE_ADMIN && role != ROLE_ADMIN && adminCount() <= 1) {
            return exchange.respond(409, err("cannot demote the last admin"))
        }
        users.put(name, user.copy(role = role))
        exchange.respond(200, Json.write(mapOf("user" to publicUser(user.copy(role = role)))))
    }

    private fun deleteUser(exchange: HttpExchange, username: String) {
        val name = query(exchange, "username")
        if (name.isEmpty()) return exchange.respond(400, err("missing ?username="))
        val user = users.get(name) ?: return exchange.respond(404, err("unknown user"))
        if (user.role ?: ROLE_MEMBER == ROLE_ADMIN && adminCount() <= 1) {
            return exchange.respond(409, err("cannot delete the last admin"))
        }
        users.delete(name)
        store.revokeUserSessions(name)
        exchange.respond(200, Json.write(mapOf("deleted" to name)))
    }

    // ---------------------------------------------------------------- helpers

    private fun issueSession(exchange: HttpExchange, user: UserRecord) {
        val token = store.issueToken(user.username)
        exchange.respond(200, Json.write(mapOf("token" to token, "user" to publicUser(user))))
    }

    private fun publicUser(user: UserRecord): Map<String, Any?> = mapOf(
        "username" to user.username,
        "displayName" to user.displayName,
        "role" to (user.role ?: ROLE_MEMBER),
        "keys" to user.keys.map { publicKey(it) },
    )

    private fun accountSummary(user: UserRecord): Map<String, Any?> = mapOf(
        "username" to user.username,
        "displayName" to user.displayName,
        "role" to (user.role ?: ROLE_MEMBER),
        "keys" to user.keys.size,
    )

    private fun publicKey(key: SshKeyRecord): Map<String, Any?> = mapOf(
        "id" to key.id,
        "algorithm" to key.algorithm,
        "comment" to key.comment,
        "fingerprint" to key.fingerprint,
    )

    private fun parseKey(keyLine: String): Result<SshKeyRecord> = runCatching {
        val info = SshKeys.parse(keyLine)
        SshKeyRecord(
            id = RandomId.next(),
            algorithm = info.algorithm,
            blob = Base64.getEncoder().encodeToString(info.blob),
            comment = info.comment,
            fingerprint = info.fingerprint,
        )
    }

    /** Parses the optional `"keys": ["ssh-ed25519 …", …]` register field. */
    private fun parseKeys(exchange: HttpExchange, body: Map<String, Any?>): List<SshKeyRecord>? {
        val lines = (body["keys"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val records = ArrayList<SshKeyRecord>(lines.size)
        for (line in lines) {
            val record = parseKey(line.trim()).getOrElse {
                exchange.respond(400, err("invalid ssh key: ${it.message}"))
                return null
            }
            if (records.any { it.fingerprint == record.fingerprint }) {
                exchange.respond(400, err("duplicate ssh key"))
                return null
            }
            records += record
        }
        return records
    }

    private fun adminCount(): Int = users.entries().count { (_, user) -> (user?.role ?: ROLE_MEMBER) == ROLE_ADMIN }

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

    private fun validUsername(username: String): Boolean = username.isNotEmpty() && username.all {
        it.isLetterOrDigit() || it == '_' || it == '-'
    }

    private fun err(message: String): String = Json.write(mapOf("error" to message))

    companion object {
        const val ROLE_ADMIN = "admin"
        const val ROLE_MEMBER = "member"
        val ROLES = listOf(ROLE_ADMIN, ROLE_MEMBER)
    }
}
