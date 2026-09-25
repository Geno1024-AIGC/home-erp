# swrepo/finances — `g.sw.erp.finances`

家庭账单/预算: income/expense ledger, budgets, bill splitting. Currently a mock module serving canned JSON.

Part of [Home ERP](../../README.md) (swrepo).

Mounts under `/api/finances`:

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/finances/ledger` | list ledger entries |

Depends on [`members`](../members/README.md).

```bash
curl http://localhost:8080/api/finances/ledger
```