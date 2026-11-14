package com.nyx.ai.workbench

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Conservative supported subsets. Validation never executes active content. */
object ArtifactFormats {
    fun validate(format: ArtifactFormat, bytes: ByteArray): ValidationResult = try {
        require(bytes.isNotEmpty()) { "Empty artifact" }
        require(bytes.size.toLong() <= format.maxBytes) { "Artifact exceeds format byte limit" }
        when (format) {
            ArtifactFormat.PDF -> error("PDF deferred: no reliable PDF generator and parser available")
            ArtifactFormat.ZIP -> ZipBundleWriter.validate(bytes)
            else -> {
                val text = utf8(bytes)
                require(text.isNotBlank()) { "Blank document" }
                require('\u0000' !in text) { "NUL is not allowed in text documents" }
                when (format) {
                    ArtifactFormat.CSV -> validateCsv(text)
                    ArtifactFormat.JSON -> {
                        // org.json is intentionally preceded by strict JSON grammar: Android's
                        // parser accepts single quotes, trailing commas and non-JSON values.
                        StrictJson(text).parse()
                        if (text.trimStart().startsWith("{")) JSONObject(text) else JSONArray(text)
                    }
                    ArtifactFormat.HTML -> validateHtml(text)
                    else -> Unit
                }
            }
        }
        ValidationResult(true, "Validated ${format.name.lowercase()} supported format")
    } catch (e: Exception) {
        ValidationResult(false, (e.message ?: "Invalid artifact").take(512))
    }

    fun requireValid(format: ArtifactFormat, bytes: ByteArray): ValidationResult =
        validate(format, bytes).also { require(it.valid) { it.note } }

    fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    internal fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun validateCsv(text: String) {
        var width: Int? = null
        var columns = 0
        var rows = 0
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var i = 0
        fun endField() {
            if (rows == 0) require(field.isNotBlank()) { "CSV requires nonempty header fields" }
            columns++; field.setLength(0); closed = false
        }
        fun endRow() {
            endField()
            if (width == null) width = columns
            require(columns == width) { "CSV inconsistent column count" }
            rows++; columns = 0
        }
        while (i < text.length) {
            val c = text[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < text.length && text[i] == '"') { field.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else field.append(c)
            } else when (c) {
                '"' -> { require(field.isEmpty() && !closed) { "CSV quote inside unquoted field" }; quoted = true }
                ',' -> endField()
                '\r', '\n' -> { if (c == '\r') { require(i < text.length && text[i] == '\n') { "CSV bare CR" }; i++ }; endRow() }
                else -> { require(!closed) { "CSV text after closing quote" }; field.append(c) }
            }
        }
        require(!quoted) { "CSV unterminated quote" }
        if (field.isNotEmpty() || columns > 0 || closed) endRow()
        require(rows > 0) { "CSV requires a header" }
    }

    private fun validateHtml(text: String) {
        val doc = text.trim()
        val doctype = Regex("^<!doctype\\s+html\\s*>", RegexOption.IGNORE_CASE).find(doc)
        require(doctype != null) { "Standalone HTML requires HTML doctype" }
        val allowed = setOf("html", "head", "title", "meta", "body", "main", "section", "article", "header", "footer", "nav", "div", "span", "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "pre", "code", "blockquote", "a", "strong", "em", "b", "i", "u", "s", "small", "sub", "sup", "br", "hr")
        val voids = setOf("meta", "br", "hr")
        val stack = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var pos = doctype!!.range.last + 1
        var rootClosed = false
        var tagCount = 0
        while (pos < doc.length) {
            if (doc.startsWith("<!--", pos)) {
                val end = doc.indexOf("-->", pos + 4)
                require(end >= 0 && "--" !in doc.substring(pos + 4, end)) { "Invalid HTML comment" }
                pos = end + 3; continue
            }
            if (doc[pos] != '<') {
                val end = doc.indexOf('<', pos).let { if (it < 0) doc.length else it }
                val body = doc.substring(pos, end)
                require(stack.isNotEmpty() || body.isBlank()) { "HTML text outside root" }
                validateEntities(body)
                pos = end; continue
            }
            val end = findTagEnd(doc, pos)
            val inside = doc.substring(pos + 1, end)
            require(++tagCount <= 100_000 && stack.size <= 64) { "HTML structure exceeds supported bounds" }
            if (inside.startsWith("/")) {
                val tag = inside.drop(1).trim().lowercase()
                require(tag.matches(Regex("[a-z][a-z0-9]*")) && stack.lastOrNull() == tag) { "HTML unbalanced closing tag" }
                stack.removeAt(stack.lastIndex)
                if (tag == "html") rootClosed = true
            } else {
                require(!rootClosed && !inside.endsWith("/")) { "Unsupported HTML self-closing markup" }
                val m = Regex("^([A-Za-z][A-Za-z0-9]*)([\\s\\S]*)$").matchEntire(inside)
                    ?: error("Invalid HTML tag")
                val tag = m.groupValues[1].lowercase()
                require(tag in allowed) { "Unsupported or active HTML tag: $tag" }
                if (tag == "html") require(stack.isEmpty() && seen.add(tag)) { "Duplicate HTML root" }
                else require(stack.isNotEmpty()) { "HTML tag outside root" }
                if (tag == "head" || tag == "body") {
                    require(stack.lastOrNull() == "html" && seen.add(tag)) { "Invalid HTML document structure" }
                    if (tag == "body") require("head" in seen) { "HTML head must precede body" }
                } else if (tag != "html") {
                    require(stack.lastOrNull() != "html") { "HTML content must be in head or body" }
                    if ("head" in stack) require(tag in setOf("title", "meta")) { "Unsupported HTML head content" }
                    if (tag == "title" || tag == "meta") require(stack.lastOrNull() == "head") { "Head tag outside head" }
                }
                validateAttributes(tag, m.groupValues[2])
                if (tag !in voids) stack.add(tag)
            }
            pos = end + 1
        }
        require(stack.isEmpty() && rootClosed && seen.containsAll(setOf("html", "head", "body"))) { "Incomplete standalone HTML document" }
    }

    private fun findTagEnd(text: String, start: Int): Int {
        var quote: Char? = null
        for (i in start + 1 until text.length) {
            val c = text[i]
            if (quote != null) { if (c == quote) quote = null }
            else if (c == '\'' || c == '"') quote = c
            else if (c == '>') return i
            else require(c != '<') { "HTML nested tag opener" }
        }
        error("Unterminated HTML tag")
    }

    private fun validateAttributes(tag: String, raw: String) {
        var p = 0
        val names = mutableSetOf<String>()
        val attribute = Regex("([A-Za-z][A-Za-z0-9-]*)\\s*=\\s*([\"'])(.*?)\\2", RegexOption.DOT_MATCHES_ALL)
        while (p < raw.length) {
            require(raw[p].isWhitespace()) { "HTML attributes require whitespace" }
            while (p < raw.length && raw[p].isWhitespace()) p++
            if (p == raw.length) break
            val m = attribute.find(raw, p)
            require(m != null && m.range.first == p) { "HTML attributes must be quoted" }
            val name = m!!.groupValues[1].lowercase()
            val value = m.groupValues[3]
            require(names.add(name)) { "Duplicate HTML attribute" }
            val global = setOf("id", "class", "title", "lang", "dir", "role", "aria-label")
            require(name in global || (tag == "a" && name == "href") ||
                (tag == "meta" && name == "charset") ||
                (tag in setOf("th", "td") && name in setOf("colspan", "rowspan", "scope"))) { "Unsupported or active HTML attribute: $name" }
            validateEntities(value)
            require('<' !in value && '`' !in value) { "Unsafe HTML attribute" }
            if (name == "href") {
                // No entity or percent decoding ambiguity; links are inert fragment references
                // or visibly plain HTTP(S) citations. No loaded external resources are allowed.
                require(value.matches(Regex("#[A-Za-z0-9._-]*|https?://[A-Za-z0-9][^\\s\\u0000-\\u001f]*")) &&
                    '&' !in value && '%' !in value && '\\' !in value) { "Unsupported HTML URL" }
            }
            if (name == "charset") require(value.equals("utf-8", true)) { "HTML charset must be UTF-8" }
            p = m.range.last + 1
        }
    }

    private fun validateEntities(value: String) {
        val entity = Regex("&(?:amp|lt|gt|quot|apos|nbsp|#(?:[0-9]{1,7}|x[0-9A-Fa-f]{1,6}));")
        var pos = value.indexOf('&')
        while (pos >= 0) {
            val m = entity.find(value, pos)
            require(m != null && m.range.first == pos) { "Unescaped or unsupported HTML entity" }
            pos = value.indexOf('&', m!!.range.last + 1)
        }
    }
}

/** Bounded recursive-descent RFC 8259 document syntax, with duplicate-key rejection. */
internal class StrictJson(private val s: String) {
    private var p = 0
    private var values = 0
    fun parse() {
        ws(); require(p < s.length && s[p] in "{[") { "JSON artifact must be an object or array" }
        value(0); ws(); require(p == s.length) { "Trailing JSON content" }
    }
    private fun ws() { while (p < s.length && s[p] in " \t\r\n") p++ }
    private fun take(c: Char): Boolean { ws(); if (p < s.length && s[p] == c) { p++; return true }; return false }
    private fun value(depth: Int) {
        require(depth <= 64 && ++values <= 100_000) { "JSON structure exceeds limit" }; ws(); require(p < s.length) { "Incomplete JSON" }
        when (s[p]) {
            '{' -> {
                p++; val keys = mutableSetOf<String>()
                if (take('}')) return
                do { ws(); val key = string(); require(keys.add(key)) { "Duplicate JSON key" }; require(take(':')); value(depth + 1) } while (take(','))
                require(take('}')) { "Invalid JSON object" }
            }
            '[' -> { p++; if (take(']')) return; do { value(depth + 1) } while (take(',')); require(take(']')) { "Invalid JSON array" } }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val m = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(s, p)
                require(m != null && m.range.first == p) { "Invalid JSON value" }; p = m!!.range.last + 1
            }
        }
    }
    private fun literal(v: String) { require(s.startsWith(v, p)); p += v.length }
    private fun string(): String {
        require(p < s.length && s[p++] == '"') { "JSON requires quoted strings" }
        val b = StringBuilder()
        while (p < s.length) {
            val c = s[p++]
            if (c == '"') {
                val text = b.toString()
                var i = 0
                while (i < text.length) {
                    val ch = text[i++]
                    if (Character.isHighSurrogate(ch)) require(i < text.length && Character.isLowSurrogate(text[i++])) { "Unpaired JSON surrogate" }
                    else require(!Character.isLowSurrogate(ch)) { "Unpaired JSON surrogate" }
                }
                return text
            }
            require(c >= ' ') { "JSON control character" }
            if (c != '\\') b.append(c) else {
                require(p < s.length); when (val e = s[p++]) {
                    '"', '\\', '/' -> b.append(e)
                    'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                    'u' -> { require(p + 4 <= s.length); val h = s.substring(p, p + 4); require(h.all { it in "0123456789abcdefABCDEF" }); b.append(h.toInt(16).toChar()); p += 4 }
                    else -> error("Invalid JSON escape")
                }
            }
        }
        error("Unterminated JSON string")
    }
}
