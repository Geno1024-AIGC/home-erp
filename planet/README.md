# planet — 行星

The Planet tier: a cloud **service-discovery + relay** server that lets remote
satellites reach the home Star even when the Star sits behind a NAT. Built on
the JDK only, exactly like the Star.

## How it works

- The Planet listens on two ports: **HTTP** (satellite traffic + discovery) and
  **tunnel** (Star dial-in).
- The Star dials out to the tunnel port and announces its **alias**
  (`--planet=<host:port> --alias=home`). Because the Star makes the connection,
  no inbound port at home needs to be open.
- A remote satellite points its `baseUrl` at `http://<planet>:<httpPort>/<alias>`
  — the Planet strips the alias prefix, ships the request over the Star's tunnel
  connection (loaded with the shared relay protocol in `swrepo:relay`), and
  relays the reply back verbatim.
- The Planet **keeps no data**. Every `--refresh` milliseconds it pulls
  `GET /api/topology` from each connected Star over its tunnel and caches it in
  memory, serving `GET /planet/topology` from that cache — so a satellite can
  learn the whole deployment (Star + all Planets) from any single Planet.

## Usage

```bash
./gradlew :planet:distZip
# start the star at home, dialing this planet (two plans, each a --planet flag):
./gradlew :star:run --args="8080 --planet=planet.example.com:9090:9091 --alias=home"
# start the planet (cloud host); --refresh tunes the topology pull interval, default 30s:
build/install/planet/bin/planet --http=9090 --tunnel=9091 --refresh=30
```

## Endpoints

| Route | Purpose |
|---|---|
| `GET /` | HTML discovery page (connected stars) |
| `GET /planet/stars` | JSON list of connected stars (`alias`, `remote`) |
| `GET /planet/topology` | cached topology pulled from the Stars (`503` before the first pull) |
| `GET\|POST /<alias>/…` | relayed to the matching Star (any method, query preserved) |

Unknown or offline stars answer `502`; a Star that does not answer within the
relay timeout answers `504`.

Run the relay and topology self-tests with `./gradlew :swrepo:relay:smoke :swrepo:topology:smoke`.