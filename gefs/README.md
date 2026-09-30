# gefs — `g.erp.gefs`

Packs the satellite **feature packages** for Home ERP. Every canary release
carries one GEF package per feature — v0.1 HTML zips generated from
`FEATURES` in `FeaturePack.kt` (a single spec table → one generic page each),
so the whole satellite feature list lives in one place.

- `./gradlew :gefs:pack` — writes `build/gefs/<slug>.gef` (members, inventory,
  finances, chores), each validated by an `HtmlGef.pack` → `unpack` round-trip.
- Pages are self-contained HTML: list a Star API endpoint (e.g.
  `GET /api/inventory/items`) rendered as a table, and call it through
  `window.Erp.apiGet/apiPost` — the only network path a GEF has. Members also
  gains a plain-text add form (`POST /api/members/members`).
- Each package stamps the module version (`0.1.<env>.<pack>.<sha1>`, see
  [VersioningPlugin](../build-logic/src/main/kotlin/g/build/versioning/VersioningPlugin.kt))
  into `manifest.json`; the satellite compares `(env, pack)` to decide whether
  an install is an update. `pack` also bumps `gefs/count.pack`.

The satellite side consumes these through 设置 → 软件仓库 → 从发布同步功能包…
(see [satellite/android](../satellite/android/README.md)).