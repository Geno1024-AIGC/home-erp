# AGENTS.md

## Project Overview

Home ERP — a home-scenario ERP system. Kotlin-first, Gradle KTS build.

## Deployment Architecture (3-Tier)

Three deployment tiers, named after celestial bodies:

- **恒星 (Star)** — the primary home server sitting inside the user's home; hosts the core services and data.
- **行星 (Planet)** — a cloud server; provides service discovery and relay (port forwarding-like) so that clients outside the home can reach the Star through the Planet.
- **卫星 (Satellite)** — a client running on home computers, phones, or in browsers; talks to the Star directly when home, and via the Planet when remote. Primary satellite surfaces: **Android app** and **browser page**.

## Star Extensibility & swrepo

- The Star can **flexibly add or remove feature modules** at runtime/deploy time.
- Feature modules are aggregated in **swrepo** (software repository); even cross-cutting common features (e.g. user management) may live in swrepo.
- Modules may **depend on each other**; dependency relationships must be modeled.
- Some swrepo entries are designed to be **reusable outside this project**.
- **swrepo form**: a **monorepo** — all modules live in one Git repo, managed as Gradle multi-module; the Star assembles a deployment by selecting modules.

## Initial Feature Scope (1.0)

Planned for the first release; **each feature is one swrepo module**:

1. **成员与用户管理 (members/users)** — family members, accounts, auth, permissions.
2. **家居物品/库存 (household inventory)** — items, storage locations, quantities, expiry/expiration reminders, shopping lists.
3. **家庭账单/预算 (finances/budget)** — income/expense ledger, budgets, bill splitting.
4. **家务/日程 (chores/schedule)** — chore assignment, schedules, reminders.

## Storage & HTTP Policy

- **Star persistence**: hand-rolled lightweight storage in `swrepo/store` (`g.sw.store`): append-only WAL (one text line per record, `seq\top\tcollection\tid\tpayload`, id/payload Base64) + in-memory index rebuilt by replay, force-to-disk per append, inline compaction when dead records reach live records; no external database dependency.
- **HTTP API**: use only the JDK built-in `com.sun.net.httpserver.HttpServer`; no web framework.
- **Browser satellite**: plain native HTML/CSS/JS front end speaking to the HTTP API; no front-end framework.

## Commit Message Convention

Commits must be **atomic** — one logical change per commit. Dense, very frequent commits are encouraged and expected (small steps beat big batches).

Strict format: `<bracket><action><bracket> <module>: <description>`

- **Bracket** encodes change size:
  - `{}` — large change (architecture, new module, cross-cutting rework)
  - `[]` — medium change (feature work, non-trivial fix/refactor)
  - `()` — small change (typo, doc tweak, minor fix)
- **Action** is a verb: `Update`, `Fix`, `Typo`, `Refactor`, `Add`, `Remove`, `Docs`, `Test`, `Chore` (case-sensitive CamelCase)
- **Module** is lowercase; project-wide changes use `project`; nested modules use `/` (e.g. `inventory/stock`)
- **Description** is a short English summary ending with a period `.`

Examples:

```
{Add} project: Bootstrap Gradle KTS build with Kotlin application plugin
[Refactor] inventory/stock: Extract reservation logic into dedicated service
(Typo) docs: Fix wording in README
```

## Build & Language

- Build system: **Gradle KTS** (`build.gradle.kts`, `settings.gradle.kts`)
- Primary language: **Kotlin**
- Java toolchain: **JDK 25** (source from `~/.jdks`, wired via `org.gradle.java.installations.paths` in `gradle.properties`); all modules use `jvmToolchain(25)`.
- Dependency policy: implement features yourself wherever reasonable; the **Kotlin standard library only** by default. Any third-party dependency must be justified and re-considered carefully before adding. No new dependency without weighing its cost against a hand-rolled solution.

## Package Convention

Base namespaces, chosen by context:

- `g.erp` — the Star application itself (run/deploy side).
- `g.sw` — swrepo entries that are generic and reusable outside this project (e.g. `g.sw.spi`).
- `g.sw.erp` — swrepo entries specific to the home-ERP domain (e.g. `g.sw.erp.members`).

## Versioning

- Version format: `0.1.<pack>.<build>-<sha1>` (base `0.1` for every module; `sha1` = first 8 chars of HEAD commit).
- Counters are **per-module**: each Gradle module keeps its own git-tracked plain-integer files `<module>/count.pack` and `<module>/count.build`.
- Increments (implemented by a Gradle plugin in `build-logic`, wired via `dependsOn`):
  - `pack` increments on packaging tasks (`jar`, `assemble`, `build`, `distTar`/`distZip`/`installDist`).
  - `build` increments on `run`.
- Semantics: a session reads counters at configuration time to form the version, then increments them as a side effect; the stamped version therefore counts **completed** events, so rebuilding at a given commit reproduces the same version.

## Code Style

- Prefer clear, self-explanatory code; no unnecessary comments
- Keep modules aligned with domain boundaries (will be defined as requirements are refined)
