package g.sw.db

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

internal object Serde {

    fun encode(value: Serializable): ByteArray =
        ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { oos -> oos.writeObject(value) }
            bos.toByteArray()
        }

    fun <T> decode(bytes: ByteArray, type: Class<T>): T? =
        try {
            ObjectInputStream(ByteArrayInputStream(bytes)).use { ois ->
                val obj = ois.readObject()
                if (type.isInstance(obj)) type.cast(obj) else null
            }
        } catch (_: Exception) {
            null
        }
}