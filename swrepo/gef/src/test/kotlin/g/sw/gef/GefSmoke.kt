package g.sw.gef

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object GefSmoke {

    @JvmStatic
    fun main(args: Array<String>) {
        val samplesDir = resolveSamplesDir()
        val samples = Files.list(samplesDir).use { stream ->
            stream.filter { it.toString().endsWith(".gef") }.toList().sorted()
        }
        check(samples.isNotEmpty()) { "no *.gef samples found in $samplesDir" }

        for (sample in samples) {
            val bytes = Files.readAllBytes(sample)
            check(bytes.isNotEmpty()) { "empty sample: $sample" }
            if (HtmlGef.isZip(bytes)) {
                validateHtml(sample, HtmlGef.unpack(bytes))
            } else {
                validate(sample)
            }
        }

        val inventory = Gef.parse(Files.readString(
            samples.first { it.fileName.toString() == "inventory.gef" }))
        check(inventory.name == "库存") { "name mismatch: ${inventory.name}" }
        check(inventory.id == "g.sw.erp.inventory") { "id mismatch: ${inventory.id}" }
        check(inventory.meta == mapOf("platforms" to "android,web")) { "extra meta mismatch" }

        val members = Gef.parse(Files.readString(
            samples.first { it.fileName.toString() == "members.gef" }))
        check(members.name == "成员") { "name mismatch: ${members.name}" }
        check(members.id == "g.sw.erp.members") { "id mismatch: ${members.id}" }
        check(members.icon != null && members.icon.size > 0) { "icon missing" }

        val withVm = inventory.copy(vms = mapOf("gefvm" to byteArrayOf(0, 1, 2, 3, -1, 42)))
        val fmt = Gef.write(withVm)
        check(fmt.contains("==== vm:gefvm ===")) { "vm segment not framed" }
        check(Gef.parse(fmt) == withVm) { "vm round-trip mismatch" }

        expectThrows { Gef.parse("hello\n") }
        expectThrows { Gef.parse("gef 9.9\nname: n\n==== ui ====\n{}") }
        expectThrows { Gef.parse("gef 1.0\nname: n\n==== ui ====\nnot json") }
        expectThrows { Gef.parse("gef 1.0\nname: n\n==== ui ====\n[1,2]") }
        expectThrows { Gef.parse("gef 1.0\nid: x\n==== ui ====\n{}") }
        expectThrows { Gef.parse("gef 1.0\nname: a\nname: b\n==== ui ====\n{}") }
        expectThrows { Gef.parse("gef 1.0\nid: x\nname: n\n==== what ====\n") }
        expectThrows { Gef.parse("gef 1.0\nid: x\nname: n\n==== icon ====\n\n==== ui ====\n{}") }
        expectThrows { Gef.parse("gef 1.0\nid: x\nname: n\n==== ui ====\n{}\n==== icon ====\n") }
        expectThrows { Gef.parse("gef 1.0\nid: x\nname: n\n==== vm: ====\n" + "==== ui ====\n{}") }

        htmlHostileTests()

        println("[gef] smoke ok: ${samples.size} samples validated")
    }

    private fun validateHtml(sample: Path, pkg: HtmlGef.Package) {
        check(pkg.id.isNotBlank()) { "blank id in $sample" }
        check(pkg.name.isNotBlank()) { "blank name in $sample" }
        check(pkg.files.containsKey(pkg.entry)) { "entry file missing from assets: $sample" }
        pkg.icon?.let { check(pkg.files.containsKey(it)) { "icon file missing from assets: $sample" } }
        check(pkg.files.values.all { it.isNotEmpty() }) { "empty asset in $sample" }

        val roundTrip = HtmlGef.unpack(HtmlGef.pack(
            id = pkg.id, name = pkg.name, version = pkg.version, summary = pkg.summary,
            entry = pkg.entry, icon = pkg.icon, files = pkg.files,
        ))
        check(roundTrip == pkg) { "html round-trip mismatch: $sample" }
    }

    private fun htmlHostileTests() {
        val manifest = """{"id":"x","name":"n","type":"html"}""".toByteArray()

        expectThrows { HtmlGef.unpack("definitely not a zip".toByteArray()) }
        expectThrows { HtmlGef.unpack(emptyZip()) }
        expectThrows { HtmlGef.unpack(zip("manifest.json" to manifest)) }
        expectThrows { HtmlGef.unpack(zip("manifest.json" to """{"name":"n"}""".toByteArray(), "index.html" to byteArrayOf())) }
        expectThrows { HtmlGef.unpack(zip("manifest.json" to """{"id":"x","name":"n","type":"nope"}""".toByteArray(), "index.html" to byteArrayOf())) }
        expectThrows { HtmlGef.unpack(zip("manifest.json" to """{"id":"x","name":"n","entry":"missing.html"}""".toByteArray(), "index.html" to byteArrayOf())) }
        expectThrows { HtmlGef.unpack(zip("manifest.json" to """{"id":"x","name":"n","icon":"i.png"}""".toByteArray(), "index.html" to byteArrayOf())) }
        expectThrows { HtmlGef.unpack(zip("manifest.json" to """abc""".toByteArray(), "index.html" to byteArrayOf())) }
        expectThrows { HtmlGef.unpack(zip("manifest.json" to manifest, "../evil" to byteArrayOf(1))) }
        expectThrows { HtmlGef.unpack(zip("manifest.json" to manifest, "a/b/../c" to byteArrayOf(1))) }
        expectThrows { HtmlGef.unpack(zip("manifest.json" to manifest, "BIG" to ByteArray(HtmlGef.MAX_TOTAL_BYTES))) }
    }

    private fun emptyZip(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { }
        return out.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun validate(sample: Path) {
        val parsed = Gef.parse(Files.readString(sample))
        check(parsed.id.isNotBlank()) { "blank id in $sample" }
        check(parsed.name.isNotBlank()) { "blank name in $sample" }
        if (parsed.icon != null) check(parsed.icon.size > 0) { "empty icon in $sample" }
        check(parsed.ui.contains("\"type\": \"page\"")) { "ui must be a page: $sample" }
        check(Gef.uiJson(parsed) is Map<*, *>) { "ui must parse as json object: $sample" }
        check(parsed.vms.isEmpty()) { "sample must not carry vm segments: $sample" }

        val roundTrip = Gef.parse(Gef.write(parsed))
        check(roundTrip == parsed) { "round-trip mismatch: $sample" }
        check(Gef.write(roundTrip) == Gef.write(parsed)) { "write not stable: $sample" }
    }

    private fun resolveSamplesDir(): Path {
        val candidates = listOf(
            Paths.get("samples"),
            Paths.get("swrepo/gef/samples"),
            Paths.get(".").toAbsolutePath().resolve("samples"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("samples dir not found, tried: $candidates")
    }

    private fun expectThrows(block: () -> Any?) {
        try {
            block()
            error("expected IllegalArgumentException but it did not throw")
        } catch (e: IllegalArgumentException) {
            check(e.message != null) { "illegal-arg without message" }
        }
    }
}