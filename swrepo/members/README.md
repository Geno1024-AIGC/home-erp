# swrepo/members — `g.sw.erp.members`

家庭成员名册: who lives in the household, and which login account (if any) belongs
to them.

Part of [Home ERP](../../README.md) (swrepo).

Mounts under `/api/members`. Requires [`auth`](../auth/README.md): a member is a
person, an account is a login, and **the account's role decides permissions** —
this module never invents roles of its own.

| Method | Path | Access | Description |
|---|---|---|---|
| `GET` | `/api/members/family` | signed in | list members with their linked account and role |
| `POST` | `/api/members/members` | admin | add a member: `{name, username?}` |
| `DELETE` | `/api/members/members?id=` | admin | remove a member |

`username` is optional — a member without one (a young child, a guest) simply
has no login. When it is set it must name an existing account, and each account
may link to at most one member.

```bash
curl http://localhost:8080/api/members/family -H "Authorization: Bearer $TOKEN"
```
