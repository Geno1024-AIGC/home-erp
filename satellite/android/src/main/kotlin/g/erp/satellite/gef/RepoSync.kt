package g.erp.satellite.gef

import g.erp.satellite.update.Updater
import java.io.File

/**
 * Downloads the GEF feature packages attached to a release (canary
 * pre-release or stable) and installs whatever is newer than the installed
 * copy. Reuses the same channel + download source as the APK updater; the
 * list of assets always comes from api.github.com.
 */
object RepoSync {

    class Outcome(val applied: List<String>, val skipped: List<String>, val errors: List<String>) {
        val installed get() = applied.isNotEmpty()
        val failed get() = errors.isNotEmpty()
    }

    /** 0.1.<env>.<pack> → (env, pack); null if not parseable. */
    fun parseVersion(v: String?): Pair<Int, Int>? {
        if (v == null) return null
        val m = Regex("""0\.1\.(\d+)\.(\d+)""").find(v) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /**
     * A candidate replaces the installed copy when it parses to a strictly
     * larger (env, pack); unparseable versions are only installed when nothing
     * is installed yet.
     */
    fun isNewer(candidate: String?, installed: String?): Boolean {
        if (installed == null) return true
        val c = parseVersion(candidate) ?: return false
        val i = parseVersion(installed) ?: return false
        return (c.first > i.first) || (c.first == i.first && c.second > i.second)
    }

    fun sync(
        store: GefStore,
        cacheDir: File,
        channel: Updater.Channel,
        source: Updater.Source,
        progress: (String) -> Unit,
    ): Outcome {
        progress("获取发布信息…")
        val release = Updater.findFor(Updater.fetchReleases(), channel)
            ?: error("渠道「${channel.label}」暂无发布")
        progress("发布：${release.tag}")
        if (release.gefAssets.isEmpty()) error("该发布没有 GEF 功能包")

        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (asset in release.gefAssets) {
            progress("下载 ${asset.name}（${asset.size / 1024}KB）…")
            val bytes = runCatching {
                val tmp = File(cacheDir, asset.name)
                Updater.download(Updater.gefDownloadUrl(source, release, asset.name), tmp) { _, _ -> }
                tmp.readBytes()
            }.getOrElse { e ->
                errors += "${asset.name}：${e.message ?: "下载失败"}"
                continue
            }
            val probe = runCatching { store.probe(bytes) }.getOrElse { e ->
                errors += "${asset.name}：${e.message ?: "不是有效的功能包"}"
                continue
            }
            val installed = store.versionOf(probe.id)
            if (!isNewer(probe.version, installed)) {
                skipped += probe.name
                continue
            }
            runCatching { store.install(bytes) }.onFailure { e ->
                errors += "${probe.name}：${e.message ?: "安装失败"}"
            }.onSuccess {
                applied += probe.name + (probe.version?.let { " v$it" } ?: "")
            }
        }
        return Outcome(applied, skipped, errors)
    }
}