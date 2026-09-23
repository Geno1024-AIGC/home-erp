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
| `swrepo:store` | `g.sw.store` | lightweight append-only-log database |
| `star` | `g.erp.star` | the Star application: assembles modules onto one JDK `HttpServer` |

## Storage

The simple database lives in `swrepo/store` (`g.sw.store`), **Kotlin stdlib only**:

- **Append-only write-ahead log**: one text line per record — `seq\top\tcollection\tid\tpayload` (`id` and `payload` Base64-encoded, so any bytes/newlines are safe).
- Operators: `P` = put/upsert, `D` = delete (tombstone).
- **In-memory index** rebuilt by replaying the log on open; each append is `force`d to disk before the index is updated in memory.
- Auto-compaction: when the number of dead records reaches the number of live records, the log is rewritten in place.
- Torn/corrupt tail lines are truncated on replay (crash-safe for partial writes).

```kotlin
Store.open(Path.of("data")).use { store ->
    val family = store.collection("family")
    family.put("alice", "Alice".encodeToByteArray())
    family.get("alice")
    family.delete("bob")
}
```

## Build & run

Requires **JDK 25** (sourced from `~/.jdks` via `gradle.properties`); Gradle wrapper is **9.7.1**.

```bash
./gradlew build     # compile everything; bumps each module's pack counter
./gradlew run       # start the Star on http://localhost:8080
./gradlew :swrepo:store:smoke   # run the store self-test
```

Sample endpoints served by the Star:

| Endpoint | Description |
|---|---|
| `GET /api/members/family` | list family members |
| `POST /api/members/members` | add a member |
| `GET /api/inventory/items` | list stock items |
| `GET /api/finances/ledger` | list ledger entries |
| `GET /api/chores/tasks` | list chores |

## Versioning

`0.1.<pack>.<build>-<sha1>` — per-module git-tracked counters (`count.pack`, `count.build`). Packaging tasks bump `pack`; running the app bumps `build`. Details and all conventions live in [`AGENTS.md`](AGENTS.md).