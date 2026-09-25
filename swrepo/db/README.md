# swrepo/db — `g.sw.db`

Hand-rolled lightweight database for the Home ERP Star — **Kotlin stdlib only**. Append-only write-ahead log + in-memory index, no external database.

Part of [Home ERP](../../README.md) (swrepo).

## Format

- **Append-only write-ahead log**: one text line per record — `seq\top\tcollection\tid\tpayload` (`id` and `payload` Base64-encoded, so any bytes/newlines are safe).
- Operators: `P` = put/upsert, `D` = delete (tombstone), `S` = schema declaration (records which class a collection holds).
- **In-memory index** rebuilt by replaying the log on open; each append is `force`d to disk before the index is updated in memory.
- Auto-compaction: when the number of dead records reaches the number of live records, the log is rewritten in place.
- Torn/corrupt tail lines are truncated on replay (crash-safe for partial writes).

```kotlin
Db.open(Path.of("data")).use { db ->
    val family = db.collection("family")
    family.put("alice", "Alice".encodeToByteArray())
    family.get("alice")
    family.delete("bob")
}
```

## Direct data-class IO

Any plain data class goes in/out as-is — **no annotation, no interface, no `Serializable`**. `g.sw.db.Codec` walks the class's fields via reflection (stdlib only) and encodes each value in a compact tagged binary format:

- **Native raw encoding** for primitives and `String` (performance: no boxed reflection in the hot path).
- **Special-cased common classes** — no reflection, no library needed:
  - date/time: `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `ZonedDateTime`, `OffsetDateTime`, `Period`, `Duration`, `Year`, `YearMonth`, `MonthDay`, `ZoneId`, legacy `java.util.Date`;
  - numeric/misc: `BigDecimal`, `BigInteger`, `UUID`, enums;
  - collections: `List`, `Set`, `Map` (recursively, any nesting).

```kotlin
data class Person(val name: String, val age: Int)   // plain data class, nothing else

Db.open(Path.of("data")).use { db ->
    val people = db.collection("people")                    // untyped: pass the class per call
    people.put("carol", Person("Carol", 30))
    val carol: Person? = people.get("carol", Person::class.java)

    val typed = db.collection("people", Person::class.java) // typed: class is recorded per collection
    typed.put("carol", Person("Carol", 30))
    val again: Person? = typed.get("carol")                 // no class literal needed
}
```

A collection's schema is written as an `S` record on first typed use and survives replay and compaction. Declaring a collection with a *different* class than stored throws immediately — drift is caught at open time instead of failing reads. Use the untyped `collection(name)` for raw bytes only.

## Binary attachments stay out of the DB

Records hold only a relative attachment key; the bytes live under the data folder's `files/` and are moved in/out only through `Db`, so **one data folder is the complete movable unit** — migrate/back up by copying it.

```kotlin
Db.open(Path.of("data")).use { db ->
    val photo = db.adoptAttachment(Path.of("/tmp/pan.jpg"), "inventory/pan-2026.jpg")
    val item = db.collection("inventory")
    item.put("pan", Item("frying pan", "inventory/pan-2026.jpg"))
    val key = item.get("pan", Item::class.java).photo
    Files.readAllBytes(db.attachment(key))  // resolves inside the data folder
}
```

## Notes

- Field metadata (reflection) is cached per class; `getObject`/`get` are shared by all threads through a per-collection lock.
- The payload carries the class name and field names, so normalize a record by re-`put` after changing a data class's shape — old blobs won't match the new constructor.
- Attachment keys are validated relative paths (`a-z0-9_-`, `/`-separated); `..`, absolute paths and anything escaping the folder are rejected. Deleting a record does not delete its attachment — orphans are reclaimed manually or by a future cleaner.

## Test

```bash
./gradlew :swrepo:db:smoke
```