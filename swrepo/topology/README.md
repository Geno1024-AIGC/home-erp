# topology — `g.sw.erp.topology`

Discovery address book for the 3-tier deployment: who is the Star, which
Planets exist, and which are currently reachable. The **Star is the authority**
and persists the book in its data folder; **Planets hold no data** — they keep
an in-memory cache of whatever they pull from their connected Stars and answer
satellites from it; **satellites store a copy** of the topology to boot from.

## Roles

| Side | Behaviour |
|---|---|
| Star | `TopologyModule(db, …)` persists Planet registrations (from `--planet=` or a runtime registration), tracks `reachable` per Planet, serves `GET /api/topology`. |
| Planet | pulls `GET /api/topology` from each connected Star over the relay tunnel, keeps a `TopologyCache` in memory, serves `GET /planet/topology` (503 until it first succeeds). |
| Satellite | caches the topology it last received; boot prefers the Star, falls back to Planets. |

## Topology shape

```json
{
  "star":   { "kind": "star", "name": "home", "httpHost": "localhost", "httpPort": 8080, "reachable": true },
  "planets":[ { "kind": "planet", "name": "a:9090", "httpHost": "a", "httpPort": 9090,
                "tunnelHost": "a", "tunnelPort": 9091, "reachable": true, "lastSeen": 0 } ]
}
```

## HTTP (mounted by the Star under `/api/topology`)

- `GET /` — full address book (star + planets).
- `POST /planets` — register/refresh a planet (`httpHost`, `httpPort`,
  `tunnelHost`, `tunnelPort`); kept as a future path for self-registering
  Planets, today the list is fed from the Star's own `--planet=` flags.

Self-test: `./gradlew :swrepo:topology:smoke`.