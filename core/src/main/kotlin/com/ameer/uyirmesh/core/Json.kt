package com.ameer.uyirmesh.core

/**
 * Minimal, dependency-free JSON support used by the core library and the app.
 *
 * Parsed values map to: Map<String, Any?> (objects, insertion ordered), List<Any?> (arrays),
 * String, Long (integers that fit), Double (other numbers), Boolean and null.
 */
class JsonException(message: String) : RuntimeException(message)

object Json {

    fun parse(text: String): Any? = Parser(text).parseDocument()

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("Expected a JSON object")

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    fun toBytes(value: Any?): ByteArray = stringify(value).toByteArray(Charsets.UTF_8)

    fun objectFromBytes(bytes: ByteArray): Map<String, Any?> = parseObject(String(bytes, Charsets.UTF_8))

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> writeDouble(sb, v.toDouble())
            is Double -> writeDouble(sb, v)
            is Number -> writeDouble(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Collection<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList())
            is IntArray -> write(sb, v.toList())
            is LongArray -> write(sb, v.toList())
            is DoubleArray -> write(sb, v.toList())
            is Enum<*> -> writeString(sb, v.name)
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) {
            sb.append("null")
            return
        }
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) {
            sb.append(d.toLong().toString())
        } else {
            sb.append(d.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
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
                else -> if (c < ' ') {
                    sb.append("\\u")
                    sb.append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = parseValue(0)
            skipWs()
            if (i != s.length) throw JsonException("Unexpected trailing data at $i")
            return v
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > 64) throw JsonException("JSON nested too deeply")
            skipWs()
            if (i >= s.length) throw JsonException("Unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw JsonException("Unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("Invalid literal at $i")
            i += word.length
            return value
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonException("Expected key at $i")
                val key = parseString()
                skipWs()
                if (peek() != ':') throw JsonException("Expected ':' at $i")
                i++
                map[key] = parseValue(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> throw JsonException("Expected ',' or '}' at $i")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(parseValue(depth + 1))
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> throw JsonException("Expected ',' or ']' at $i")
                }
            }
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonException("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("Bad unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw JsonException("Bad unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            if (text == "-" || text.isEmpty()) throw JsonException("Bad number at $start")
            if (!isDouble) {
                text.toLongOrNull()?.let { return it }
            }
            return text.toDoubleOrNull() ?: throw JsonException("Bad number '$text'")
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }
    }
}

/* ---- Typed accessors for parsed JSON objects ---- */

fun Map<String, Any?>.str(key: String, default: String = ""): String = (this[key] as? String) ?: default
fun Map<String, Any?>.strOrNull(key: String): String? = this[key] as? String
fun Map<String, Any?>.long(key: String, default: Long = 0L): Long = (this[key] as? Number)?.toLong() ?: default
fun Map<String, Any?>.int(key: String, default: Int = 0): Int = (this[key] as? Number)?.toInt() ?: default
fun Map<String, Any?>.double(key: String, default: Double = 0.0): Double = (this[key] as? Number)?.toDouble() ?: default
fun Map<String, Any?>.doubleOrNull(key: String): Double? = (this[key] as? Number)?.toDouble()
fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean = (this[key] as? Boolean) ?: default

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?> = (this[key] as? Map<String, Any?>) ?: emptyMap()

fun Map<String, Any?>.strList(key: String): List<String> = list(key).mapNotNull { it as? String }
