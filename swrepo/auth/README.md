# swrepo/auth — `g.sw.erp.auth`

Accounts, roles & authentication for the Home ERP Star — **Kotlin stdlib only**
(PBKDF2, Ed25519, sessions all built on JDK primitives, no third-party).

Part of [Home ERP](../../README.md) (swrepo).

## Endpoints (mounted at `/api/auth`)

| Method | Path | Access | Description |
|---|---|---|---|
| POST | `/api/auth/register` | public | create an account: `{username, password, displayName?, keys?}` |
| POST | `/api/auth/challenge` | public | step 1 of SSH login: issue a one-time challenge |
| POST | `/api/auth/login` | public | sign in; `method` decides the flow (below) |
| POST | `/api/auth/logout` | signed in | revoke the current bearer token |
| GET | `/api/auth/me` | signed in | profile of the signed-in user |
| GET/POST | `/api/auth/keys` | signed in | list / add an SSH public key |
| DELETE | `/api/auth/keys?id=` | signed in | remove an SSH key |
| GET | `/api/auth/users` | admin | list accounts with roles |
| POST | `/api/auth/users` | admin | create an account: `{username, password, displayName?, role?}` |
| POST | `/api/auth/users/role` | admin | change a role: `{username, role}` |
| DELETE | `/api/auth/users?username=` | admin | delete an account and its sessions |

Anything except `/register`, `/challenge` and `/login` needs
`Authorization: Bearer <token>`; the admin endpoints additionally need the
`admin` role and answer `403` otherwise.

## Roles

Two roles, stored on the account:

- **`admin`** — manages accounts and the family roster.
- **`member`** — everyone else.

The very first account to register becomes the bootstrap admin, so a fresh Star
needs no seeded credentials. The last admin can neither be demoted nor deleted
(`409`), and deleting an account revokes its live sessions. Feature modules
never invent roles of their own — they ask this module through
[`g.sw.spi.Identity`](../spi/README.md).

## Login flows

Login is explicit — the request carries a `method` field; the server never guesses.

### Password

```json
POST /api/auth/login
{ "method": "password", "username": "alice", "password": "secret123" }
→ 200 { "token": "…", "user": { "username", "displayName", "role", "keys" } }
```

Passwords never leave the machine in recoverable form: PBKDF2WithHmacSHA256, 210k iterations, 256-bit key, random 16-byte salt, constant-time compare. A wrong password and an unknown username both answer `401` with the same message, so the API does not reveal which accounts exist.

### SSH key (ed25519 challenge/response)

1. `POST /api/auth/challenge` `{ "username": "alice" }` → `{ "challenge": "…", "expiresIn": 300 }`. Each challenge is **one-time** (consumed on use, 5-minute TTL) — a replayed login with the same challenge fails.
2. Sign the challenge string (UTF-8) with the private key, then:

```json
POST /api/auth/login
{ "method": "ssh", "username": "alice", "challenge": "…",
  "fingerprint": "SHA256:…", "signature": "<base64>" }
→ 200 { "token": "…", … }
```

Only **`ssh-ed25519`** keys are accepted. Registration accepts the standard `ssh-ed25519 <base64> [comment]` line (as in `~/.ssh/id_ed25519.pub`), either up front in the register body's `keys` array or later via `POST /api/auth/keys`; fingerprints match `ssh-keygen -lf` (`SHA256:<base64-of-SHA256>`). Verification uses the JDK's Ed25519 (`KeyFactory`/`Signature`) over a hand-built DER `SubjectPublicKeyInfo` — no OpenSSH/OpenSSL dependency server-side.

## Storage

Accounts persist in the `Db` collection `users` (keyed by username) with
`passwordHash`/`salt` as hex strings. Bearer tokens persist in the `sessions`
collection, so **restarting the Star does not sign anyone out**; expired tokens
are swept on each login. One-time SSH challenges stay in memory — they are
worthless after a restart anyway.

## Reusable outside Home ERP?

The password/SSH primitives (`Password`, `SshKeys`, `Hex`) are stdlib-only and
could be lifted out; the module itself is wired to `g.sw.db` and this project's
role vocabulary.
