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
- `Identity` — who is calling, so a feature module can demand a signed-in user
  (or a role) without depending on the auth module's internals:
  - `resolve(token)` — bearer token → username, or null when invalid/expired;
  - `roleOf(username)` — username → role name, or null when the account is gone.
- `HttpExchange.bearerToken()` — the `Authorization: Bearer <token>` credential.
- `requireAuth(identity, vararg roles, handler)` — wraps a handler so it only
  runs for a signed-in user, optionally restricted to one of `roles` (empty means
  "any signed-in user"). Answers `401` with no session and `403` on a role
  mismatch; on success the handler gets `(exchange, username)`.

```kotlin
class MyModule(private val auth: AuthModule) : ErpModule {
    override val name: String = "my"
    override val requires: List<String> = listOf("auth")

    override fun mount(context: MountContext) {
        context.handle("GET", "/things", requireAuth(auth) { ex, user -> listThings(ex) })
        context.handle("POST", "/things", requireAuth(auth, "admin") { ex, user -> addThing(ex) })
    }
}
```

`g.sw.erp.auth` is the only `Identity` implementation today; the Star wires it
into every module that declares `requires = listOf("auth")`.

Everything is on the JDK's `com.sun.net.httpserver` — no web framework, no third-party dependency.