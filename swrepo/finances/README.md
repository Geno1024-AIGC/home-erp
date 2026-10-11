# swrepo/finances — `g.sw.erp.finances`

家庭账单/预算: income/expense ledger, budgets, bill splitting. Currently a mock module serving canned JSON — but **every endpoint already demands a signed-in user**, so the canned data is not world-readable.

Part of [Home ERP](../../README.md) (swrepo).

Mounts under `/api/finances`:

| Method | Path | Access | Description |
|---|---|---|---|
| `GET` | `/api/finances/ledger` | signed in | list ledger entries |

Depends on [`auth`](../auth/README.md) and [`members`](../members/README.md).

```bash
curl http://localhost:8080/api/finances/ledger -H "Authorization: Bearer $TOKEN"
```
