package g.sw.erp.auth

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** In-memory login sessions and one-time SSH challenges (not persisted in 1.0). */
object Store {

    private const val SESSION_TTL_MS = 7L * 24 * 3600 * 1000
    private const val CHALLENGE_TTL_MS = 5L * 60 * 1000
    private val random = SecureRandom()

    private data class Session(val username: String, val expiresAt: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val challenges = ConcurrentHashMap<String, Pair<String, Long>>() // username -> (challenge, expiresAt)

    fun issueToken(username: String): String {
        val token = randomToken()
        sessions[token] = Session(username, System.currentTimeMillis() + SESSION_TTL_MS)
        return token
    }

    fun resolveToken(token: String): String? {
        val session = sessions[token] ?: return null
        if (session.expiresAt < System.currentTimeMillis()) {
            sessions.remove(token)
            return null
        }
        return session.username
    }

    fun revokeToken(token: String) {
        sessions.remove(token)
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
}