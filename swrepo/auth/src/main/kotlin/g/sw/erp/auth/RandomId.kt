package g.sw.erp.auth

import java.security.SecureRandom

/** Short random hex ids for stored ssh keys. */
object RandomId {

    private val random = SecureRandom()

    fun next(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}