# swrepo/inventory — `g.sw.erp.inventory`

家居物品/库存: household items, storage locations, quantities, expiry/expiration reminders, shopping lists. Currently a mock module serving canned JSON — but **every endpoint already demands a signed-in user**, so the canned data is not world-readable.

Part of [Home ERP](../../README.md) (swrepo).

Mounts under `/api/inventory`:

| Method | Path | Access | Description |
|---|---|---|---|
| `GET` | `/api/inventory/items` | signed in | list stock items |

Depends on [`auth`](../auth/README.md) and [`members`](../members/README.md).

```bash
curl http://localhost:8080/api/inventory/items -H "Authorization: Bearer $TOKEN"
```
