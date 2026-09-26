package g.sw.gef

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object GefSmoke {

    @JvmStatic
    fun main(args: Array<String>) {
        val sample = resolveSample()

        val parsed = Gef.parse(Files.readString(sample))
        check(parsed.id == "g.sw.erp.inventory") { "id mismatch: ${parsed.id}" }
        check(parsed.name == "库存") { "name mismatch: ${parsed.name}" }
        check(parsed.version == "0.1") { "version mismatch" }
        check(parsed.summary == "家居物品与库存") { "summary mismatch" }
        check(parsed.meta == mapOf("platforms" to "android,web")) { "extra meta mismatch" }
        check(parsed.icon != null && parsed.icon.size > 0) { "icon missing" }
        check(parsed.ui.contains("\"type\": \"page\"")) { "ui not retained verbatim" }
        check(parsed.vms.isEmpty()) { "unexpected vm segments" }
        check(Gef.uiJson(parsed) is Map<*, *>) { "ui must parse as json object" }

        val roundTrip = Gef.parse(Gef.write(parsed))
        check(roundTrip == parsed) { "round-trip mismatch" }
        check(Gef.write(roundTrip) == Gef.write(parsed)) { "write not stable" }

        val withVm = parsed.copy(
            vms = mapOf("gefvm" to byteArrayOf(0, 1, 2, 3, -1, 42)),
        )
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

        println("[gef] smoke ok")
    }

    private fun resolveSample(): Path {
        val candidates = listOf(
            Paths.get("samples/inventory.gef"),
            Paths.get("swrepo/gef/samples/inventory.gef"),
            Paths.get(".").toAbsolutePath().resolve("samples/inventory.gef"),
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