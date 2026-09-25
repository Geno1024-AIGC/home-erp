# android — `g.erp.satellite`

The 卫星 (Satellite) Android app: talks to the Star's HTTP API. Zero AndroidX, zero third-party — plain framework UI (rough side-drawer + screens built in code).

Part of [Home ERP](../README.md).

## Layout

```
src/main/kotlin/g/erp/satellite/
├── MainActivity.kt        # activity + settings screen + drawer gesture + APK install
├── StarClient.kt          # tiny HttpURLConnection GET helper (default http://10.0.2.2:8080)
├── json/Json.kt           # hand-rolled JSON parse/format
└── update/
    ├── Updater.kt         # canary/stable release check, channel & source select, download
    └── InstallReceiver.kt # manifest BroadcastReceiver reporting session-install results
```

`applicationId` `g.erp.satellite`, label **New Home**, `minSdk 24` / `targetSdk 36` / `compileSdk 36`, JDK 17 bytecode.

## App behaviour

- On launch, fetches a few sample endpoints from the Star and renders them in a rough side-drawer UI (drawer opens by an edge swipe from the left, scrim follows the finger).
- 设置 → 更新: pick 更新渠道 (**Canary** = GitHub pre-releases, **正式版** = regular releases — none published yet) and 更新源 (GitHub or mirror prefixes ghproxy / gh-proxy / ghfast.top), then 检查更新 and 下载并安装.
  - Release metadata is always read from `api.github.com`; the APK download goes through the selected source prefix.
- APK install uses the framework **`PackageInstaller` session API**: the file is streamed into a session and committed; the result arrives at `InstallReceiver` (manifest-registered) and is surfaced via a system Notification. No `FileProvider`, no `ACTION_VIEW`.
  - API 33+: asks for `POST_NOTIFICATIONS` first. API 26+: routes to the "install unknown apps" setting when needed.
- Downloads keep the single-thread executor; a stale Star response can't overwrite the settings screen (guarded render).

## Signing

Both `debug` and `release` build types sign with the repo-tracked `signing/debug.jks` (standard debug keystore, `androiddebugkey` / `android`), so every CI build has the **same signature** and a canary update installs over the previous one. `versionCode` = the environment sequence (`versionSeq`), strictly monotonic per release stream.

## Build

```bash
./gradlew :android:assembleDebug
```