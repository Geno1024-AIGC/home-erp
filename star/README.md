# star — `g.erp.star`

The Star (恒星): the home server application. Assembles swrepo feature modules onto a single JDK `HttpServer`.

Part of [Home ERP](../README.md).

## How it runs

`Star.main` starts `HttpServer` on port `8080` (first CLI arg overrides), then `assemble`s the feature modules and serves them all through one router context.

Assembly:

- Each module gets a `MountContext` whose `basePath` is `/api/<module name>`; module endpoints registered via `context.handle` land on the router as `METHOD /api/<name>/<path>`.
- Modules are mounted in **dependency order**: transitive `requires` are topologically sorted first, cycles rejected. Unknown required modules fail the build at startup (`Module '…' requires unknown module '…'`).
- Adding/removing a feature module = adding/removing one line in `Star.assemble`'s module list.

The `Router` does exact `method + path` matching and answers unmatched requests with `404 {"error":"not found"}`.

```bash
./gradlew run        # http://localhost:8080
./gradlew :star:distZip   # distribution zip (published as part of every canary release)
```

## Endpoints (sample)

| Endpoint | Description |
|---|---|
| `GET /api/members/family` | list family members |
| `POST /api/members/members` | add a member |
| `GET /api/inventory/items` | list stock items |
| `GET /api/finances/ledger` | list ledger entries |
| `GET /api/chores/tasks` | list chores |