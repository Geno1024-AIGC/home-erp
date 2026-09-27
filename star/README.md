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

## Reaching the Star from outside (Planet relay)

The Star can dial out to one or more [Planet](../planet/README.md)s so remote
satellites can reach it behind a NAT. Each `--planet` takes `host:httpPort[:tunnelPort]`
(tunnel port defaults to `httpPort + 1`):

```bash
./gradlew :star:run --args="8080 --planet=planet.example.com:9090:9091 --alias=home"
```

Once connected, the Star appears on each Planet under its `alias`
(`http://<planet>:9090/home/…`). The `--alias` defaults to `home`; with no
`--planet` the Star is reachable only on the local network.

## Topology

The Star is the **authority** for the deployment address book. Every configured
`--planet` is persisted in the local [db](../swrepo/db/README.md) (collection
`topology`) along with a self entry (`--httpHost` defaults to `localhost`), and a
background supervisor keeps one relay tunnel to each registered Planet, marking it
`reachable` as tunnels come and go.

- `GET /api/topology` — `{"star": {…}, "planets": [{…}, …]}`; this single endpoint
  is what Planets poll and Satellites probe to learn the whole deployment.
- De-registering: remove the `--planet=` flag and the supervisor stops dialing that
  planet; the address remains in the log but shows `reachable:false` until a new
  flag re-adopts it. A running Planet can register itself at runtime via
  `POST /api/topology/planets` (body `{httpHost, httpPort, tunnelHost, tunnelPort}`).
- Planets are free to send you their cached topology back; the Star ignores it and
  only accepts registrations via its own `--planet` flags.

## Endpoints (sample)

| Endpoint | Description |
|---|---|
| `GET /api/members/family` | list family members |
| `POST /api/members/members` | add a member |
| `GET /api/inventory/items` | list stock items |
| `GET /api/finances/ledger` | list ledger entries |
| `GET /api/chores/tasks` | list chores |