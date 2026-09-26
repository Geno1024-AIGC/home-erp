# HEDS — Home ERP Descriptor Segment format (container v1)

A **self-describing, single-file** bundle that a satellite scans at startup to build
its feature list (name + optional icon), and opens on demand to read a **UI
description** and, later, one of several **VM bytecode** segments to execute a
feature. A `.heapp` file is completely independent of any single host (it does
not depend on `swrepo/db`, on the Star, or on any one satellite platform).

A HEDS bundle is a **sequence of segments**. One shared text framing is used so
that every platform (JVM, browser/JS, …) can parse it with a tiny hand-rolled
reader — no third-party parser. Binary payloads (icons, bytecode) are embedded
as Base64 so the whole bundle stays a plain text file.

## 1. Container framing

```
heds 1.0                    <- line 1: magic + container version

name: 库存                  <- metadata block: `key: value` lines,
id: g.sw.erp.inventory         ends at the first segment frame
version: 0.1
summary: 家居物品与库存
platforms: android,web

==== icon ====             <- segment frame  (0..1 per bundle)
<Base64 of PNG bytes>         body until next frame or EOF

==== ui ====               <- segment frame  (1 required)
<JSON document: the UI DSL>   raw text body

==== vm:hedsvm ====        <- segment frame  (0..n, one per VM engine)
<Base64 bytecode>             engine name = text after `vm:`
```

Rules:

- **Line 1** must be exactly `heds 1.0`. Case-sensitive.
- **Metadata block**: every line before the first segment frame is either blank
  or `key: value`. Keys match `[A-Za-z0-9_][A-Za-z0-9_-]*`; duplicate keys are
  an error. Reserved keys: `id`, `name`, `version`, `summary`. Any other key is
  preserved verbatim as module metadata.
- **Segment frame**: a line matching
  `==== <name> ====` with spaces optional, name = `[A-Za-z0-9][A-Za-z0-9_-]*`,
  plus the `vm:<engine>` form where engine = `[A-Za-z0-9][A-Za-z0-9_-]*`.
  Known names: `icon`, `ui`, `vm:<engine>`. Anything else is a hard error in v1.
- **Segment body**: everything until the next frame line or end of file.
  - `icon` (0..1): body is Base64 (standard alphabet) of the image bytes.
    Whitespace is ignored; the reader may wrap lines.
  - `ui` (exactly 1): body is raw text that must parse as a JSON value.
  - `vm:<engine>` (0..n, engine names unique): body is Base64 of the engine's
    bytecode blob.
- A bundle is valid only when `id`, `name` are set and the `ui` segment exists
  and holds one valid JSON value. An `icon` segment must decode to ≥ 1 byte.

Future container versions keep this framing and only extend the metadata keys
or segment names; a reader that knows an older version must reject a newer
container version (line 1) rather than guess.

## 2. Metadata keys

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | stable unique id of the bundle (e.g. `g.sw.erp.inventory`) |
| `name` | yes | display name shown in the satellite feature list |
| `version` | no | bundle version, free-form (e.g. `0.1`) |
| `summary` | no | one-line description shown in the feature list |
| … | no | any extra keys are preserved and round-trip |

## 3. UI segment (UI DSL v1)

The `ui` value is a JSON object describing a tree of nodes. Each node is an
object with at least `type`. The root type is `page`.

Node vocabulary:

| `type` | Fields | Meaning |
|---|---|---|
| `page` | `title?`, `children` | root of a view |
| `row` / `column` | `children` | layout container |
| `text` | `text?` **or** `bind?` | static text, or a bound value rendered as text |
| `image` | `src` | `icon` = icon segment; `emoji:<hex>`; `asset:<key>` (future) |
| `button` | `label`, `action?` | tappable action |
| `list` | `repeat` (path), `item` (node) | renders `item` once per element of `repeat` |
| `if` | `bind` (path), `then` (array of nodes) | children shown when bound value is truthy |
| `field` | `bind`, `kind?` (`text\|number\|date`), `label?` | editable field (editing forms, v1 reserved) |

**Binding** — a dotted path into the current data context:

- At `page` level the context is the root data object.
- Inside a `list` `item` subtree the context is the current element; bind paths
  are relative to it.

**Actions** —

| Form | Meaning |
|---|---|
| `url:<METHOD> <path>?<query>` | satellite performs the HTTP request itself (no VM required); path is resolved against the satellite's Star base URL |
| `fn:<symbol>` | deferred to a VM; without a matching `vm:<engine>` segment that the platform can run, the satellite renders the action as unavailable |

## 4. VM segments (future, framed now)

Each `vm:<engine>` carries one opaque bytecode blob for that engine. Engines
are per-platform or shared (e.g. a future `hedsvm` hand-rolled bytecode target).
A satellite that knows the engine can execute it; otherwise the segment is
ignored and `fn:` actions above are left out of the rendered UI. The framing is
fixed now so the format never needs to change to add engines.

## 5. Scope & stability

- v1 ships **metadata + icon + ui**. The UI DSL node set is frozen at the
  vocabulary above; the renderer on each platform may implement a subset and
  must skip (with a visible placeholder) nodes it does not know.
- Byte-level canon: `id`/`name`/`version`/`summary` are emitted first (in that
  order), remaining metadata keys sorted; `icon` then `ui` then `vm:` segments.
- The bundle is a plain UTF-8 text file; `String`-based frameworks on both JVM
  and browser handle it unchanged.

Reference implementation + round-trip smoke: `g.sw.heds.Heds` and the
`samples/inventory.heapp` example in this module.