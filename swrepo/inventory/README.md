# swrepo/inventory — `g.sw.erp.inventory`

家居物品/库存: household items, storage locations, quantities, expiry/expiration reminders, shopping lists. Currently a mock module serving canned JSON.

Part of [Home ERP](../../README.md) (swrepo).

Mounts under `/api/inventory`:

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/inventory/items` | list stock items |

Depends on [`members`](../members/README.md).

```bash
curl http://localhost:8080/api/inventory/items
```