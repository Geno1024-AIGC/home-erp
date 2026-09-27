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

## Usage

```bash
./gradlew :planet:distZip
# start the star at home, dialing this planet:
./gradlew :star:run --args="8080 --planet=planet.example.com:9091 --alias=home"
# start the planet (cloud host):
build/install/planet/bin/planet --http=9090 --tunnel=9091
```

## Endpoints

| Route | Purpose |
|---|---|
| `GET /` | HTML discovery page (connected stars) |
| `GET /planet/stars` | JSON list of connected stars (`alias`, `remote`) |
| `GET\|POST /<alias>/…` | relayed to the matching Star (any method, query preserved) |

Unknown or offline stars answer `502`; a Star that does not answer within the
relay timeout answers `504`.

Run the relay self-test with `./gradlew :swrepo:relay:smoke`.