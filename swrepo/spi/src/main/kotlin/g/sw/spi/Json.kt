package g.sw.spi

/**
 * Minimal hand-rolled JSON for the Star's HTTP API: parse text into
 * [Map]<String, Any?>/[List]<Any?>/[String]/[Number]/[Boolean]/null, and write
 * them back out. Kotlin stdlib + JDK only.
 */
object Json {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWs()
        check(parser.pos >= parser.text.length) { "trailing tokens after JSON value" }
        return value
    }

    fun write(value: Any?): String = buildString { writeValue(this, value) }

    private fun writeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Double -> {
                if (value.isFinite()) out.append(value.toString()) else out.append("null")
            }
            is Float -> {
                if (value.isFinite()) out.append(value.toString()) else out.append("null")
            }
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k.toString())
                    out.append(':')
                    writeValue(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    writeValue(out, v)
                }
                out.append(']')
            }
            is ByteArray -> writeString(out, java.util.Base64.getEncoder().encodeToString(value))
            is Char -> writeString(out, value.toString())
            else -> writeValue(out, value.toString())
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(val text: String) {
        var pos = 0

        fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            check(pos < text.length) { "unexpected end of JSON" }
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseKeyword("true", true)
                'f' -> parseKeyword("false", false)
                'n' -> parseKeyword("null", null)
                else -> parseNumber(c)
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peekIs('}')) {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                check(peekIs(':')) { "expected ':' after object key" }
                pos++
                map[key] = parseValue()
                skipWs()
                when {
                    peekIs(',') -> pos++
                    peekIs('}') -> {
                        pos++
                        return map
                    }
                    else -> error("expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (peekIs(']')) {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                when {
                    peekIs(',') -> pos++
                    peekIs(']') -> {
                        pos++
                        return list
                    }
                    else -> error("expected ',' or ']' in array")
                }
            }
        }

        private fun parseString(): String {
            check(peekIs('"')) { "expected '\"'" }
            pos++
            val out = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        check(pos < text.length) { "unterminated escape" }
                        when (val e = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                check(pos + 4 <= text.length) { "bad \\u escape" }
                                out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> error("control character in string")
                    else -> out.append(c)
                }
            }
            error("unterminated string")
        }

        private fun parseKeyword(word: String, value: Any?): Any? {
            check(text.regionMatches(pos, word, 0, word.length)) { "invalid token at $pos" }
            pos += word.length
            return value
        }

        private fun parseNumber(first: Char): Number {
            val start = pos
            pos++
            while (pos < text.length && (text[pos] in '0'..'9' || text[pos] in "+-.eE")) pos++
            val raw = text.substring(start, pos)
            return if (raw.any { it == '.' || it == 'e' || it == 'E' }) raw.toDouble()
            else raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun peekIs(c: Char): Boolean = pos < text.length && text[pos] == c
    }
}