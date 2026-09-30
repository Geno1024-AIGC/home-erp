package g.sw.gef

object GefSmoke {

    @JvmStatic
    fun main(args: Array<String>) {
        val bundle = Gef.Bundle(
            id = "g.sw.erp.smoke",
            name = "冒烟",
            version = "0.1",
            summary = "self-test fixture",
            meta = mapOf("platforms" to "android,web"),
            icon = byteArrayOf(1, 2, 3),
            ui = """{"type":"page","title":"冒烟","children":[]}""",
        )
        val parsed = Gef.parse(Gef.write(bundle))
        check(parsed == bundle) { "round-trip mismatch" }
        check(Gef.write(parsed) == Gef.write(bundle)) { "write not stable" }
        check(parsed.id == "g.sw.erp.smoke") { "id mismatch" }
        check(parsed.meta == mapOf("platforms" to "android,web")) { "meta mismatch" }
        check(Gef.uiJson(parsed) is Map<*, *>) { "ui must parse as json object" }

        val withVm = bundle.copy(vms = mapOf("gefvm" to byteArrayOf(0, 1, 2, 3, -1, 42)))
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

        htmlRoundTrip()
        htmlHostileTests()

        println("[gef] smoke ok")
    }

    private fun htmlRoundTrip() {
        val pkg = HtmlGef.Package(
            id = "g.sw.erp.smoke-html",
            name = "HTML 冒烟",
            version = "0.1",
            entry = "index.html",
            icon = "icon",
            files = mapOf(
                "index.html" to "<!doctype html><html><body>ok</body></html>".toByteArray(),
                "icon" to byteArrayOf(1, 2, 3),
            ),
        )
        val packed = HtmlGef.pack(
            id = pkg.id, name = pkg.name, version = pkg.version, summary = pkg.summary,
            entry = pkg.entry, icon = pkg.icon, files = pkg.files,
        )
        check(HtmlGef.isZip(packed)) { "packed bytes must be a zip" }
        check(HtmlGef.unpack(packed) == pkg) { "html round-trip mismatch" }
        val repacked = HtmlGef.pack(
            id = pkg.id, name = pkg.name, version = pkg.version, summary = pkg.summary,
            entry = pkg.entry, icon = pkg.icon, files = pkg.files,
        )
        check(packed.contentEquals(repacked)) { "packed bytes not stable" }
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
        expectThrows { HtmlGef.unpack(zip("manifest.json" to manifest, "index.html" to byteArrayOf(), "x/../y" to byteArrayOf(1))) }
        expectThrows { HtmlGef.unpack(zip("manifest.json" to """{"id":"x/y","name":"n"}""".toByteArray(), "index.html" to byteArrayOf())) }
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

    private fun expectThrows(block: () -> Any?) {
        try {
            block()
            error("expected IllegalArgumentException but it did not throw")
        } catch (e: IllegalArgumentException) {
            check(e.message != null) { "illegal-arg without message" }
        }
    }
}