package com.nyx.ai.development

/** No lenient JSONObject parsing, markdown fences, tool calls, shell commands or unknown keys. */
object DevelopmentPatchParser {
    fun parse(text: String, budget: DevelopmentBudget, remainingFiles: Int, remainingBytes: Int): DevelopmentPatch {
        require(text.length <= budget.maxResponseChars) { "Patch response exceeds response budget" }
        val root = StrictJson(text).parse() as? Map<*, *> ?: error("Patch must be a JSON object")
        require(root.keys == setOf("files")) { "Patch has unknown or missing keys" }
        val files = root["files"] as? List<*> ?: error("files must be an array")
        require(files.isNotEmpty() && files.size <= minOf(budget.maxFilesPerPatch, remainingFiles)) { "Patch file budget exceeded or empty patch" }
        val seen = mutableSetOf<String>()
        var bytes = 0L
        return DevelopmentPatch(files.map { value ->
            val file = value as? Map<*, *> ?: error("Each file must be an object")
            require(file.keys == setOf("path", "expectedSha256", "content")) { "File has unknown or missing keys" }
            val path = file["path"] as? String ?: error("path must be a string")
            val hash = file["expectedSha256"] as? String ?: error("expectedSha256 must be a string")
            val content = file["content"] as? String ?: error("content must be a string")
            require(DevelopmentPaths.isSafeRelative(path)) { "Protected, non-relative or unsafe path: $path" }
            require(seen.add(path)) { "Duplicate file path: $path" }
            require(hash == DevelopmentPaths.MISSING || (hash.length == 64 && hash.all { it in "0123456789abcdef" })) { "Invalid expectedSha256" }
            require('\u0000' !in content) { "NUL/binary content denied" }
            val size = content.toByteArray(Charsets.UTF_8).size
            require(size <= budget.maxFileBytes) { "File exceeds byte budget" }
            bytes += size
            require(bytes <= remainingBytes) { "Total edit byte budget exceeded" }
            PatchFile(path, hash, content)
        })
    }

    /** Minimal strict RFC 8259 subset: this protocol needs only objects, arrays and strings. */
    private class StrictJson(private val text: String) {
        private var index = 0
        fun parse(): Any {
            val value = value(0)
            whitespace()
            require(index == text.length) { "Trailing JSON data" }
            return value
        }
        private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
        private fun value(depth: Int): Any {
            require(depth <= 4) { "JSON nesting exceeds protocol" }
            whitespace()
            require(index < text.length) { "Unexpected end of JSON" }
            return when (text[index]) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> string()
                else -> error("Only JSON objects, arrays and strings are allowed")
            }
        }
        private fun obj(depth: Int): Map<String, Any> {
            index++
            val result = linkedMapOf<String, Any>()
            whitespace()
            if (consume('}')) return result
            do {
                whitespace()
                val key = string()
                require(key !in result) { "Duplicate JSON key: $key" }
                whitespace(); require(consume(':')) { "Missing colon" }
                result[key] = value(depth)
                whitespace()
                if (consume('}')) return result
                require(consume(',')) { "Missing comma" }
            } while (true)
        }
        private fun array(depth: Int): List<Any> {
            index++
            val result = mutableListOf<Any>()
            whitespace()
            if (consume(']')) return result
            do {
                require(result.size < 16) { "Array exceeds protocol limit" }
                result += value(depth)
                whitespace()
                if (consume(']')) return result
                require(consume(',')) { "Missing comma" }
            } while (true)
        }
        private fun consume(char: Char): Boolean = if (index < text.length && text[index] == char) { index++; true } else false
        private fun string(): String {
            require(consume('"')) { "Expected quoted JSON string" }
            val result = StringBuilder()
            while (index < text.length) {
                val ch = text[index++]
                if (ch == '"') {
                    val s = result.toString()
                    var i = 0
                    while (i < s.length) {
                        if (s[i].isHighSurrogate()) { require(i + 1 < s.length && s[i + 1].isLowSurrogate()) { "Invalid Unicode surrogate" }; i += 2 }
                        else { require(!s[i].isLowSurrogate()) { "Invalid Unicode surrogate" }; i++ }
                    }
                    return s
                }
                require(ch >= ' ') { "Unescaped control character" }
                if (ch != '\\') { result.append(ch); continue }
                require(index < text.length) { "Incomplete escape" }
                when (val escaped = text[index++]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        require(index + 4 <= text.length) { "Incomplete Unicode escape" }
                        val code = text.substring(index, index + 4)
                        require(code.all { it in "0123456789abcdefABCDEF" }) { "Invalid Unicode escape" }
                        result.append(code.toInt(16).toChar()); index += 4
                    }
                    else -> error("Invalid JSON escape")
                }
            }
            error("Unterminated string")
        }
    }
}
