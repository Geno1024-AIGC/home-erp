# swrepo/members — `g.sw.erp.members`

成员与用户管理: family members, accounts, auth, permissions. Currently a mock module serving canned JSON.

Part of [Home ERP](../../README.md) (swrepo).

Mounts under `/api/members`:

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/members/family` | list family members |
| `POST` | `/api/members/members` | add a member (body = name) |

No dependencies (`requires` empty).

```bash
curl http://localhost:8080/api/members/family
```