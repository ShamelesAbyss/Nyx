package com.nyx.ai.ui

/** UI-only paging: retain all characters, but keep each lazy text row within a modest size. */
internal fun consoleTextParts(text: String): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = start
        var lines = 0
        while (end < text.length && end - start < 600 && lines < 12) {
            if (text[end] == '\n') lines++
            end++
        }
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        parts += text.substring(start, end)
        start = end
    }
    return parts
}

/** Operations are newest-first; chronological event views are newest-last. Row zero is context. */
internal fun consoleLatestIndex(newestFirst: Boolean, totalRows: Int): Int =
    if (newestFirst) minOf(1, (totalRows - 1).coerceAtLeast(0)) else (totalRows - 1).coerceAtLeast(0)

internal fun toggleConsoleEvent(expanded: List<String>, id: String): List<String> =
    if (id in expanded) expanded - id else expanded + id
