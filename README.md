# Home ERP

A home-scenario ERP system. Kotlin-first, Gradle KTS. All feature logic is hand-rolled on the JDK + Kotlin standard library — no external database, no web framework, no front-end framework.

## Architecture (3 tiers)

| Tier | Codename | Role |
|---|---|---|
| 恒星 **Star** | primary server at home | hosts core services and data |
| 行星 **Planet** | cloud server | service discovery and relay (port-forward-like), so remote satellites can reach the Star |
| 卫星 **Satellite** | Android app / browser page | talks to the Star directly when home, via the Planet when remote |

## Modules (swrepo)

Feature modules live in `swrepo/` as Gradle sub-projects — a monorepo software repository. The Star assembles a deployment by selecting modules; modules may depend on each other.

| Module | Package | Purpose |
|---|---|---|
| `swrepo:spi` | `g.sw.spi` | module SPI: `ErpModule`, `MountContext`, HTTP helpers |
| `swrepo:members` | `g.sw.erp.members` | family members / users |
| `swrepo:inventory` | `g.sw.erp.inventory` | household items & stock |
| `swrepo:finances` | `g.sw.erp.finances` | home bills & budget |
| `swrepo:chores` | `g.sw.erp.chores` | housework & schedule |
| `swrepo:db` | `g.sw.db` | lightweight append-only-log database |
| `star` | `g.erp.star` | the Star application: assembles modules onto one JDK `HttpServer` |
| `android` | `g.erp.satellite` | the Android satellite: side-drawer app browsing the Star's HTTP API (zero AndroidX, plain framework UI) |

## Storage

The simple database lives in `swrepo/db` (`g.sw.db`), **Kotlin stdlib only**:

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

**Direct data-class IO**: any plain data class goes in/out as-is — **no annotation, no interface, no `Serializable`**. `g.sw.db.Codec` walks the class's fields via reflection (stdlib only) and encodes each value in a compact tagged binary format:

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

**Binary attachments stay out of the DB**: records hold only a relative attachment key; the bytes live under the data folder's `files/` and are moved in/out only through `Db`, so **one data folder is the complete movable unit** — migrate/back up by copying it.

```kotlin
Db.open(Path.of("data")).use { db ->
    val photo = db.adoptAttachment(Path.of("/tmp/pan.jpg"), "inventory/pan-2026.jpg")
    val item = db.collection("inventory")
    item.put("pan", Item("frying pan", "inventory/pan-2026.jpg"))
    val key = item.get("pan", Item::class.java).photo
    Files.readAllBytes(db.attachment(key))  // resolves inside the data folder
}
```

Notes:
- Field metadata (reflection) is cached per class; `getObject`/`get` are shared by all threads through a per-collection lock.
- The payload carries the class name and field names, so normalize a record by re-`put` after changing a data class's shape — old blobs won't match the new constructor.
- Attachment keys are validated relative paths (`a-z0-9_-`, `/`-separated); `..`, absolute paths and anything escaping the folder are rejected. Deleting a record does not delete its attachment — orphans are reclaimed manually or by a future cleaner.

## Build & run

Requires **JDK 25** (sourced from `~/.jdks` via `gradle.properties`); Gradle wrapper is **9.7.1**.

```bash
./gradlew build     # compile everything; bumps each module's pack counter
./gradlew run       # start the Star on http://localhost:8080
./gradlew :swrepo:db:smoke   # run the db self-test
./gradlew :android:assembleDebug  # build the Android satellite APK
```

Sample endpoints served by the Star:

| Endpoint | Description |
|---|---|
| `GET /api/members/family` | list family members |
| `POST /api/members/members` | add a member |
| `GET /api/inventory/items` | list stock items |
| `GET /api/finances/ledger` | list ledger entries |
| `GET /api/chores/tasks` | list chores |

## Satellite release & update

CI (`.github/workflows/canary.yml`) builds the whole repo on every push to `master` (`workflow_dispatch` also available) and publishes one GitHub **pre-release** (Canary) carrying the Star dist zip plus the debug/release APKs. Exactly one pre-release is kept — the previous one is deleted on each run.

- Both APKs are signed with the shared debug keystore `android/signing/debug.jks` (standard `androiddebugkey`, password `android`), so every CI run produces the **same signature** and a Canary update installs over the previous one, no GitHub secrets required. A future stable channel may swap in a secret signing key without touching the pipeline.
- In-app **设置 → 更新**: pick 更新渠道 (**Canary** = pre-releases, **正式版** = regular releases — none published yet) and 更新源 (GitHub or mirror prefixes ghproxy / gh-proxy / ghfast.top), then 检查更新 and 下载并安装.
- Updates are fetched from `api.github.com` and downloaded through the selected mirror prefix; the cached APK is served to the package installer through `ApkProvider`, a hand-rolled framework `ContentProvider` (no FileProvider, zero AndroidX).

## Versioning

`0.1.<pack>.<build>-<sha1>` — per-module git-tracked counters (`count.pack`, `count.build`). Packaging tasks bump `pack` (JVM `jar`/`assemble`/`build`/`dist*`; Android `assembleDebug`/`assembleRelease`/`bundle*`); running/installing bumps `build` (JVM `run`; Android `install*`). Details and all conventions live in [`AGENTS.md`](AGENTS.md).