package app.wallcapture.core.export

/** Tiny JSON reader/writer so core needs no serialization library (and runs identically on Android). */
object Json {
    fun write(value: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        writeValue(sb, value, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun indent(sb: StringBuilder, level: Int) {
        if (level < 0) return
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun writeValue(sb: StringBuilder, v: Any?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Float -> sb.append(if (v.isFinite()) v.toString() else "null")
            is Double -> sb.append(if (v.isFinite()) v.toString() else "null")
            is Number -> sb.append(v.toString())
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    indent(sb, if (level < 0) -1 else level + 1)
                    writeString(sb, k.toString())
                    sb.append(if (level < 0) ":" else ": ")
                    writeValue(sb, x, if (level < 0) -1 else level + 1)
                }
                indent(sb, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                val flat = items.all { it == null || it is Number || it is Boolean }
                sb.append('[')
                for ((i, x) in items.withIndex()) {
                    if (i > 0) sb.append(if (flat && level >= 0) ", " else ",")
                    if (!flat) indent(sb, if (level < 0) -1 else level + 1)
                    writeValue(sb, x, if (level < 0) -1 else level + 1)
                }
                if (!flat) indent(sb, level)
                sb.append(']')
            }
            is DoubleArray -> writeValue(sb, v.toList(), level)
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    /** Parses JSON into Map<String, Any?>, List<Any?>, String, Double, Boolean or null. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "trailing characters at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
            }
        }
        fun lit(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "bad literal at $i" }
            i += w.length
            return v
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']') { "expected ] at $i" }
                i++
                return out
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i] == ':') { "expected : at $i" }
                i++
                out[k] = value()
                ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}') { "expected } at $i" }
                i++
                return out
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asList(): List<Any?> = this as List<Any?>

fun Any?.asDouble(): Double = (this as Number).toDouble()

fun Any?.asDoubles(): List<Double> = asList().map { it.asDouble() }
