package com.andrerinas.openheadunit.connection.carplaynative.codec

/**
 * The XML plist of the lockdown protocol, as a pure parser and writer. A hand-made reader
 * keeps it testable on the JVM. It skips a DOCTYPE and never expands an entity declaration.
 * Ported from xcertplay (https://github.com/shilapi/xcertplay), `LockdownPlistChannel`.
 */
object XmlPlist {
    private const val MAX_ENTRIES = 1024
    private const val HEADER =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">" +
            "<plist version=\"1.0\">"

    fun encode(root: PlistValue.Dict): ByteArray {
        val sb = StringBuilder(HEADER)
        append(sb, root, 0)
        return sb.append("</plist>").toString().toByteArray(Charsets.UTF_8)
    }

    fun parse(xml: ByteArray): PlistValue.Dict {
        val tokens = Tokenizer(String(xml, Charsets.UTF_8))
        val parser = Parser(tokens)
        parser.expectStart("plist")
        val root = parser.value(0)
        parser.expectEnd("plist")
        if (tokens.next() != null) throw PlistException("plist: content after </plist>")
        return root as? PlistValue.Dict ?: throw PlistException("plist: root value must be <dict>")
    }

    private fun append(sb: StringBuilder, value: PlistValue, depth: Int) {
        if (depth > PlistValue.MAX_DEPTH) throw PlistException("plist: nesting exceeds ${PlistValue.MAX_DEPTH}")
        when (value) {
            is PlistValue.Dict -> {
                if (value.entries.size > MAX_ENTRIES) throw PlistException("plist: dictionary over $MAX_ENTRIES entries")
                sb.append("<dict>")
                for ((key, v) in value.entries) {
                    sb.append("<key>").append(escape(key)).append("</key>")
                    append(sb, v, depth + 1)
                }
                sb.append("</dict>")
            }
            is PlistValue.Items -> {
                sb.append("<array>")
                value.items.forEach { append(sb, it, depth + 1) }
                sb.append("</array>")
            }
            is PlistValue.Text -> sb.append("<string>").append(escape(value.value)).append("</string>")
            is PlistValue.Integer -> sb.append("<integer>").append(java.lang.Long.toUnsignedString(value.value)).append("</integer>")
            is PlistValue.Real -> sb.append("<real>").append(value.value).append("</real>")
            is PlistValue.Bool -> sb.append(if (value.value) "<true/>" else "<false/>")
            is PlistValue.Data -> sb.append("<data>").append(PlainBase64.encode(value.bytes)).append("</data>")
        }
    }

    private fun escape(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private sealed class Token {
        class Start(val name: String, val empty: Boolean) : Token()
        class End(val name: String) : Token()
        class Text(val text: String) : Token()
    }

    private class Tokenizer(private val s: String) {
        private var i = 0
        private var pendingEnd: String? = null

        /** Next token, or null at the end. Comments and declarations are skipped, and blank text unless [keepBlank]. */
        fun next(keepBlank: Boolean = false): Token? {
            pendingEnd?.let { pendingEnd = null; return Token.End(it) }
            while (i < s.length) {
                if (s[i] != '<') {
                    val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                    val raw = s.substring(i, end)
                    i = end
                    if (raw.isBlank() && !keepBlank) continue
                    return Token.Text(unescape(raw))
                }
                when {
                    s.startsWith("<?", i) -> i = skipTo("?>", 2)
                    s.startsWith("<!--", i) -> i = skipTo("-->", 4)
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i)
                        if (end < 0) throw PlistException("plist: unterminated CDATA")
                        val text = s.substring(i + 9, end)
                        i = end + 3
                        return Token.Text(text)
                    }
                    s.startsWith("<!", i) -> {
                        val end = s.indexOf('>', i)
                        if (end < 0 || s.substring(i, end).contains('[')) throw PlistException("plist: unsupported DOCTYPE")
                        i = end + 1
                    }
                    s.startsWith("</", i) -> {
                        val end = s.indexOf('>', i)
                        if (end < 0) throw PlistException("plist: unterminated tag")
                        val name = s.substring(i + 2, end).trim()
                        i = end + 1
                        return Token.End(name)
                    }
                    else -> return startTag()
                }
            }
            return null
        }

        private fun skipTo(marker: String, from: Int): Int {
            val end = s.indexOf(marker, i + from)
            if (end < 0) throw PlistException("plist: unterminated markup")
            return end + marker.length
        }

        private fun startTag(): Token {
            var j = i + 1
            var quote = 0.toChar()
            while (j < s.length) {
                val c = s[j]
                if (quote != 0.toChar()) {
                    if (c == quote) quote = 0.toChar()
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '>') {
                    break
                }
                j++
            }
            if (j >= s.length) throw PlistException("plist: unterminated tag")
            val body = s.substring(i + 1, j)
            i = j + 1
            val empty = body.endsWith("/")
            val name = body.removeSuffix("/").trim().takeWhile { !it.isWhitespace() }
            if (name.isEmpty()) throw PlistException("plist: empty tag name")
            if (empty) pendingEnd = name
            return Token.Start(name, empty)
        }

        private fun unescape(raw: String): String {
            if (raw.indexOf('&') < 0) return raw
            val sb = StringBuilder(raw.length)
            var k = 0
            while (k < raw.length) {
                val c = raw[k]
                if (c != '&') {
                    sb.append(c)
                    k++
                    continue
                }
                val semi = raw.indexOf(';', k)
                if (semi < 0) throw PlistException("plist: bad entity")
                when (val name = raw.substring(k + 1, semi)) {
                    "amp" -> sb.append('&')
                    "lt" -> sb.append('<')
                    "gt" -> sb.append('>')
                    "quot" -> sb.append('"')
                    "apos" -> sb.append('\'')
                    else -> {
                        val code = when {
                            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)
                            name.startsWith("#") -> name.substring(1).toIntOrNull()
                            else -> null
                        } ?: throw PlistException("plist: unknown entity &$name;")
                        if (code !in 0..0x10ffff) throw PlistException("plist: bad character reference")
                        sb.appendCodePoint(code)
                    }
                }
                k = semi + 1
            }
            return sb.toString()
        }
    }

    private class Parser(private val tokens: Tokenizer) {
        fun expectStart(name: String): Token.Start {
            val t = tokens.next()
            if (t !is Token.Start || t.name != name) throw PlistException("plist: expected <$name>")
            return t
        }

        fun expectEnd(name: String) {
            val t = tokens.next()
            if (t !is Token.End || t.name != name) throw PlistException("plist: expected </$name>")
        }

        fun value(depth: Int): PlistValue {
            val start = tokens.next() as? Token.Start ?: throw PlistException("plist: a value is missing")
            return startedValue(start, depth)
        }

        private fun startedValue(start: Token.Start, depth: Int): PlistValue {
            if (depth > PlistValue.MAX_DEPTH) throw PlistException("plist: nesting exceeds ${PlistValue.MAX_DEPTH}")
            return when (start.name) {
                "dict" -> dict(depth)
                "array" -> {
                    val items = ArrayList<PlistValue>()
                    while (true) {
                        val t = tokens.next()
                        if (t is Token.End && t.name == "array") break
                        if (t !is Token.Start) throw PlistException("plist: bad array")
                        items.add(startedValue(t, depth + 1))
                    }
                    PlistValue.Items(items)
                }
                else -> leaf(start)
            }
        }

        private fun dict(depth: Int): PlistValue.Dict {
            val entries = ArrayList<Pair<String, PlistValue>>()
            while (true) {
                val t = tokens.next()
                if (t is Token.End && t.name == "dict") break
                if (t !is Token.Start || t.name != "key" || entries.size == MAX_ENTRIES) {
                    throw PlistException("plist: bad dictionary")
                }
                val key = text(t)
                if (entries.any { it.first == key }) throw PlistException("plist: duplicate key '$key'")
                val v = tokens.next() as? Token.Start ?: throw PlistException("plist: dictionary value is missing")
                entries.add(key to startedValue(v, depth + 1))
            }
            return PlistValue.Dict(entries)
        }

        private fun leaf(start: Token.Start): PlistValue = when (start.name) {
            "string" -> PlistValue.Text(text(start))
            "integer" -> PlistValue.Integer(parseInteger(text(start).trim()))
            "real" -> PlistValue.Real(text(start).trim().toDoubleOrNull() ?: throw PlistException("plist: bad real"))
            "true" -> { empty(start); PlistValue.Bool(true) }
            "false" -> { empty(start); PlistValue.Bool(false) }
            "data" -> PlistValue.Data(PlainBase64.decode(text(start).filterNot { it.isWhitespace() }))
            else -> throw PlistException("plist: unsupported value <${start.name}>")
        }

        private fun parseInteger(s: String): Long {
            s.toLongOrNull()?.let { return it }
            // A 64-bit identifier with the high bit set prints as a large unsigned number.
            return s.toBigIntegerOrNull()
                ?.takeIf { it.signum() >= 0 && it.bitLength() <= 64 }?.toLong()
                ?: throw PlistException("plist: bad integer")
        }

        private fun empty(start: Token.Start) = expectEnd(start.name)

        /** The text of an element that holds text only, and its end tag. */
        private fun text(start: Token.Start): String {
            if (start.empty) {
                expectEnd(start.name)
                return ""
            }
            val sb = StringBuilder()
            while (true) {
                when (val t = tokens.next(keepBlank = true)) {
                    is Token.Text -> sb.append(t.text)
                    is Token.End -> {
                        if (t.name != start.name) throw PlistException("plist: bad <${start.name}>")
                        return sb.toString()
                    }
                    else -> throw PlistException("plist: <${start.name}> holds only text")
                }
            }
        }
    }
}
