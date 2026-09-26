# Home ERP

面向家庭场景的 ERP 系统。Kotlin 优先、Gradle KTS 构建。全部功能逻辑都基于 JDK + Kotlin 标准库手写——无外部数据库、无 Web 框架、无前端框架。

## 架构（三层）

| 层 | 代号 | 职责 |
|---|---|---|
| 恒星 **Star** | 家庭内的主服务器 | 承载核心服务与数据 |
| 行星 **Planet** | 云服务器 | 服务发现与中继（类似端口转发），让身处家庭外的卫星也能连到恒星 |
| 卫星 **Satellite** | Android 应用 / 浏览器页面 | 在家直连恒星、在外经行星中转访问 |

## 模块

每个模块在自己的目录里各有一份 README。

| 模块 | 包名 | README |
|---|---|---|
| `swrepo:spi` | `g.sw.spi` | [spi](swrepo/spi/README.md) — 模块 SPI：`ErpModule`、`MountContext`、HTTP 辅助 |
| `swrepo:auth` | `g.sw.erp.auth` | [auth](swrepo/auth/README.md) — 账号与认证：密码（PBKDF2）+ SSH 密钥（ed25519）登录 |
| `swrepo:members` | `g.sw.erp.members` | [members](swrepo/members/README.md) — 家庭成员 / 用户 |
| `swrepo:inventory` | `g.sw.erp.inventory` | [inventory](swrepo/inventory/README.md) — 家居物品 / 库存 |
| `swrepo:finances` | `g.sw.erp.finances` | [finances](swrepo/finances/README.md) — 家庭账单 / 预算 |
| `swrepo:chores` | `g.sw.erp.chores` | [chores](swrepo/chores/README.md) — 家务 / 日程 |
| `swrepo:db` | `g.sw.db` | [db](swrepo/db/README.md) — 精简的追加式日志数据库 |
| `swrepo:gef` | `g.sw.gef` | [gef](swrepo/gef/README.md) — 卫星功能的自描述 bundle 格式（元数据/图标/UI/VM 段） |
| `star` | `g.erp.star` | [star](star/README.md) — 恒星应用：把各模块组装到单个 JDK `HttpServer` 上 |
| `satellite:android` | `g.erp.satellite` | [satellite/android](satellite/android/README.md) — Android 卫星：侧滑抽屉应用，浏览恒星的 HTTP API（零 AndroidX、纯框架 UI） |

软件仓库布局见 [swrepo/README.md](swrepo/README.md)。

## 构建与运行

需要 **JDK 25**（经 `gradle.properties` 从 `~/.jdks` 获取）；Gradle wrapper 是 **9.7.1**。

```bash
./gradlew build     # 编译全部；同时把各模块的 pack 计数自增
./gradlew run       # 启动恒星，监听 http://localhost:8080
./gradlew :swrepo:db:smoke   # 运行数据库自测
./gradlew :swrepo:gef:smoke  # 运行 GEF bundle 格式往返自测
./gradlew :satellite:android:assembleDebug  # 构建 Android 卫星 APK
```

## 卫星发布与更新

CI（`.github/workflows/canary.yml`）在每次 push 到 `master` 时全量构建（也支持 `workflow_dispatch`），并发布一个 GitHub **pre-release**（Canary）,附带恒星 dist zip 与 debug/release APK。pre-release 只保留一个——每次运行都会先删掉旧的。

- 两个 APK 都用仓库内置的共享 debug keystore `satellite/android/signing/debug.jks`（标准 `androiddebugkey`、密码 `android`）签名，因此每次 CI 运行签名一致，Canary 更新可直接覆盖安装，无需任何 GitHub secret。将来正式版可换成 secret 签名密钥而不改动流水线。
- App 内 **设置 → 更新**：选 更新渠道（**Canary** = pre-release，**正式版** = 正式 release——目前还没有发布过）与 更新源（GitHub 或镜像前缀 ghproxy / gh-proxy / ghfast.top），再 检查更新 与 下载并安装。
- 更新元数据统一读 `api.github.com`，APK 经所选镜像前缀下载；缓存的 APK 用框架自带的 `PackageInstaller` 会话 API 安装，结果经 manifest 中的 `BroadcastReceiver` 以系统通知呈现（零 AndroidX）。详见 [satellite/android/README.md](satellite/android/README.md)。

## 版本号

`0.1.<env>.<pack>.<sha1>` —— `<env>` 是环境决定的单调递增序列（CI 上用 GitHub Actions 运行号，否则用仓库提交数），`<pack>` 是该模块 git 跟踪的打包计数 `count.pack`（`jar`/`assemble`/`build`/`dist*` 会自增；Android 为 `assembleDebug`/`assembleRelease`/`bundle*`）。细节与全部约定见 [`AGENTS.md`](AGENTS.md)。