package g.sw.db

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

class Db private constructor(private val dir: Path) : Closeable {

    private val lock = Any()

    private var channel: FileChannel
    private val tables = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private var nextSeq = 1L
    private var liveCount = 0L
    private var deadCount = 0L

    init {
        Files.createDirectories(dir)
        channel = FileChannel.open(
            dir.resolve(LOG),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )
        replay()
    }

    fun collection(name: String): Collection {
        check(NAME.matches(name)) { "invalid collection name: '$name'" }
        return Collection(this, name)
    }

    fun compact() {
        synchronized(lock) {
            val tmp = dir.resolve("$LOG.tmp")
            FileChannel.open(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { out ->
                var seq = 1L
                for ((table, rows) in tables) {
                    for ((id, blob) in rows) {
                        writeFully(out, encodeLine(seq, 'P', table, id, blob))
                        seq++
                    }
                }
                out.force(true)
                nextSeq = seq
            }
            channel.close()
            Files.move(tmp, dir.resolve(LOG), StandardCopyOption.REPLACE_EXISTING)
            channel = FileChannel.open(
                dir.resolve(LOG),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            liveCount = tables.values.sumOf { it.size.toLong() }
            deadCount = 0L
        }
    }

    override fun close() {
        synchronized(lock) {
            channel.close()
        }
    }

    internal fun get(table: String, id: String): ByteArray? = synchronized(lock) {
        tables[table]?.get(id)
    }

    internal fun put(table: String, id: String, blob: ByteArray) = synchronized(lock) {
        apply('P', table, id, blob)
    }

    internal fun delete(table: String, id: String) = synchronized(lock) {
        apply('D', table, id, null)
    }

    internal fun size(table: String): Int = synchronized(lock) {
        tables[table]?.size ?: 0
    }

    internal fun ids(table: String): Set<String> = synchronized(lock) {
        tables[table]?.keys?.toSet() ?: emptySet()
    }

    internal fun entries(table: String): List<Pair<String, ByteArray>> = synchronized(lock) {
        tables[table]?.entries?.map { it.key to it.value } ?: emptyList()
    }

    private fun apply(op: Char, table: String, id: String, blob: ByteArray?) {
        writeFully(channel, encodeLine(nextSeq++, op, table, id, blob))
        when (op) {
            'P' -> {
                val rows = tables.getOrPut(table) { mutableMapOf() }
                if (id !in rows) liveCount++
                rows[id] = blob!!
            }
            'D' -> {
                if (tables[table]?.remove(id) != null) liveCount--
                deadCount++
            }
        }
        if (deadCount > 0L && deadCount >= liveCount) compact()
    }

    private fun encodeLine(seq: Long, op: Char, table: String, id: String, blob: ByteArray?): ByteArray {
        val b64Id = Base64.getEncoder().encodeToString(id.toByteArray(StandardCharsets.UTF_8))
        val b64Blob = blob?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()
        return "$seq\t$op\t$table\t$b64Id\t$b64Blob\n".toByteArray(StandardCharsets.UTF_8)
    }

    private fun writeFully(out: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        out.position(out.size())
        while (buffer.hasRemaining()) {
            out.write(buffer)
        }
        out.force(false)
    }

    private fun replay() {
        val size = channel.size()
        if (size == 0L) return
        val data = ByteBuffer.allocate(size.toInt())
        channel.position(0)
        while (data.hasRemaining()) {
            if (channel.read(data) < 0) break
        }
        data.flip()
        val text = StandardCharsets.UTF_8.decode(data).toString()
        var consumed = 0
        for (line in text.lines()) {
            if (line.isEmpty()) {
                consumed += 1
                continue
            }
            if (!applyLine(line)) break
            consumed += line.length + 1
        }
        if (consumed.toLong() < size) {
            channel.truncate(consumed.toLong())
        }
        liveCount = tables.values.sumOf { it.size.toLong() }
        deadCount = 0L
        channel.position(channel.size())
    }

    private fun applyLine(line: String): Boolean {
        val parts = line.split('\t')
        if (parts.size != 5) return false
        val seq = parts[0].toLongOrNull() ?: return false
        val op = when (parts[1]) {
            "P" -> 'P'
            "D" -> 'D'
            else -> return false
        }
        val table = parts[2]
        val id = decode(parts[3])?.toString(StandardCharsets.UTF_8) ?: return false
        when (op) {
            'P' -> {
                val blob = decode(parts[4]) ?: return false
                tables.getOrPut(table) { mutableMapOf() }[id] = blob
            }
            'D' -> tables[table]?.remove(id)
        }
        if (seq >= nextSeq) nextSeq = seq + 1
        return true
    }

    private fun decode(encoded: String): ByteArray? = try {
        Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
        null
    }

    class Collection internal constructor(
        private val db: Db,
        val name: String,
    ) {
        fun get(id: String): ByteArray? = db.get(name, id)
        fun put(id: String, blob: ByteArray) = db.put(name, id, blob)

        fun put(id: String, value: Any) = db.put(name, id, Codec.encode(value))
        fun <T> get(id: String, type: Class<T>): T? = db.get(name, id)?.let { Codec.decode(it, type) }

        inline fun <reified T : Any> getObject(id: String): T? = get(id, T::class.java)

        fun delete(id: String) = db.delete(name, id)
        val size: Int get() = db.size(name)
        fun ids(): Set<String> = db.ids(name)
        fun entries(): List<Pair<String, ByteArray>> = db.entries(name)
    }

    companion object {
        private const val LOG = "data.log"
        private val NAME = Regex("[a-z0-9_-]+")

        fun open(dir: Path): Db = Db(dir)
    }
}