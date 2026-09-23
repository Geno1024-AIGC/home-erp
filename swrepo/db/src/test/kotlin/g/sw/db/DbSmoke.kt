package g.sw.db

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.math.BigDecimal
import kotlin.io.path.createTempDirectory

data class Person(val name: String, val age: Int, val tags: List<String>)

enum class Role { GUEST, COOK, ADMIN }

data class Event(
    val title: String,
    val at: LocalDateTime,
    val day: LocalDate,
    val zone: ZoneId,
    val cost: BigDecimal,
    val roles: List<Role>,
    val meta: Map<String, String>,
    val wait: Duration,
    val span: Period,
)

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
            val people = db.collection("people")
            people.put("carol", Person("Carol", 30, listOf("guest", "cook")))
            people.put("martin", Person("Martin", 45, emptyList()))
            check(people.get("carol", Person::class.java) == Person("Carol", 30, listOf("guest", "cook")))
            check(people.get("martin", Person::class.java) == Person("Martin", 45, emptyList()))
            check(people.get("carol", String::class.java) == null)
            check(people.get("missing", Person::class.java) == null)
            val carolObj: Person? = people.getObject("carol")
            check(carolObj == Person("Carol", 30, listOf("guest", "cook")))
            people.delete("martin")
        }

        Db.open(dir).use { db ->
            val people = db.collection("people")
            check(people.size == 1)
            check(people.get("carol", Person::class.java) == Person("Carol", 30, listOf("guest", "cook")))
            check(people.get("martin", Person::class.java) == null)
        }

        val dinner = Event(
            "dinner",
            LocalDateTime.of(2026, 9, 23, 19, 30),
            LocalDate.of(2026, 9, 23),
            ZoneId.of("Asia/Shanghai"),
            BigDecimal("88.50"),
            listOf(Role.GUEST, Role.COOK),
            mapOf("venue" to "home"),
            Duration.ofHours(2),
            Period.of(1, 2, 3),
        )

        Db.open(dir).use { db ->
            val events = db.collection("events")
            events.put("dinner", dinner)
            check(events.get("dinner", Event::class.java) == dinner)
            val again: Event? = events.getObject("dinner")
            check(again == dinner)
        }

        Db.open(dir).use { db ->
            check(db.collection("events").get("dinner", Event::class.java) == dinner)
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