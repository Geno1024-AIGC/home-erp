package g.sw.db

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory

object DbSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = createTempDirectory("db-smoke")
        dir.toFile().deleteOnExit()

        Db.open(dir).use { db ->
            val family = db.collection("family")
            family.put("alice", "Alice".encodeToByteArray())
            family.put("bob", "Bob".encodeToByteArray())
            check(family.size == 2)
            check(family.get("alice").contentEquals("Alice".encodeToByteArray()))
            check(family.ids() == setOf("alice", "bob"))
            family.delete("bob")
            check(family.size == 1)
            check(family.get("bob") == null)
        }

        Db.open(dir).use { db ->
            val family = db.collection("family")
            check(family.size == 1)
            check(family.get("alice").contentEquals("Alice".encodeToByteArray()))
        }

        Db.open(dir).use { db ->
            val chores = db.collection("chores")
            for (i in 1..500) chores.put("task-$i", "payload-$i".encodeToByteArray())
            for (i in 1..300) chores.delete("task-$i")
            check(chores.size == 200)
            check(chores.get("task-499").contentEquals("payload-499".encodeToByteArray()))
        }

        Db.open(dir).use { db ->
            check(db.collection("chores").size == 200)
            check(db.collection("family").size == 1)
        }

        val log = dir.resolve("data.log")
        Files.writeString(log, "garbage line without tabs\n", StandardOpenOption.APPEND)
        Files.writeString(log, "42\tP\tfamily\taGVscA==\tYmFk\n", StandardOpenOption.APPEND)

        Db.open(dir).use { db ->
            check(db.collection("chores").size == 200)
            val family = db.collection("family")
            check(family.size == 1)
            check(family.get("alice").contentEquals("Alice".encodeToByteArray()))
        }

        println("[smoke] all checks passed")
    }
}