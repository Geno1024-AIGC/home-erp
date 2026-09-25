# Home ERP

面向家庭场景的 ERP 系统。Kotlin 优先、Gradle KTS 构建。全部功能逻辑都基于 JDK + Kotlin 标准库手写——无外部数据库、无 Web 框架、无前端框架。

## 架构（三层）

| 层 | 代号 | 职责 |
|---|---|---|
| 恒星 **Star** | 家庭内的主服务器 | 承载核心服务与数据 |
| 行星 **Planet** | 云服务器 | 服务发现与中继（类似端口转发），让身处家庭外的卫星也能连到恒星 |
| 卫星 **Satellite** | Android 应用 / 浏览器页面 | 在家直连恒星、在外经行星中转访问 |

## 模块（swrepo）

功能模块以 Gradle 子项目的形态放在 `swrepo/` 下——一个 monorepo 软件仓库。恒星通过选择模块来组装部署；模块之间可以互相依赖。

| 模块 | 包名 | 用途 |
|---|---|---|
| `swrepo:spi` | `g.sw.spi` | 模块 SPI：`ErpModule`、`MountContext`、HTTP 辅助 |
| `swrepo:members` | `g.sw.erp.members` | 家庭成员 / 用户 |
| `swrepo:inventory` | `g.sw.erp.inventory` | 家居物品 / 库存 |
| `swrepo:finances` | `g.sw.erp.finances` | 家庭账单 / 预算 |
| `swrepo:chores` | `g.sw.erp.chores` | 家务 / 日程 |
| `swrepo:db` | `g.sw.db` | 精简的追加式日志数据库 |
| `star` | `g.erp.star` | 恒星应用：把各模块组装到单个 JDK `HttpServer` 上 |
| `android` | `g.erp.satellite` | Android 卫星：侧滑抽屉应用，浏览恒星的 HTTP API（零 AndroidX、纯框架 UI） |

## 存储

精简数据库在 `swrepo/db`（`g.sw.db`），**只用 Kotlin 标准库**：

- **追加式预写日志**：每条记录一行——`seq\top\tcollection\tid\tpayload`（`id` 与 `payload` 均 Base64 编码，任意字节/换行都安全）。
- 操作符：`P` = 写入/upsert，`D` = 删除（墓碑），`S` = schema 声明（记录某 collection 持有哪个类）。
- **内存索引**：打开时重放日志重建；每次追加都会先 `force` 落盘再更新内存索引。
- 自动压缩：当死记录数量达到存活记录数量时，就地重写日志。
- 撕裂/损坏的尾部行在重放时截断（对部分写入崩溃安全）。

```kotlin
Db.open(Path.of("data")).use { db ->
    val family = db.collection("family")
    family.put("alice", "Alice".encodeToByteArray())
    family.get("alice")
    family.delete("bob")
}
```

**Data class 直读直写**：任意普通 data class 原样进出——**无注解、无接口、无 `Serializable`**。`g.sw.db.Codec` 通过反射（仅标准库）遍历类的字段，把每个值编码为紧凑的带标签二进制格式：

- 基本类型与 `String` 走**原生裸编码**（性能：热点路径无装箱反射）。
- **特判常见类型**——无反射、无需库：
  - 日期/时间：`LocalDate`、`LocalTime`、`LocalDateTime`、`Instant`、`ZonedDateTime`、`OffsetDateTime`、`Period`、`Duration`、`Year`、`YearMonth`、`MonthDay`、`ZoneId`、历史遗留 `java.util.Date`；
  - 数值/杂项：`BigDecimal`、`BigInteger`、`UUID`、枚举；
  - 集合：`List`、`Set`、`Map`（递归、任意嵌套）。

```kotlin
data class Person(val name: String, val age: Int)   // 普通 data class，什么都不用加

Db.open(Path.of("data")).use { db ->
    val people = db.collection("people")                    // 无类型：每次调用传类
    people.put("carol", Person("Carol", 30))
    val carol: Person? = people.get("carol", Person::class.java)

    val typed = db.collection("people", Person::class.java) // 有类型：类在 collection 上记录
    typed.put("carol", Person("Carol", 30))
    val again: Person? = typed.get("carol")                 // 无需再传类字面量
}
```

collection 的 schema 在首次带类型使用后作为 `S` 记录写入，并在重放与压缩后仍保留。若用与已存数据**不同**的类声明同名 collection，会立即抛错——漂移在打开时就被抓住，而不是读到一半才失败。纯字节请用无类型的 `collection(name)`。

**二进制附件不进数据库**：记录只保存相对附件键；字节内容放在数据目录下的 `files/`，且只能经由 `Db` 移入/取出，因此**一个数据目录就是完整可迁移单元**——迁移或备份只需拷贝整个目录。

```kotlin
Db.open(Path.of("data")).use { db ->
    val photo = db.adoptAttachment(Path.of("/tmp/pan.jpg"), "inventory/pan-2026.jpg")
    val item = db.collection("inventory")
    item.put("pan", Item("frying pan", "inventory/pan-2026.jpg"))
    val key = item.get("pan", Item::class.java).photo
    Files.readAllBytes(db.attachment(key))  // 解析到数据目录内部
}
```

注意：
- 字段元数据（反射结果）按类缓存；`getObject`/`get` 通过每个 collection 的锁在多个线程间共享。
- payload 携带类名与字段名，所以改过 data class 结构后请重新 `put` 归一化——旧 blob 无法匹配新构造器。
- 附件键是经过校验的相对路径（`a-z0-9_-`、以 `/` 分隔）；`..`、绝对路径及任何逃逸出数据目录的都拒绝。删除记录不会删除其附件——孤儿文件需手动清理，或留给将来的清理器。

## 构建与运行

需要 **JDK 25**（经 `gradle.properties` 从 `~/.jdks` 获取）；Gradle wrapper 是 **9.7.1**。

```bash
./gradlew build     # 编译全部；同时把各模块的 pack 计数自增
./gradlew run       # 启动恒星，监听 http://localhost:8080
./gradlew :swrepo:db:smoke   # 运行数据库自测
./gradlew :android:assembleDebug  # 构建 Android 卫星 APK
```

恒星提供的示例接口：

| 接口 | 说明 |
|---|---|
| `GET /api/members/family` | 列出家庭成员 |
| `POST /api/members/members` | 新增成员 |
| `GET /api/inventory/items` | 列出库存物品 |
| `GET /api/finances/ledger` | 列出账单明细 |
| `GET /api/chores/tasks` | 列出家务 |

## 卫星发布与更新

CI（`.github/workflows/canary.yml`）在每次 push 到 `master` 时全量构建（也支持 `workflow_dispatch`），并发布一个 GitHub **pre-release**（Canary）,附带恒星 dist zip 与 debug/release APK。pre-release 只保留一个——每次运行都会先删掉旧的。

- 两个 APK 都用仓库内置的共享 debug keystore `android/signing/debug.jks`（标准 `androiddebugkey`、密码 `android`）签名，因此每次 CI 运行签名一致，Canary 更新可直接覆盖安装，无需任何 GitHub secret。将来正式版可换成 secret 签名密钥而不改动流水线。
- App 内 **设置 → 更新**：选 更新渠道（**Canary** = pre-release，**正式版** = 正式 release——目前还没有发布过）与 更新源（GitHub 或镜像前缀 ghproxy / gh-proxy / ghfast.top），再 检查更新 与 下载并安装。
- 更新元数据统一读 `api.github.com`，APK 经所选镜像前缀下载；缓存的 APK 用框架自带的 `PackageInstaller` 会话 API 安装，结果经 manifest 中的 `BroadcastReceiver` 以系统通知呈现（零 AndroidX）。

## 版本号

`0.1.<a>.<b>.<sha1>` —— 借鉴 opencode-inspire 的方案：`<a>` 是环境决定的单调递增序列（CI 上用 GitHub Actions 运行号，否则用仓库提交数），`<b>` 是该模块 git 跟踪的打包计数 `count.pack`（`jar`/`assemble`/`build`/`dist*` 会自增；Android 为 `assembleDebug`/`assembleRelease`/`bundle*`）。细节与全部约定见 [`AGENTS.md`](AGENTS.md)。