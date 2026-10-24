package com.encryptedenergy.sdk

/**
 * Minimal JSON serializer for flat payloads. org.json is stubbed out in
 * local unit tests, so payload building stays framework-free and testable
 * on the JVM.
 */
internal object Json {

    /** A pre-serialized JSON fragment (object or array) used as a value. */
    data class Raw(val text: String)

    /**
     * Serializes ordered (key, value) pairs; values may be String, Int,
     * Long, Double (finite), or [Raw].
     */
    fun obj(fields: List<Pair<String, Any>>): String =
        fields.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"${escape(k)}\":" + when (v) {
                is String -> "\"${escape(v)}\""
                is Int, is Long -> v.toString()
                is Double -> {
                    // NaN/Infinity are not JSON; a poisoned coord must fail
                    // loudly here, not 422 a whole batch server-side.
                    require(v.isFinite()) { "non-finite JSON number: $v" }
                    v.toString()
                }
                is Raw -> v.text
                else -> throw IllegalArgumentException("unsupported JSON value type: ${v::class}")
            }
        }

    /** Serializes fragments into a JSON array fragment. */
    fun arr(items: List<Raw>): Raw =
        Raw(items.joinToString(prefix = "[", postfix = "]", separator = ",") { it.text })

    private fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    // --- Reading (0010): response-body parse for mint/claim/ingest ---
    //
    // org.json is stubbed in the JVM test suite, so the anonymous-first
    // parses (api_key, gateway.name, bounty_earned, claim fields) run on this
    // tiny reader instead and stay unit-testable. Objects decode to
    // Map<String, Any?>, arrays to List<Any?>, with String / Double / Boolean
    // / null leaves; malformed input throws. Numbers are always Double —
    // callers that want an Int go through [int].

    /** Parses one JSON document; throws IllegalArgumentException if malformed. */
    fun parse(text: String): Any? = Reader(text).run {
        val value = readValue()
        skipWhitespace()
        require(atEnd()) { "trailing content at $pos" }
        value
    }

    /** A top-level or nested object field, as its parsed node (or null). */
    fun child(node: Any?, key: String): Any? = (node as? Map<*, *>)?.get(key)

    fun str(node: Any?, key: String): String? = child(node, key) as? String

    /** Integer field; tolerates the Double the reader produces for numbers. */
    fun int(node: Any?, key: String): Int? = (child(node, key) as? Double)?.toInt()

    fun bool(node: Any?, key: String): Boolean? = child(node, key) as? Boolean

    /** A field that is an array of strings (e.g. claim 422 `details`). */
    fun strings(node: Any?, key: String): List<String>? =
        (child(node, key) as? List<*>)?.filterIsInstance<String>()

    private class Reader(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(pos < s.length) { "unexpected end of input" }
            return when (s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBool()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                map[key] = readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> error("expected ',' or '}' but got '$c'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> error("expected ',' or ']' but got '$c'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> error("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readBool(): Boolean = when {
            s.startsWith("true", pos) -> { pos += 4; true }
            s.startsWith("false", pos) -> { pos += 5; false }
            else -> error("invalid literal at $pos")
        }

        private fun readNull(): Any? {
            require(s.startsWith("null", pos)) { "invalid literal at $pos" }
            pos += 4
            return null
        }

        private fun readNumber(): Double {
            val start = pos
            while (pos < s.length && s[pos].let {
                    it == '-' || it == '+' || it == '.' || it == 'e' || it == 'E' || it in '0'..'9'
                }) pos++
            val token = s.substring(start, pos)
            return token.toDoubleOrNull() ?: error("invalid number '$token'")
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else ' '
        private fun next(): Char {
            require(pos < s.length) { "unexpected end of input" }
            return s[pos++]
        }
        private fun expect(c: Char) {
            require(pos < s.length && s[pos] == c) { "expected '$c' at $pos" }
            pos++
        }
        private fun error(msg: String): Nothing = throw IllegalArgumentException(msg)
    }
}
