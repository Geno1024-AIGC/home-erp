# Home ERP

A home-scenario ERP system. Kotlin-first, Gradle KTS. All feature logic is hand-rolled on the JDK + Kotlin standard library — no external database, no web framework, no front-end framework.

## Architecture (3 tiers)

| Tier | Codename | Role |
|---|---|---|
| 恒星 **Star** | primary server at home | hosts core services and data |
| 行星 **Planet** | cloud server | service discovery and relay (port-forward-like), so remote satellites can reach the Star |
| 卫星 **Satellite** | Android app / browser page | talks to the Star directly when home, via the Planet when remote |

## Modules

Each module documents itself in its own README.

| Module | Package | README |
|---|---|---|
| `swrepo:spi` | `g.sw.spi` | [spi](swrepo/spi/README.md) — module SPI: `ErpModule`, `MountContext`, HTTP helpers |
| `swrepo:relay` | `g.sw.relay` | [relay](swrepo/relay/README.md) — Planet↔Star tunnel: reusable frame codec, relay pool and dialing client |
| `swrepo:auth` | `g.sw.erp.auth` | [auth](swrepo/auth/README.md) — accounts & auth: password (PBKDF2) + SSH-key (ed25519) login |
| `swrepo:members` | `g.sw.erp.members` | [members](swrepo/members/README.md) — family members / users |
| `swrepo:inventory` | `g.sw.erp.inventory` | [inventory](swrepo/inventory/README.md) — household items & stock |
| `swrepo:finances` | `g.sw.erp.finances` | [finances](swrepo/finances/README.md) — home bills & budget |
| `swrepo:chores` | `g.sw.erp.chores` | [chores](swrepo/chores/README.md) — housework & schedule |
| `swrepo:db` | `g.sw.db` | [db](swrepo/db/README.md) — lightweight append-only-log database |
| `swrepo:topology` | `g.sw.erp.topology` | [topology](swrepo/topology/README.md) — deployment-address book: Star persists it, Planets cache it, Satellites probe it |
| `swrepo:gef` | `g.sw.gef` | [gef](swrepo/gef/README.md) — self-describing bundle formats for satellite features: v1 UI DSL text + v0.1 zip/HTML (WebView) containers |
| `star` | `g.erp.star` | [star](star/README.md) — the Star application: assembles modules onto one JDK `HttpServer` |
| `planet` | `g.erp.planet` | [planet](planet/README.md) — the Planet application: cloud discovery + HTTP relay to Stars |
| `gefs` | `g.erp.gefs` | [gefs](gefs/README.md) — packs the satellite feature packages (one HTML GEF per feature) shipped with every canary release |
| `satellite:android` | `g.erp.satellite` | [satellite/android](satellite/android/README.md) — the Android satellite: side-drawer app browsing the Star's HTTP API (zero AndroidX, plain framework UI) |

See [swrepo/README.md](swrepo/README.md) for the software repository layout.

## Build & run

Requires **JDK 25** (sourced from `~/.jdks` via `gradle.properties`); Gradle wrapper is **9.7.1**.

```bash
./gradlew build     # compile everything; bumps each module's pack counter
./gradlew run       # start the Star on http://localhost:8080
./gradlew :planet:run --args="--http=9090 --tunnel=9091"   # start the Planet relay
./gradlew :star:run --args="8080 --planet=mirror.example:9090"  # Star dialing a Planet
./gradlew :swrepo:db:smoke   # run the db self-test
./gradlew :swrepo:relay:smoke  # run the Planet↔Star tunnel round-trip self-test
./gradlew :swrepo:topology:smoke  # run the topology address-book self-test
./gradlew :swrepo:gef:smoke  # run the GEF bundle format round-trip self-test
./gradlew :gefs:pack  # rebuild the satellite HTML GEF feature packages into gefs/build/gefs
./gradlew :satellite:android:assembleDebug  # build the Android satellite APK
```

## Satellite release & update

CI (`.github/workflows/canary.yml`) builds the whole repo on every push to `master` (`workflow_dispatch` also available) and publishes one GitHub **pre-release** (Canary) carrying the Star and Planet dist zips plus the debug/release APKs and the generated GEF feature packages (from `gefs/build/gefs/*.gef`). Exactly one pre-release is kept — the previous one is deleted on each run.

- Both APKs are signed with the shared debug keystore `satellite/android/signing/debug.jks` (standard `androiddebugkey`, password `android`), so every CI run produces the **same signature** and a Canary update installs over the previous one, no GitHub secrets required. A future stable channel may swap in a secret signing key without touching the pipeline.
- In-app **设置 → 更新**: pick 更新渠道 (**Canary** = pre-releases, **正式版** = regular releases — none published yet) and 更新源 (GitHub or mirror prefixes ghproxy / gh-proxy / ghfast.top), then 检查更新 and 下载并安装.
- In-app **设置 → 软件仓库** adds standalone features by installing GEF packages from the same release: **从发布同步功能包…** downloads the canary GEF feature packages through the selected 更新源, installs whichever is newer than the installed copy, and lists what was updated / already latest / failed. Feature pages are HTML apps served file:// and talking to the Star only through the `Erp` bridge (network is intercepted).
- Updates are fetched from `api.github.com` and downloaded through the selected mirror prefix; the cached APK is installed via the framework `PackageInstaller` session API, with the result surfaced through a manifest `BroadcastReceiver` notification (zero AndroidX). Details in [satellite/android/README.md](satellite/android/README.md).

## Versioning

`0.1.<env>.<pack>.<sha1>` — `<env>` is an environment monotonic sequence (GitHub Actions run number on CI, otherwise the repo's commit count), `<pack>` is the module's git-tracked packaging counter `count.pack` (bumped by `jar`/`assemble`/`build`/`dist*`; Android `assembleDebug`/`assembleRelease`/`bundle*`). Details and all conventions live in [`AGENTS.md`](AGENTS.md).