package g.sw.heds

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object HedsSmoke {

    @JvmStatic
    fun main(args: Array<String>) {
        val sample = resolveSample()

        val parsed = Heds.parse(Files.readString(sample))
        check(parsed.id == "g.sw.erp.inventory") { "id mismatch: ${parsed.id}" }
        check(parsed.name == "库存") { "name mismatch: ${parsed.name}" }
        check(parsed.version == "0.1") { "version mismatch" }
        check(parsed.summary == "家居物品与库存") { "summary mismatch" }
        check(parsed.meta == mapOf("platforms" to "android,web")) { "extra meta mismatch" }
        check(parsed.icon != null && parsed.icon.size > 0) { "icon missing" }
        check(parsed.ui.contains("\"type\": \"page\"")) { "ui not retained verbatim" }
        check(parsed.vms.isEmpty()) { "unexpected vm segments" }
        check(Heds.uiJson(parsed) is Map<*, *>) { "ui must parse as json object" }

        val roundTrip = Heds.parse(Heds.write(parsed))
        check(roundTrip == parsed) { "round-trip mismatch" }
        check(Heds.write(roundTrip) == Heds.write(parsed)) { "write not stable" }

        val withVm = parsed.copy(
            vms = mapOf("hedsvm" to byteArrayOf(0, 1, 2, 3, -1, 42)),
        )
        val fmt = Heds.write(withVm)
        check(fmt.contains("==== vm:hedsvm ===")) { "vm segment not framed" }
        check(Heds.parse(fmt) == withVm) { "vm round-trip mismatch" }

        expectThrows { Heds.parse("hello\n") }
        expectThrows { Heds.parse("heds 9.9\nname: n\n==== ui ====\n{}") }
        expectThrows { Heds.parse("heds 1.0\nname: n\n==== ui ====\nnot json") }
        expectThrows { Heds.parse("heds 1.0\nname: n\n==== ui ====\n[1,2]") }
        expectThrows { Heds.parse("heds 1.0\nid: x\n==== ui ====\n{}") }
        expectThrows { Heds.parse("heds 1.0\nname: a\nname: b\n==== ui ====\n{}") }
        expectThrows { Heds.parse("heds 1.0\nid: x\nname: n\n==== what ====\n") }
        expectThrows { Heds.parse("heds 1.0\nid: x\nname: n\n==== icon ====\n\n==== ui ====\n{}") }
        expectThrows { Heds.parse("heds 1.0\nid: x\nname: n\n==== ui ====\n{}\n==== icon ====\n") }
        expectThrows { Heds.parse("heds 1.0\nid: x\nname: n\n==== vm: ====\n") }

        println("[heds] smoke ok")
    }

    private fun resolveSample(): Path {
        val candidates = listOf(
            Paths.get("samples/inventory.heapp"),
            Paths.get("swrepo/heds/samples/inventory.heapp"),
            Paths.get(".").toAbsolutePath().resolve("samples/inventory.heapp"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("sample not found, tried: $candidates")
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