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

## Format & docs

- [FORMAT.md](FORMAT.md) — the normative container + UI DSL spec (v1).

```kotlin
// v1 text: parse a bundle fetched from anywhere (file, HTTP, asset, ...)
val bundle = Gef.parse(text)
bundle.name              // feature-list title
bundle.icon              // ByteArray? — feature-list icon
Gef.uiJson(bundle)       // UI DSL tree for the renderer

// v0.1 zip: pack an HTML app, then load the unpacked dir in a WebView
val packed = HtmlGef.pack(
    id = "g.erp.satellite.demo", name = "HTML 演示", version = "0.1",
    entry = "index.html", icon = "icon",
    files = mapOf("index.html" to html, "app.js" to js, "icon" to png),
)
val pkg = HtmlGef.unpack(packed)
pkg.entry                // "index.html" — WebView loadUrl target relative to the unpacked dir
HtmlGef.isZip(bytes)     // sniff any file: zip → html renderer, else v1 text renderer
```

Self-tests (inline fixtures + failure cases, no checked-in binaries):
`./gradlew :swrepo:gef:smoke`.