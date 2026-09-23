package g.sw.store

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory

object StoreSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = createTempDirectory("store-smoke")
        dir.toFile().deleteOnExit()

        Store.open(dir).use { store ->
            val family = store.collection("family")
            family.put("alice", "Alice".encodeToByteArray())
            family.put("bob", "Bob".encodeToByteArray())
            check(family.size == 2)
            check(family.get("alice").contentEquals("Alice".encodeToByteArray()))
            check(family.ids() == setOf("alice", "bob"))
            family.delete("bob")
            check(family.size == 1)
            check(family.get("bob") == null)
        }

        Store.open(dir).use { store ->
            val family = store.collection("family")
            check(family.size == 1)
            check(family.get("alice").contentEquals("Alice".encodeToByteArray()))
        }

        Store.open(dir).use { store ->
            val chores = store.collection("chores")
            for (i in 1..500) chores.put("task-$i", "payload-$i".encodeToByteArray())
            for (i in 1..300) chores.delete("task-$i")
            check(chores.size == 200)
            check(chores.get("task-499").contentEquals("payload-499".encodeToByteArray()))
        }

        Store.open(dir).use { store ->
            check(store.collection("chores").size == 200)
            check(store.collection("family").size == 1)
        }

        val log = dir.resolve("data.log")
        Files.writeString(log, "garbage line without tabs\n", StandardOpenOption.APPEND)
        Files.writeString(log, "42\tP\tfamily\taGVscA==\tYmFk\n", StandardOpenOption.APPEND)

        Store.open(dir).use { store ->
            check(store.collection("chores").size == 200)
            val family = store.collection("family")
            check(family.size == 1)
            check(family.get("alice").contentEquals("Alice".encodeToByteArray()))
        }

        println("[smoke] all checks passed")
    }
}