# swrepo/gef — `g.sw.gef`

The **GEF** container format (Geno's Executable Format, file suffix `.gef`)
and its reference implementation. A `g.sw.gef.Gef.Bundle` is a **self-describing
single file** that a satellite discovers at startup to build its feature list
(name + optional embedded icon) and opens on demand to read a **UI DSL**
document and, later, any number of **VM bytecode** segments.

The bundle is **independent of any host**: it does not depend on `swrepo/db`, the
Star, or any single satellite platform. It is plain UTF-8 text with a tiny
segment framing (metadata block + `==== segment ====` frames; binary payloads
are Base64), parseable by a few dozen lines of hand-rolled code on JVM or
browser. Reusable outside this project.

## What it provides

- `Gef.parse(text)` / `Gef.write(bundle)` — validates (magic, required
  metadata, `ui` segment presence + JSON validity, Base64 payloads) and
  round-trips a `Gef.Bundle`.
- `Gef.uiJson(bundle)` — parses the `ui` segment into the JSON model
  (`g.sw.spi.Json`).
- `Bundle` model — `id`, `name`, `version`, `summary`, `meta` (extra metadata,
  preserved), optional `icon` bytes, `ui` source, `vms` (`engine -> ByteArray`).

## Sections

- [FORMAT.md](FORMAT.md) — the normative container + UI DSL spec (v1).
- [samples/inventory.gef](samples/inventory.gef) — household inventory: metadata + embedded icon + a `page`/`list`/`text`/`button` UI tree with `url:` actions.
- [samples/members.gef](samples/members.gef) — family members: metadata + embedded icon + a `list` of members bound to `GET /api/members/family`, refresh/add actions.

The samples under `samples/` are **build output, not hand-written**: `GenSample`
composes the bundles in Kotlin (UI DSL as data, icons from
`src/main/resources/icons/`) and writes them through `Gef.write`. Running
`./gradlew :swrepo:gef:generateSamples` reproduces them byte-for-byte.

```kotlin
val bundle = Gef.parse(Files.readString(Path.of("inventory.gef")))
bundle.name              // "库存" — feature-list title
bundle.icon              // ByteArray? — feature-list icon
Gef.uiJson(bundle)       // UI DSL tree for the renderer
```

Smoke round-trip + failure cases: `./gradlew :swrepo:gef:smoke`.