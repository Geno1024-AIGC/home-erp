package g.build.versioning

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class VersioningPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val base = "0.1"
        val packFile = project.file("count.pack")
        val sha = headSha(project)
        val seq = runSequence(project)

        val pack = readCount(packFile)
        val version = "$base.$seq.$pack.$sha"
        project.version = version
        project.extensions.extraProperties["versionSeq"] = seq
        project.logger.lifecycle("[versioning] ${project.path}: $version")

        val bumpPack = registerBump(project, "bumpVersionPack", "Increment the pack counter of this module.", packFile)
        project.tasks.configureEach {
            if (name in PACKAGING_TASKS) dependsOn(bumpPack)
        }
    }

    /**
     * Monotonic `<env>` sequence: GitHub Actions run number on CI, otherwise
     * the total commit count of HEAD. Both increase over time with no commit
     * round-trip, so the version's leading sequence never stalls. `<pack>` is
     * the module's own committed [packFile] packaging counter, bumped once per
     * packaging task run.
     */
    private fun runSequence(project: Project): Int {
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.trim()
        if (!ciRun.isNullOrEmpty()) return ciRun.toIntOrNull() ?: 0
        return runCatching {
            val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
                .directory(project.rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            check(process.exitValue() == 0) { "failed to count HEAD commits" }
            out.toInt()
        }.getOrElse { 0 }
    }

    private fun registerBump(
        project: Project,
        name: String,
        desc: String,
        counterFile: File,
    ) = project.tasks.register(name) {
        group = "versioning"
        description = desc
        outputs.upToDateWhen { false }
        doLast { writeCount(counterFile, readCount(counterFile) + 1) }
    }

    private fun headSha(project: Project): String = runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
            .directory(project.rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val sha = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        check(process.exitValue() == 0 && sha.isNotEmpty()) { "failed to resolve HEAD sha" }
        sha
    }.getOrElse { "dev" }

    private fun readCount(file: File): Int =
        if (file.exists()) file.readText().trim().toIntOrNull() ?: 0 else 0

    private fun writeCount(file: File, value: Int) {
        file.writeText(value.toString())
    }

    companion object {
        private val PACKAGING_TASKS = setOf(
            "jar", "assemble", "build", "installDist", "distTar", "distZip",
            "assembleDebug", "assembleRelease", "bundleDebug", "bundleRelease",
        )
    }
}