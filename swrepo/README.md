# swrepo — software repository

The swrepo is a **monorepo**: every feature module lives as a Gradle sub-project here, managed with the rest of Home ERP. Modules may depend on each other; the Star assembles a deployment by selecting modules. Some entries are designed to be reusable outside this project.

Part of [Home ERP](../README.md).

## Modules

| Module | Package | Purpose | README |
|---|---|---|---|
| `swrepo:spi` | `g.sw.spi` | module SPI: `ErpModule`, `MountContext`, HTTP helpers (reusable) | [spi](spi/README.md) |
| `swrepo:auth` | `g.sw.erp.auth` | accounts & authentication: password (PBKDF2) + SSH-key (ed25519) login | [auth](auth/README.md) |
| `swrepo:members` | `g.sw.erp.members` | family members / users | [members](members/README.md) |
| `swrepo:inventory` | `g.sw.erp.inventory` | household items & stock | [inventory](inventory/README.md) |
| `swrepo:finances` | `g.sw.erp.finances` | home bills & budget | [finances](finances/README.md) |
| `swrepo:chores` | `g.sw.erp.chores` | housework & schedule | [chores](chores/README.md) |
| `swrepo:db` | `g.sw.db` | append-only-log database (reusable) | [db](db/README.md) |

The host-side modules live outside swrepo: [`star`](../star/README.md) and [`satellite/android`](../satellite/android/README.md).