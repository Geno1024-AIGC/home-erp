package g.sw.erp.auth

import g.sw.db.Db
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

private data class SessionRecord(val username: String, val expiresAt: Long)

/**
 * Login sessions and one-time SSH challenges.
 *
 * Tokens live in [Db] (collection `sessions`) so restarting the Star no longer
 * signs everyone out. Challenges stay in memory on purpose: they are one-time,
 * five minutes old at most, and are worthless after a restart anyway.
 */
class Store(private val db: Db) {

    private val sessions = db.collection("sessions", SessionRecord::class.java)
    private val challenges = ConcurrentHashMap<String, Pair<String, Long>>() // username -> (challenge, expiresAt)

    fun issueToken(username: String): String {
        pruneExpired()
        val token = randomToken()
        sessions.put(token, SessionRecord(username, System.currentTimeMillis() + SESSION_TTL_MS))
        return token
    }

    fun resolveToken(token: String): String? {
        if (token.isEmpty()) return null
        val session = sessions.get(token) ?: return null
        if (session.expiresAt < System.currentTimeMillis()) {
            sessions.delete(token)
            return null
        }
        return session.username
    }

    fun revokeToken(token: String) {
        if (token.isNotEmpty()) sessions.delete(token)
    }

    /** Drops every live session of [username] — used when an account is deleted. */
    fun revokeUserSessions(username: String) {
        for ((token, session) in sessions.entries()) {
            if (session?.username == username) sessions.delete(token)
        }
    }

    /** Households are small, so a linear sweep on login is cheaper than a timer. */
    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        for ((token, session) in sessions.entries()) {
            if (session != null && session.expiresAt < now) sessions.delete(token)
        }
    }

    fun issueChallenge(username: String): String {
        val challenge = randomToken()
        challenges[username] = challenge to (System.currentTimeMillis() + CHALLENGE_TTL_MS)
        return challenge
    }

    /** One-time, TTL-bounded challenge consumption. */
    fun consumeChallenge(username: String, challenge: String): Boolean = synchronized(this) {
        val entry = challenges.remove(username) ?: return false
        entry.first == challenge && entry.second >= System.currentTimeMillis()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val SESSION_TTL_MS = 7L * 24 * 3600 * 1000
        const val CHALLENGE_TTL_MS = 5L * 60 * 1000
        val random = SecureRandom()
    }
}
