# swrepo/heds — `g.sw.heds`

The **HEDS** container format (Home ERP Descriptor Segment bundle, file suffix `.heapp`)
and its reference implementation. A `g.sw.heds.Heds.Bundle` is a **self-describing
single file** that a satellite discovers at startup to build its feature list
(name + optional embedded icon) and opens on demand to read a **UI DSL** document
and, later, any number of **VM bytecode** segments.

The bundle is **independent of any host**: it does not depend on `swrepo/db`, the
Star, or any single satellite platform. It is plain UTF-8 text with a tiny
segment framing (metadata block + `==== segment ====` frames; binary payloads
are Base64), parseable by a few dozen lines of hand-rolled code on JVM or
browser. Reusable outside this project.

## What it provides

- `Heds.parse(text)` / `Heds.write(bundle)` — validates (magic, required
  metadata, `ui` segment presence + JSON validity, Base64 payloads) and
  round-trips a `Heds.Bundle`.
- `Heds.uiJson(bundle)` — parses the `ui` segment into the JSON model
  (`g.sw.spi.Json`).
- `Bundle` model — `id`, `name`, `version`, `summary`, `meta` (extra metadata,
  preserved), optional `icon` bytes, `ui` source, `vms` (`engine -> ByteArray`).

## Sections

- [FORMAT.md](FORMAT.md) — the normative container + UI DSL spec (v1).
- [samples/inventory.heapp](samples/inventory.heapp) — a hand-written example:
  metadata + embedded icon + a `page`/`list`/`text`/`button` UI tree and an
  `url:` action.

```kotlin
val bundle = Heds.parse(Files.readString(Path.of("inventory.heapp")))
bundle.name              // "库存" — feature-list title
bundle.icon              // ByteArray? — feature-list icon
Heds.uiJson(bundle)      // UI DSL tree for the renderer
```

Smoke round-trip + failure cases: `./gradlew :swrepo:heds:smoke`.