# swrepo/spi — `g.sw.spi`

The module SPI (`ErpModule`, `MountContext`, HTTP helpers) shared by the swrepo feature modules and the Star host. Reusable outside this project.

Part of [Home ERP](../../README.md) (swrepo).

## What it provides

- `Handler` — typealias for `(HttpExchange) -> Unit`.
- `MountContext` — how a module exposes HTTP endpoints on the Star:
  - `basePath` — `/api/<module name>`;
  - `handle(method, path, handler)` — registers a route on the Star's router.
- `ErpModule` — the runtime contract for a feature module:
  - `name` — module id, i.e. its URL namespace;
  - `requires` — names of modules this one depends on (`emptyList()` by default); validated/topologically ordered by the Star;
  - `mount(context)` — registers the module's endpoints (`no-op` by default).
- `HttpExchange.respond(code, body)` — writes a `application/json; charset=utf-8` response.

```kotlin
class MyModule : ErpModule {
    override val name: String = "my"
    override val requires: List<String> = listOf("members")

    override fun mount(context: MountContext) {
        context.handle("GET", "/things", ::listThings)
    }
}
```

Everything is on the JDK's `com.sun.net.httpserver` — no web framework, no third-party dependency.