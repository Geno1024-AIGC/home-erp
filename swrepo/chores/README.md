# swrepo/chores — `g.sw.erp.chores`

家务/日程: chore assignment, schedules, reminders. Currently a mock module serving canned JSON.

Part of [Home ERP](../../README.md) (swrepo).

Mounts under `/api/chores`:

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/chores/tasks` | list chores |

Depends on [`members`](../members/README.md).

```bash
curl http://localhost:8080/api/chores/tasks
```