package g.build.versioning

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class VersioningPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val base = "0.1"
        val packFile = project.file("count.pack")
        val buildFile = project.file("count.build")
        val sha = headSha(project)

        val pack = readCount(packFile)
        val build = readCount(buildFile)
        val version = "$base.$pack.$build-$sha"
        project.version = version
        project.logger.lifecycle("[versioning] ${project.path}: $version")

        val bumpPack = registerBump(project, "bumpVersionPack", "Increment the pack counter of this module.", packFile)
        val bumpBuild = registerBump(project, "bumpVersionBuild", "Increment the build counter of this module.", buildFile)

        project.tasks.configureEach {
            when {
                name in PACKAGING_TASKS -> dependsOn(bumpPack)
                name in RUNNING_TASKS -> dependsOn(bumpBuild)
            }
        }
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
        private val RUNNING_TASKS = setOf("run", "installDebug", "installRelease")
    }
}