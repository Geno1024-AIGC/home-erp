# relay — `g.sw.relay`

Reusable tunnel relay used by the 行星 (Planet) cloud server and the 恒星 (Star)
home server. A **Star dials out** to the Planet over one persistent TCP
connection; the Planet multiplexes inbound satellite HTTP requests onto that
tunnel by correlation id and relays the Star's responses back.

Port of the framing to another host (browser, Android) is possible — the
protocol is plain TCP + length-prefixed JSON frames.

## Frame format (v1)

```
[4-byte BE length][1-byte type][UTF-8 JSON payload]
```

| Type | Payload | Direction |
|---|---|---|
| HELLO | `{"alias":"<star>"}` | Star → Planet |
| REQUEST | `{"id":N,"method","target","headers":{},"body":"<b64>"}` | Planet → Star |
| RESPONSE | `{"id":N,"status":S,"headers":{},"body":"<b64>"}` | Star → Planet |
| PING / PONG | `{}` | either way |

`target` is an absolute HTTP path (query included) resolved by the Star against
its own HTTP server; bodies are Base64 so binary payloads are safe.

## Components

- `RelayConnection` — frame writer/reader + typed senders (Hello/Request/Response/Ping/Pong). Reads must be single-threaded; writes are internally serialized.
- `RelayPool` — Planet side: accepts Star connections, registers aliases, answers `forward(alias, …)`.
- `RelayClient` — Star side: dials the Planet, HELLOs, and answers each REQUEST by calling its own local HTTP server.

Self-test: `./gradlew :swrepo:relay:test` runs an in-process Planet↔Star relay
round-trip over loopback sockets.