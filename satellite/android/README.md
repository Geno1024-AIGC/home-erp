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
├── ui/Palette.kt          # day/night colour set read from Configuration.uiMode
├── gef/
│   ├── Gef.kt             # satellite port of the GEF v1 text container + GefPackage (native/html) abstraction + HTML zip parser
│   ├── GefStore.kt        # installed packages under filesDir/gefs (v1 singles + v0.1 zip dirs)
│   ├── GefRenderer.kt     # UI DSL v1 renderer (text/image/row/column/list/button/if)
│   ├── ErpBridge.kt       # @JavascriptInterface bridge: the only network path for HTML GEF pages
│   ├── RepoSync.kt        # download the GEF feature packages from a release and install newer copies
│   └── PackageOrder.kt    # persisted user-controlled package order (drag in the drawer)
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
  - 账号: password login against `POST /api/auth/login` (method `password`), plus a 注册 form that calls `POST /api/auth/register` and then signs straight in — the first account registered against a fresh Star becomes its admin. The bearer token and role are stored in prefs and sent on every Star request; the role shows next to the signed-in name (管理员 / 成员). A 401 response clears the token and the affected page shows a 去设置登录或注册 prompt, so a signed-out satellite is never stuck — every feature module now refuses anonymous calls.
  - 软件仓库 installs a `.gef` picked via `ACTION_OPEN_DOCUMENT`, lists installed packages with 卸载, and **从发布同步功能包…** downloads the GEF feature packages attached to the canary pre-release (or stable channel) through the selected update source, installing whatever is newer by `(env, pack)` and reporting 更新 / 已是最新 / 失败 per feature. Packages come from the `gefs` build (one HTML zip per feature); the list of assets is always read from `api.github.com`.
- Feature **order is user-controlled**: long-press a package row in the side drawer to start a framework drag (no AndroidX `ItemTouchHelper`); rows shift live under the finger, the drop persists the id order in prefs, and 设置 stays pinned last. Newly installed packages land at the tail until dragged.
  - Release metadata is always read from `api.github.com`; the APK download goes through the selected source prefix.
- APK installation follows opencode-inspire's flow: on **API 29+** the APK is copied into public Downloads (MediaStore) and opened with `ACTION_VIEW` so the system installer shows its confirm dialog; on older devices it falls back to the framework **`PackageInstaller` session API**, with the result reported by a manifest `BroadcastReceiver` notification. No `FileProvider`, no AndroidX.
  - API 33+: asks for `POST_NOTIFICATIONS` first. API 26+: routes to the "install unknown apps" setting when needed.
- Downloads keep the single-thread executor; a stale Star response can't overwrite the current screen (guarded by navigation identity).
- **Dark mode follows the system by default**, with an override in **设置 → 外观 → 深色模式** (跟随系统 / 浅色 / 深色). Because the whole UI is drawn in code, every colour comes from `ui/Palette.kt`, which resolves against `Configuration.UI_MODE_NIGHT_*`; the window theme comes from the resource qualifier — `values/themes.xml` stays `Theme.Material.Light.NoActionBar` and `values-night/themes.xml` uses the dark parent. `values-night-v35` exists too: `uiMode` outranks `platform version` in resource precedence, so without it a nightly Android 15+ build would lose the edge-to-edge opt-out.
  - Forcing a mode never calls the deprecated `Resources.updateConfiguration`; `attachBaseContext` folds the pref into the base context via `createConfigurationContext`, so the framework picks the right theme variant *before* the window and any view exist, and `Palette` + the WebView's `prefers-color-scheme` all read the same value. 跟随系统 returns the base context untouched. The choice is applied on every launch, so a system toggle still flips the app when nothing is forced.
  - Changing the mode calls `recreate()`, which runs the same flow as a configuration change: `onSaveInstanceState` keeps the current screen (settings or a package) and `onCreate` restores it, so re-skinning never dumps the user back on the first feature page. Cold starts (`savedInstanceState == null`) still land on the first installed package, or 设置 when there is none.
  - v0.1 HTML packages switch through their own `@media (prefers-color-scheme: dark)` stylesheet, and the WebView background is set to the palette background so there is no white flash while the page loads.

## Signing

Both `debug` and `release` use the **shared org release keystore** described by `../RULES/KEYSTORE.md` in the parent workspace. Values resolve as environment variable (CI) → git-ignored `local.properties` → default alias `geno`:

| `local.properties` key | env var | default |
|---|---|---|
| `keystore.storeFile` | `KEYSTORE_STORE_FILE` | — (required) |
| `keystore.storePassword` | `KEYSTORE_STORE_PASSWORD` | — (required) |
| `keystore.keyPassword` | `KEYSTORE_KEY_PASSWORD` | store password |
| `keystore.keyAlias` | `KEYSTORE_KEY_ALIAS` | `geno` |

Nothing keystore-related is committed — no binary, no password, no alias. When the values are absent:

- `debug` falls back to the AGP default `~/.android/debug.keystore`, so a local `assembleDebug` needs zero setup.
- `release` is deliberately left **unsigned** (`android-release-unsigned.apk`). The Canary workflow never publishes it: it fails early if the `KEYSTORE_BASE64` org secret is unavailable (before deleting the previous pre-release) and separately asserts the built release APK is not `-unsigned`.

`versionCode` = the environment sequence (`versionSeq`), strictly monotonic per release stream.

**Signature change:** releases up to `v0.1.50` were signed with a throwaway debug keystore that used to live in this repo, so the first org-signed release **cannot install over an existing install** — uninstall once, then install. Installed GEF packages and settings are local and are lost by that uninstall; Star-side data is unaffected. Canary updates install in place from then on.

## Build

```bash
./gradlew :satellite:android:assembleDebug
```