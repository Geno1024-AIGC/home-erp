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
            validate(sample)
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
        expectThrows { Gef.parse("gef 1.0\nid: x\nname: n\n==== vm: ====\n") }

        println("[gef] smoke ok: ${samples.size} samples validated")
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