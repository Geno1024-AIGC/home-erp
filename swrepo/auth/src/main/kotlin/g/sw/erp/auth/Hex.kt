package g.sw.erp.auth

/** Hex encode/decode helpers (JDK only). */
object Hex {

    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex: '${hex.take(16)}…'" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}