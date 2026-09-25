package g.erp.satellite.json

/**
 * Minimal recursive-descent JSON parser — pure Kotlin, no dependencies.
 * Decodes into Map<String, Any?> / List<Any?> / String / Double / Boolean / null.
 * Throws [IllegalArgumentException] on malformed input.
 */
object Json {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWs()
        check(reader.ended()) { "trailing content at index ${reader.pos}" }
        return value
    }

    class Reader(s: String) {
        private val text = s
        var pos = 0
            private set

        fun ended(): Boolean = pos >= text.length

        fun skipWs() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun value(): Any? {
            skipWs()
            return when (text.getOrNull(pos)) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            skipWs()
            check(text.startsWith(word, pos)) { "expected '$word' at index $pos" }
            pos += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            skipWs()
            check(expect('{')) { "expected '{' at index ${pos - 1}" }
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (take('}')) return map
            while (true) {
                skipWs()
                val key = string()
                skipWs()
                check(take(':')) { "expected ':' at index $pos" }
                map[key] = value()
                skipWs()
                if (take('}')) return map
                check(take(',')) { "expected ',' or '}' at index $pos" }
            }
        }

        private fun arr(): List<Any?> {
            skipWs()
            check(expect('[')) { "expected '[' at index ${pos - 1}" }
            val list = ArrayList<Any?>()
            skipWs()
            if (take(']')) return list
            while (true) {
                list.add(value())
                skipWs()
                if (take(']')) return list
                check(take(',')) { "expected ',' or ']' at index $pos" }
            }
        }

        private fun string(): String {
            skipWs()
            check(expect('"')) { "expected '\"' at index ${pos - 1}" }
            val sb = StringBuilder()
            while (true) {
                val c = text[pos]
                pos++
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        val esc = text[pos]
                        pos++
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = text.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("bad escape '\\$esc' at index ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            skipWs()
            val start = pos
            while (pos < text.length && text[pos] in "-+.eE0123456789") pos++
            check(pos > start) { "expected number at index $pos" }
            return text.substring(start, pos).toDouble()
        }

        private fun expect(c: Char): Boolean {
            if (text.getOrNull(pos) != c) return false
            pos++
            return true
        }

        private fun take(c: Char): Boolean = expect(c)
    }

    fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }
    }
}