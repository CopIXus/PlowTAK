package com.atakmap.android.ideaplow.gis

/**
 * Minimal dependency-free JSON parser for agency GIS import and provisioning
 * profiles. Parses the full JSON grammar into plain Kotlin types:
 * `Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean`, `null`.
 *
 * Kept deliberately small: no streaming, no custom types, whole-document
 * parse only. Fine for provisioning files and road-attribute GeoJSON up to a
 * few tens of MB; not intended as a general-purpose library.
 */
object MiniJson {

    /** Parse a JSON document; null on any syntax error (fail-soft). */
    fun parse(text: String): Any? {
        return try {
            val p = Parser(text)
            val v = p.parseValue()
            p.skipWhitespace()
            if (!p.atEnd()) null else v
        } catch (e: Exception) {
            null
        }
    }

    /** Convenience: parse expecting a top-level object. */
    fun parseObject(text: String): Map<String, Any?>? =
        parse(text) as? Map<String, Any?>

    // -------------------------------------------------- typed accessors

    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    fun array(v: Any?): List<Any?>? = v as? List<Any?>

    fun string(v: Any?): String? = v as? String

    /** Numbers parse as Double; numeric strings are also accepted. */
    fun double(v: Any?): Double? = when (v) {
        is Double -> v
        is String -> v.toDoubleOrNull()
        else -> null
    }

    fun int(v: Any?): Int? = double(v)?.let {
        if (it.isFinite()) it.toInt() else null
    }

    /** Booleans; also accepts "true"/"false"/"yes"/"no"/"1"/"0" strings and 0/1 numbers. */
    fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Double -> if (v == 1.0) true else if (v == 0.0) false else null
        is String -> when (v.lowercase()) {
            "true", "yes", "y", "1" -> true
            "false", "no", "n", "0" -> false
            else -> null
        }
        else -> null
    }

    // ---------------------------------------------------------- encoding

    /** Escape + quote a string for JSON output. */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ------------------------------------------------------------ parser

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) fail("unexpected end")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> fail("expected , or } in object")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> fail("expected , or ] in array")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("bad escape")
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > text.length) fail("bad \\u escape")
                                sb.append(
                                    text.substring(pos, pos + 4).toInt(16).toChar()
                                )
                                pos += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val s = text.substring(start, pos)
            return s.toDoubleOrNull() ?: fail("bad number '$s'")
        }

        private fun <T> parseLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, pos)) fail("expected $literal")
            pos += literal.length
            return value
        }

        private fun peek(): Char {
            if (atEnd()) fail("unexpected end")
            return text[pos]
        }

        private fun expect(c: Char) {
            if (atEnd() || text[pos] != c) fail("expected '$c'")
            pos++
        }

        private fun fail(msg: String): Nothing =
            throw IllegalArgumentException("JSON error at $pos: $msg")
    }
}
