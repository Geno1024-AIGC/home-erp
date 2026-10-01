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
- Every package carries a generated `icon.png` (64×64 truecolour): a rounded
  plate in the feature accent colour with a white geometric mark on top — two
  members, a taped parcel, ascending bars, a check mark. Marks are built from
  circles, domes, round-rects and round-capped strokes in `drawMark`, drawn at
  8× supersample and box-filtered down, so curves and diagonals stay smooth
  instead of stepping like a bitmap glyph. The plate corners match the
  satellite drawer row background, so the icon reads as a floating chip. No
  image resources, no third-party encoder.

The satellite side consumes these through 设置 → 软件仓库 → 从发布同步功能包…
(see [satellite/android](../satellite/android/README.md)).
