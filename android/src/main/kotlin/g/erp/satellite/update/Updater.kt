package g.erp.satellite.update

import g.erp.satellite.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {

    const val OWNER = "Geno1024-AIGC"
    const val REPO = "home-erp"

    enum class Channel(val label: String) {
        CANARY("Canary"),
        STABLE("正式版");

        companion object {
            fun from(stored: String): Channel =
                entries.firstOrNull { it.name == stored } ?: CANARY
        }
    }

    class Source(val id: String, val label: String, val downloadPrefix: String)

    val SOURCES = listOf(
        Source("github", "GitHub", "https://github.com"),
        Source("ghproxy", "ghproxy", "https://mirror.ghproxy.com/https://github.com"),
        Source("gh-proxy", "gh-proxy", "https://gh-proxy.com/https://github.com"),
        Source("ghfast", "ghfast.top", "https://ghfast.top/https://github.com"),
    )

    fun sourceFrom(stored: String): Source =
        SOURCES.firstOrNull { it.id == stored } ?: SOURCES.first()

    class Version(val pack: Int, val build: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::pack, Version::build)

        override fun toString(): String = "0.1.$pack.$build"
    }

    class Release(
        val tag: String,
        val name: String,
        val prerelease: Boolean,
        val publishedAt: String,
        val version: Version?,
        val apkName: String?,
        val apkSize: Long,
    )

    fun parseVersion(s: String): Version? {
        val m = Regex("""0\.1\.(\d+)\.(\d+)""").find(s) ?: return null
        return Version(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    fun fetchReleases(): List<Release> {
        val body = httpGet("https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30")
        val list = Json.parse(body) as? List<*> ?: return emptyList()
        return list.mapNotNull { it as? Map<*, *> }.mapNotNull { toRelease(it) }
    }

    fun findFor(releases: List<Release>, channel: Channel): Release? =
        releases.firstOrNull { it.prerelease == (channel == Channel.CANARY) }

    fun downloadUrl(source: Source, release: Release): String =
        "${source.downloadPrefix}/$OWNER/$REPO/releases/download/${release.tag}/${release.apkName}"

    fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "home-erp-satellite")
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val total = conn.contentLength.toLong()
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun toRelease(map: Map<*, *>): Release? {
        val tag = map["tag_name"] as? String ?: return null
        val assets = map["assets"] as? List<*> ?: emptyList<Any?>()
        val apk = assets.mapNotNull { it as? Map<*, *> }
            .firstOrNull { (it["name"] as? String)?.endsWith(".apk", ignoreCase = true) == true }
        return Release(
            tag = tag,
            name = map["name"] as? String ?: tag,
            prerelease = map["prerelease"] == true,
            publishedAt = map["published_at"] as? String ?: "",
            version = parseVersion(tag),
            apkName = apk?.get("name") as? String,
            apkSize = ((apk?.get("size") as? Double) ?: 0.0).toLong(),
        )
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "home-erp-satellite")
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}