# android — `g.erp.satellite`

The 卫星 (Satellite) Android app: talks to the Star's HTTP API. Zero AndroidX, zero third-party — plain framework UI (rough side-drawer + screens built in code).

Part of [Home ERP](../README.md).

## Layout

```
src/main/kotlin/g/erp/satellite/
├── MainActivity.kt        # activity + settings/account screens + drawer gesture + APK install
├── StarClient.kt          # tiny HttpURLConnection GET/POST helper with bearer token (default http://10.0.2.2:8080)
├── json/Json.kt           # hand-rolled JSON parse/format
└── update/
    ├── Updater.kt         # canary/stable release check, channel & source select, download
    └── InstallReceiver.kt # manifest BroadcastReceiver reporting session-install results
```

`applicationId` `g.erp.satellite`, label **New Home**, `minSdk 24` / `targetSdk 36` / `compileSdk 36`, JDK 17 bytecode.

## App behaviour

- On first launch (no star address configured) the home screen asks for the Star's HTTP address; it can also be managed in **设置 → 恒星** (multiple addresses: add / pick / delete). Then it fetches a few sample endpoints from the Star and renders them in a rough side-drawer UI (drawer opens by an edge swipe from the left, scrim follows the finger).
- **设置** holds three sections: 账号 (sign in / out), 恒星 (star addresses) and 更新 (update channel + source).
  - 账号: password login against `POST /api/auth/login` (method `password`); the bearer token is stored in prefs and sent on every Star request. A 401 response clears the token and the affected page shows 去登录.
  - Release metadata is always read from `api.github.com`; the APK download goes through the selected source prefix.
- APK installation follows opencode-inspire's flow: on **API 29+** the APK is copied into public Downloads (MediaStore) and opened with `ACTION_VIEW` so the system installer shows its confirm dialog; on older devices it falls back to the framework **`PackageInstaller` session API**, with the result reported by a manifest `BroadcastReceiver` notification. No `FileProvider`, no AndroidX.
  - API 33+: asks for `POST_NOTIFICATIONS` first. API 26+: routes to the "install unknown apps" setting when needed.
- Downloads keep the single-thread executor; a stale Star response can't overwrite the settings screen (guarded render).

## Signing

Both `debug` and `release` build types sign with the repo-tracked `signing/debug.jks` (standard debug keystore, `androiddebugkey` / `android`), so every CI build has the **same signature** and a canary update installs over the previous one. `versionCode` = the environment sequence (`versionSeq`), strictly monotonic per release stream.

## Build

```bash
./gradlew :satellite:android:assembleDebug
```