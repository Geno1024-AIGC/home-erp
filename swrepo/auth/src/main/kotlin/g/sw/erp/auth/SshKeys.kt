package g.sw.erp.auth

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * OpenSSH public key handling for ed25519 (JDK-native EdDSA, zero dependency).
 *
 * Parses the standard `ssh-ed25519 <base64> [comment]` line, computes
 * `ssh-keygen -lf`-style `SHA256:<base64>` fingerprints, and verifies a
 * signature over the challenge bytes.
 */
object SshKeys {

    const val ALGORITHM = "ssh-ed25519"

    data class PublicKeyInfo(
        val algorithm: String,
        val blob: ByteArray,
        val keyBytes: ByteArray,
        val comment: String,
        val fingerprint: String,
    )

    fun parse(line: String): PublicKeyInfo {
        val parts = line.trim().split(Regex("\\s+"), limit = 3)
        require(parts.size >= 2) { "invalid OpenSSH public key line" }
        require(parts[0] == ALGORITHM) { "unsupported key algorithm '${parts[0]}' (only $ALGORITHM)" }
        val blob = try {
            Base64.getDecoder().decode(parts[1])
        } catch (_: IllegalArgumentException) {
            error("invalid base64 in public key")
        }
        val (algo, next) = readString(blob, 0)
        require(String(algo, Charsets.US_ASCII) == ALGORITHM) { "key blob algorithm mismatch" }
        val (keyBytes, _) = readString(blob, next)
        require(keyBytes.size == 32) { "ed25519 key must be 32 bytes" }
        val comment = parts.getOrNull(2) ?: ""
        return PublicKeyInfo(ALGORITHM, blob, keyBytes, comment, fingerprint(blob))
    }

    fun fingerprint(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun verify(info: PublicKeyInfo, challengeBytes: ByteArray, signature: ByteArray): Boolean {
        return runCatching {
            val key = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(subjectPublicKeyInfo(info.keyBytes)))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update(challengeBytes)
            verifier.verify(signature)
        }.getOrDefault(false)
    }

    /** Rebuild a key from its stored record fields. */
    fun fromRegistered(algorithm: String, blobBase64: String, comment: String, fingerprint: String): PublicKeyInfo? =
        runCatching {
            require(algorithm == ALGORITHM)
            val blob = Base64.getDecoder().decode(blobBase64)
            val (algo, next) = readString(blob, 0)
            check(String(algo, Charsets.US_ASCII) == ALGORITHM)
            val (keyBytes, _) = readString(blob, next)
            require(keyBytes.size == 32)
            PublicKeyInfo(ALGORITHM, blob, keyBytes, comment, fingerprint)
        }.getOrNull()

    /** DER SubjectPublicKeyInfo for a raw 32-byte ed25519 public key. */
    private fun subjectPublicKeyInfo(key: ByteArray): ByteArray {
        // AlgorithmIdentifier: SEQUENCE { OID 1.3.101.112 }
        val algorithmId = byteArrayOf(0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70)
        // BIT STRING content: 0x00 unused-bits prefix + key (33 octets).
        val bitString = ByteArray(key.size + 3)
        bitString[0] = 0x03
        bitString[1] = (key.size + 1).toByte()
        bitString[2] = 0x00
        key.copyInto(bitString, 3)
        val total = algorithmId.size + bitString.size
        val out = ByteArray(total + 2)
        out[0] = 0x30
        out[1] = total.toByte()
        algorithmId.copyInto(out, 2)
        bitString.copyInto(out, 2 + algorithmId.size)
        return out
    }

    /** Read an SSH wire `string` (uint32 length + bytes) from [blob] at [offset]. */
    private fun readString(blob: ByteArray, offset: Int): Pair<ByteArray, Int> {
        require(offset + 4 <= blob.size) { "truncated key blob" }
        val len = ((blob[offset].toInt() and 0xFF) shl 24) or
            ((blob[offset + 1].toInt() and 0xFF) shl 16) or
            ((blob[offset + 2].toInt() and 0xFF) shl 8) or
            (blob[offset + 3].toInt() and 0xFF)
        require(offset + 4 + len <= blob.size) { "truncated key blob string" }
        return blob.copyOfRange(offset + 4, offset + 4 + len) to offset + 4 + len
    }
}