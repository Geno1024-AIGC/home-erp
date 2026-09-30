# swrepo/gef — `g.sw.gef`

The **GEF** container formats (Geno's Executable Format, file suffix `.gef`)
and their reference implementations. Two containers share the suffix and are
sniffed apart by their leading bytes:

- **v1 text** — `Gef.Bundle`, the original self-describing **UI DSL** bundle:
  plain UTF-8 text with segment framing (metadata + `==== segment ====` frames,
  binary Base64), rendered natively on Android with the framework renderer.
- **v0.1 html zip** — `HtmlGef.Package`, a **zip** holding `manifest.json` plus
  web assets (entry HTML page, CSS/JS, optional icon), rendered by a WebView
  that talks to the Star through an `Erp` JavaScript bridge. Reusable outside
  this project.

## What it provides

- `Gef.parse(text)` / `Gef.write(bundle)` — the v1 text container: validates
  (magic, required metadata, `ui` segment presence + JSON validity, Base64
  payloads) and round-trips a `Gef.Bundle`.
- `Gef.uiJson(bundle)` — parses the `ui` segment into the JSON model
  (`g.sw.spi.Json`).
- `Gef.Bundle` — `id`, `name`, `version`, `summary`, `meta` (extra metadata,
  preserved), optional `icon` bytes, `ui` source, `vms` (`engine -> ByteArray`).
- `HtmlGef.pack(...)` / `HtmlGef.unpack(bytes)` / `HtmlGef.isZip(bytes)` — the
  v0.1 zip container. `pack` writes `manifest.json` (`{id, name, version?,
  summary?, type:"html", entry?, icon?}`) plus the asset files; `unpack`
  validates: zip magic, absolute-path/traversal-free entry names, mandatory
  entry file, referenced icon file, `id` as a single path segment, 4 MB
  uncompressed / 256 entries caps.

## Sections

- [FORMAT.md](FORMAT.md) — the normative container + UI DSL spec (v1).
- [samples/inventory.gef](samples/inventory.gef) — household inventory: metadata + embedded icon + a `page`/`list`/`text`/`button` UI tree with `url:` actions.
- [samples/members.gef](samples/members.gef) — family members: metadata + embedded icon + a `list` of members bound to `GET /api/members/family`, refresh/add actions.
- [samples/html-demo.gef](samples/html-demo.gef) — the v0.1 zip demo: a self-contained `index.html` (inventory table + member list) that calls the Star through the `Erp` bridge.

The samples under `samples/` are **build output, not hand-written**: `GenSample`
composes the v1 bundles in Kotlin (UI DSL as data, icons from
`src/main/resources/icons/`) and the zip demo with `HtmlGef.pack`. Running
`./gradlew :swrepo:gef:generateSamples` reproduces them byte-for-byte.

```kotlin
val bundle = Gef.parse(Files.readString(Path.of("inventory.gef")))
bundle.name              // "库存" — feature-list title
bundle.icon              // ByteArray? — feature-list icon
Gef.uiJson(bundle)       // UI DSL tree for the renderer

val pkg = HtmlGef.unpack(Files.readAllBytes(Path.of("html-demo.gef")))
pkg.entry                // "index.html" — WebView loadUrl target relative to the unzipped dir
HtmlGef.isZip(bytes)     // sniff any file: zip → html renderer, else v1 text renderer
```

Smoke round-trip + failure cases: `./gradlew :swrepo:gef:smoke`.