# swrepo/auth — `g.sw.erp.auth`

Accounts & authentication for the Home ERP Star — **Kotlin stdlib only** (PBKDF2, Ed25519, sessions all built on JDK primitives, no third-party).

Part of [Home ERP](../../README.md) (swrepo).

## Endpoints (mounted at `/api/auth`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` | create a user: `{username, password, displayName?}` |
| POST | `/api/auth/login` | sign in, `method` decides the flow (below) |
| POST | `/api/auth/challenge` | step 1 of SSH login: issue a one-time challenge |
| POST | `/api/auth/logout` | revoke the current bearer token |
| GET | `/api/auth/me` | profile of the signed-in user |
| GET/POST | `/api/auth/keys` | list / add an SSH public key |
| DELETE | `/api/auth/keys?id=` | remove an SSH key |

All endpoints except `/register`, `/challenge`, and `/login` require `Authorization: Bearer <token>`.

## Login flows

Login is explicit — the request carries a `method` field; the server never guesses.

### Password

```json
POST /api/auth/login
{ "method": "password", "username": "alice", "password": "secret123" }
→ 200 { "token": "…", "user": { "username", "displayName", "keys" } }
```

Passwords never leave the machine in recoverable form: PBKDF2WithHmacSHA256, 210k iterations, 256-bit key, random 16-byte salt, constant-time compare.

### SSH key (ed25519 challenge/response)

1. `POST /api/auth/challenge` `{ "username": "alice" }` → `{ "challenge": "…", "expiresIn": 300 }`. Each challenge is **one-time** (consumed on use, 5-minute TTL) — a replayed login with the same challenge fails.
2. Sign the challenge string (UTF-8) with the private key, then:

```json
POST /api/auth/login
{ "method": "ssh", "username": "alice", "challenge": "…",
  "fingerprint": "SHA256:…", "signature": "<base64>" }
→ 200 { "token": "…", … }
```

Only **`ssh-ed25519`** keys are accepted. Registration accepts the standard `ssh-ed25519 <base64> [comment]` line (as in `~/.ssh/id_ed25519.pub`); fingerprints match `ssh-keygen -lf` (`SHA256:<base64-of-SHA256>`). Verification uses the JDK's Ed25519 (`KeyFactory`/`Signature`) over a hand-built DER `SubjectPublicKeyInfo` — no OpenSSH/OpenSSL dependency server-side.

## Storage

Users persist in the `Db` collection `users` (keyed by username) with `passwordHash`/`salt` as hex strings. Sessions and challenges are in-memory only for now (7-day token TTL) — a future release may persist sessions.

## Test

```bash
./gradlew :swrepo:auth:build   # no dedicated smoke yet; exercise via the Star HTTP API
```