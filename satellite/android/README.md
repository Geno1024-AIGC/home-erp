# android — `g.erp.satellite`

The 卫星 (Satellite) Android app: talks to the Star's HTTP API. Zero AndroidX, zero third-party — plain framework UI (rough side-drawer + screens built in code).

Part of [Home ERP](../README.md).

## Layout

```
src/main/kotlin/g/erp/satellite/
├── MainActivity.kt        # activity + settings/account screens + drawer gesture + APK install + WebView host
├── StarClient.kt          # tiny HttpURLConnection GET/POST helper with bearer token (default http://10.0.2.2:8080)
├── Topology.kt            # star-first star/planet probing, persisted in filesDir/topology.json
├── json/Json.kt           # hand-rolled JSON parse/format
├── gef/
│   ├── Gef.kt             # satellite port of the GEF v1 text container + GefPackage (native/html) abstraction + HTML zip parser
│   ├── GefStore.kt        # installed packages under filesDir/gefs (v1 singles + v0.1 zip dirs)
│   ├── GefRenderer.kt     # UI DSL v1 renderer (text/image/row/column/list/button/if)
│   ├── ErpBridge.kt       # @JavascriptInterface bridge: the only network path for HTML GEF pages
│   ├── RepoSync.kt        # download the GEF feature packages from a release and install newer copies
│   └── PackageOrder.kt    # user-controlled package order (上移/下移), persisted in prefs and used by drawer + repo list
└── update/
    ├── Updater.kt         # canary/stable release check, channel & source select, download
    └── InstallReceiver.kt # manifest BroadcastReceiver reporting session-install results
```

The satellite renders **two GEF formats** side by side (双渲染并存):
`Gef.kt`/`GefRenderer.kt` draw a v1 UI DSL bundle natively, while a v0.1 **zip
package** is unzipped into `filesDir/gefs/<id>/` and its entry HTML is shown in
a locked-down `WebView` (`showHtmlPackage`). Format detection is by sniffing the
leading bytes — a file picker accepts either `.gef`.

`applicationId` `g.erp.satellite`, label **New Home**, `minSdk 24` / `targetSdk 36` / `compileSdk 36`, JDK 17 bytecode.

## App behaviour

- On first launch (no star address configured) the home screen asks for the Star's HTTP address; it can also be managed in **设置 → 恒星** (multiple addresses: add / pick / delete).
- The satellite is **topology-aware**: on every startup it probes its stored topology (`filesDir/topology.json`, kept fresh as the satellite also stores data) — Star direct first, then every Planet — and connects to whichever answers first (`bootConnection`). Each probe has a short timeout, so an unreachable Star doesn't block switching to a Planet. The current entry (恒星直连 / 经行星) plus a 重新探测连接 button live in **设置 → 恒星 · 连接**; a successful probe refreshes the local topology file and the current `baseUrl`.
- The side drawer is **driven by installed GEF packages** (scanned from `filesDir/gefs`, listed with their bundle icon + name), not a hardcoded feature list; **设置** is always last. A **v1 UI DSL** package renders via the framework renderer: page text/image/row/column, lists bound to Star GET actions (`list.action`, `repeat` key), and buttons that re-fetch (`url:GET`) or post (`url:POST`). A **v0.1 HTML zip** package opens in a `WebView` locked down to its own directory (no file-in-JS, universal access off, every `http(s)` resource intercepted and muted); the page calls `window.Erp.apiGet(path, cb)` / `apiPost(path, body, cb)` / `getToken()` and Kotlin executes the request with the current `baseUrl` + token and replies `cb(data, null)` / `cb(null, "error")` — the page has **no direct network path**. Drawer opens by an edge swipe from the left, scrim follows the finger.
- **设置** holds four sections: 账号 (sign in / out), 恒星 · 连接 (topology probe + manual star addresses), 软件仓库 (install/uninstall/download GEF feature packages) and 更新 (update channel + source).
  - 账号: password login against `POST /api/auth/login` (method `password`); the bearer token is stored in prefs and sent on every Star request. A 401 response clears the token and the affected page shows a 去登录 prompt.
  - 软件仓库 installs a `.gef` picked via `ACTION_OPEN_DOCUMENT`, lists installed packages with **上移/下移** (manual order, persisted in prefs — the drawer follows it; newly installed packages land at the tail until moved) and 卸载, and **从发布同步功能包…** downloads the GEF feature packages attached to the canary pre-release (or stable channel) through the selected update source, installing whatever is newer by `(env, pack)` and reporting 更新 / 已是最新 / 失败 per feature. Packages come from the `gefs` build (one HTML zip per feature); the list of assets is always read from `api.github.com`.
  - Release metadata is always read from `api.github.com`; the APK download goes through the selected source prefix.
- APK installation follows opencode-inspire's flow: on **API 29+** the APK is copied into public Downloads (MediaStore) and opened with `ACTION_VIEW` so the system installer shows its confirm dialog; on older devices it falls back to the framework **`PackageInstaller` session API**, with the result reported by a manifest `BroadcastReceiver` notification. No `FileProvider`, no AndroidX.
  - API 33+: asks for `POST_NOTIFICATIONS` first. API 26+: routes to the "install unknown apps" setting when needed.
- Downloads keep the single-thread executor; a stale Star response can't overwrite the current screen (guarded by navigation identity).

## Signing

Both `debug` and `release` build types sign with the repo-tracked `signing/debug.jks` (standard debug keystore, `androiddebugkey` / `android`), so every CI build has the **same signature** and a canary update installs over the previous one. `versionCode` = the environment sequence (`versionSeq`), strictly monotonic per release stream.

## Build

```bash
./gradlew :satellite:android:assembleDebug
```