# swrepo/chores — `g.sw.erp.chores`

家务/日程: chore assignment, schedules, reminders. Currently a mock module serving canned JSON — but **every endpoint already demands a signed-in user**, so the canned data is not world-readable.

Part of [Home ERP](../../README.md) (swrepo).

Mounts under `/api/chores`:

| Method | Path | Access | Description |
|---|---|---|---|
| `GET` | `/api/chores/tasks` | signed in | list chores |

Depends on [`auth`](../auth/README.md) and [`members`](../members/README.md).

```bash
curl http://localhost:8080/api/chores/tasks -H "Authorization: Bearer $TOKEN"
```
