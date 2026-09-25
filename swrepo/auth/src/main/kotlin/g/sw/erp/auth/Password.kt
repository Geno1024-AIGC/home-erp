package g.sw.erp.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Password hashing via JDK PBKDF2WithHmacSHA256 — no third-party dependency. */
object Password {

    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return Hex.encode(derived.encoded)
    }

    fun verify(password: String, salt: ByteArray, expectedHashHex: String): Boolean {
        val computed = hash(password, salt)
        return MessageDigest.isEqual(
            computed.toByteArray(Charsets.UTF_8),
            expectedHashHex.toByteArray(Charsets.UTF_8),
        )
    }
}