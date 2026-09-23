package g.sw.db

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.Period
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

object Codec {

    private const val T_NULL = 0
    private const val T_BOOL = 1
    private const val T_BYTE = 2
    private const val T_SHORT = 3
    private const val T_INT = 4
    private const val T_LONG = 5
    private const val T_FLOAT = 6
    private const val T_DOUBLE = 7
    private const val T_CHAR = 8
    private const val T_STRING = 9
    private const val T_BYTES = 10
    private const val T_UUID = 11
    private const val T_ENUM = 12
    private const val T_DATA = 13
    private const val T_LIST = 14
    private const val T_SET = 15
    private const val T_MAP = 16
    private const val T_LOCAL_DATE = 17
    private const val T_LOCAL_TIME = 18
    private const val T_LOCAL_DATE_TIME = 19
    private const val T_INSTANT = 20
    private const val T_ZONED_DATE_TIME = 21
    private const val T_OFFSET_DATE_TIME = 22
    private const val T_DATE = 23
    private const val T_DURATION = 24
    private const val T_PERIOD = 25
    private const val T_ZONE_ID = 26
    private const val T_BIG_DECIMAL = 27
    private const val T_BIG_INTEGER = 28
    private const val T_YEAR_MONTH = 29
    private const val T_YEAR = 30
    private const val T_MONTH_DAY = 31

    fun encode(value: Any): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> writeValue(out, value) }
        return bytes.toByteArray()
    }

    fun <T> decode(bytes: ByteArray, type: Class<T>): T? {
        val effective = WRAPPERS[type] ?: type
        val value = try {
            readValue(DataInputStream(ByteArrayInputStream(bytes)))
        } catch (_: Exception) {
            null
        }
        @Suppress("UNCHECKED_CAST")
        return if (effective.isInstance(value)) effective.cast(value) as T? else null
    }

    fun decode(bytes: ByteArray, type: KClass<*>): Any? = decode(bytes, type.java)

    private fun writeValue(out: DataOutputStream, v: Any?) {
        if (v == null) {
            out.writeByte(T_NULL)
            return
        }
        when (v) {
            is Boolean -> {
                out.writeByte(T_BOOL); out.writeBoolean(v)
            }
            is Byte -> {
                out.writeByte(T_BYTE); out.writeByte(v.toInt())
            }
            is Short -> {
                out.writeByte(T_SHORT); out.writeShort(v.toInt())
            }
            is Int -> {
                out.writeByte(T_INT); out.writeInt(v)
            }
            is Long -> {
                out.writeByte(T_LONG); out.writeLong(v)
            }
            is Float -> {
                out.writeByte(T_FLOAT); out.writeFloat(v)
            }
            is Double -> {
                out.writeByte(T_DOUBLE); out.writeDouble(v)
            }
            is Char -> {
                out.writeByte(T_CHAR); out.writeChar(v.code)
            }
            is String -> {
                out.writeByte(T_STRING); writeString(out, v)
            }
            is ByteArray -> {
                out.writeByte(T_BYTES); out.writeInt(v.size); out.write(v)
            }
            is UUID -> {
                out.writeByte(T_UUID); out.writeLong(v.mostSignificantBits); out.writeLong(v.leastSignificantBits)
            }
            is LocalDate -> {
                out.writeByte(T_LOCAL_DATE); out.writeLong(v.toEpochDay())
            }
            is LocalTime -> {
                out.writeByte(T_LOCAL_TIME); out.writeLong(v.toNanoOfDay())
            }
            is LocalDateTime -> {
                out.writeByte(T_LOCAL_DATE_TIME); out.writeLong(v.toLocalDate().toEpochDay()); out.writeLong(v.toLocalTime().toNanoOfDay())
            }
            is Instant -> {
                out.writeByte(T_INSTANT); out.writeLong(v.epochSecond); out.writeInt(v.nano)
            }
            is ZonedDateTime -> {
                out.writeByte(T_ZONED_DATE_TIME); out.writeLong(v.toEpochSecond()); out.writeInt(v.nano); writeString(out, v.zone.id)
            }
            is OffsetDateTime -> {
                out.writeByte(T_OFFSET_DATE_TIME); out.writeLong(v.toEpochSecond()); out.writeInt(v.nano); out.writeInt(v.offset.totalSeconds)
            }
            is java.util.Date -> {
                out.writeByte(T_DATE); out.writeLong(v.time)
            }
            is Duration -> {
                out.writeByte(T_DURATION); out.writeLong(v.seconds); out.writeLong(v.nano.toLong())
            }
            is Period -> {
                out.writeByte(T_PERIOD); out.writeInt(v.years); out.writeInt(v.months); out.writeInt(v.days)
            }
            is ZoneId -> {
                out.writeByte(T_ZONE_ID); writeString(out, v.id)
            }
            is BigDecimal -> {
                out.writeByte(T_BIG_DECIMAL); writeString(out, v.unscaledValue().toString()); out.writeInt(v.scale())
            }
            is BigInteger -> {
                out.writeByte(T_BIG_INTEGER); writeString(out, v.toString())
            }
            is YearMonth -> {
                out.writeByte(T_YEAR_MONTH); out.writeInt(v.year); out.writeInt(v.monthValue)
            }
            is Year -> {
                out.writeByte(T_YEAR); out.writeInt(v.value)
            }
            is MonthDay -> {
                out.writeByte(T_MONTH_DAY); out.writeInt(v.monthValue); out.writeInt(v.dayOfMonth)
            }
            is Enum<*> -> {
                out.writeByte(T_ENUM); writeString(out, v.javaClass.name); writeString(out, v.name)
            }
            is List<*> -> {
                out.writeByte(T_LIST); out.writeInt(v.size); v.forEach { writeValue(out, it) }
            }
            is Set<*> -> {
                out.writeByte(T_SET); out.writeInt(v.size); v.forEach { writeValue(out, it) }
            }
            is Map<*, *> -> {
                out.writeByte(T_MAP); out.writeInt(v.size); v.forEach { (k, value) -> writeValue(out, k); writeValue(out, value) }
            }
            else -> writeData(out, v)
        }
    }

    private fun writeData(out: DataOutputStream, v: Any) {
        out.writeByte(T_DATA)
        writeString(out, v.javaClass.name)
        val meta = meta(v.javaClass)
        out.writeInt(meta.fields.size)
        for ((name, field) in meta.fields) {
            writeString(out, name)
            writeValue(out, field.get(v))
        }
    }

    private fun readValue(input: DataInputStream): Any? {
        when (val tag = input.readByte().toInt()) {
            T_NULL -> return null
            T_BOOL -> return input.readBoolean()
            T_BYTE -> return input.readByte()
            T_SHORT -> return input.readShort()
            T_INT -> return input.readInt()
            T_LONG -> return input.readLong()
            T_FLOAT -> return input.readFloat()
            T_DOUBLE -> return input.readDouble()
            T_CHAR -> return input.readChar()
            T_STRING -> return readString(input)
            T_BYTES -> {
                val n = input.readInt()
                val bytes = ByteArray(n)
                input.readFully(bytes)
                return bytes
            }
            T_UUID -> return UUID(input.readLong(), input.readLong())
            T_ENUM -> {
                val clazz = classByName(readString(input)) ?: return null
                val name = readString(input)
                return clazz.getMethod("valueOf", String::class.java).invoke(null, name)
            }
            T_DATA -> return readData(input)
            T_LIST -> {
                val n = input.readInt()
                val list = ArrayList<Any?>(n)
                repeat(n) { list.add(readValue(input)) }
                return list
            }
            T_SET -> {
                val n = input.readInt()
                val set = LinkedHashSet<Any?>(n)
                repeat(n) { set.add(readValue(input)) }
                return set
            }
            T_MAP -> {
                val n = input.readInt()
                val map = LinkedHashMap<Any?, Any?>(n)
                repeat(n) { map[readValue(input)] = readValue(input) }
                return map
            }
            T_LOCAL_DATE -> return LocalDate.ofEpochDay(input.readLong())
            T_LOCAL_TIME -> return LocalTime.ofNanoOfDay(input.readLong())
            T_LOCAL_DATE_TIME -> return LocalDateTime.of(LocalDate.ofEpochDay(input.readLong()), LocalTime.ofNanoOfDay(input.readLong()))
            T_INSTANT -> return Instant.ofEpochSecond(input.readLong(), input.readInt().toLong())
            T_ZONED_DATE_TIME -> return ZonedDateTime.ofInstant(Instant.ofEpochSecond(input.readLong(), input.readInt().toLong()), ZoneId.of(readString(input)))
            T_OFFSET_DATE_TIME -> return OffsetDateTime.ofInstant(Instant.ofEpochSecond(input.readLong(), input.readInt().toLong()), ZoneOffset.ofTotalSeconds(input.readInt()))
            T_DATE -> return java.util.Date(input.readLong())
            T_DURATION -> return Duration.ofSeconds(input.readLong(), input.readLong())
            T_PERIOD -> return Period.of(input.readInt(), input.readInt(), input.readInt())
            T_ZONE_ID -> return ZoneId.of(readString(input))
            T_BIG_DECIMAL -> return BigDecimal(BigInteger(readString(input)), input.readInt())
            T_BIG_INTEGER -> return BigInteger(readString(input))
            T_YEAR_MONTH -> return YearMonth.of(input.readInt(), input.readInt())
            T_YEAR -> return Year.of(input.readInt())
            T_MONTH_DAY -> return MonthDay.of(input.readInt(), input.readInt())
            else -> return null
        }
    }

    private fun readData(input: DataInputStream): Any? {
        val clazz = classByName(readString(input)) ?: return null
        val meta = meta(clazz)
        val count = input.readInt()
        val values = HashMap<String, Any?>(count)
        repeat(count) {
            val name = readString(input)
            values[name] = readValue(input)
        }
        return meta.instantiate(values)
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val n = input.readInt()
        check(n >= 0) { "Corrupt record: negative string length." }
        val bytes = ByteArray(n)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private class Meta(val fields: List<Pair<String, Field>>, val constructor: Constructor<*>?, val argKeys: List<String>) {
        fun instantiate(values: Map<String, Any?>): Any? {
            val ctor = constructor ?: return null
            if (argKeys.size != ctor.parameterCount) return null
            val args = argKeys.map { values[it] }
            return runCatching { ctor.newInstance(*args.toTypedArray()) }.getOrNull()
        }
    }

    private val cache = ConcurrentHashMap<Class<*>, Meta>()

    private fun meta(clazz: Class<*>): Meta = cache.computeIfAbsent(clazz) {
        val fields = clazz.declaredFields
            .filter { f -> !Modifier.isStatic(f.modifiers) && !f.isSynthetic }
            .map { f ->
                f.isAccessible = true
                f.name to f
            }
        val ctor = clazz.declaredConstructors
            .filter { it.parameterCount == fields.size }
            .firstOrNull { c -> c.parameters.isNotEmpty() && c.parameters.all { p -> p.isNamePresent() } }
            ?: clazz.declaredConstructors.maxByOrNull { it.parameterCount }
        // Kotlin keeps no constructor parameter names in bytecode, so fall back to
        // positional alignment: field declaration order == constructor parameter order.
        val argKeys = when {
            ctor == null -> emptyList()
            ctor.parameterCount == fields.size && ctor.parameters.all { it.isNamePresent() } ->
                ctor.parameters.map { it.name }
            else -> fields.map { it.first }
        }
        Meta(fields, ctor, argKeys)
    }

    fun classByName(name: String): Class<*>? {
        val loader = Thread.currentThread().contextClassLoader
            ?: Codec::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
        return runCatching { Class.forName(name, false, loader) }.getOrNull()
    }

    private val WRAPPERS = mapOf(
        java.lang.Boolean.TYPE to java.lang.Boolean::class.java,
        java.lang.Byte.TYPE to java.lang.Byte::class.java,
        java.lang.Short.TYPE to java.lang.Short::class.java,
        java.lang.Integer.TYPE to java.lang.Integer::class.java,
        java.lang.Long.TYPE to java.lang.Long::class.java,
        java.lang.Float.TYPE to java.lang.Float::class.java,
        java.lang.Double.TYPE to java.lang.Double::class.java,
        java.lang.Character.TYPE to java.lang.Character::class.java,
    )
}