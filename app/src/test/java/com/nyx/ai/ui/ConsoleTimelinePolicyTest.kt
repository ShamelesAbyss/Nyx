package com.nyx.ai.ui

import org.junit.Assert.*
import org.junit.Test

class ConsoleTimelinePolicyTest {
    @Test fun extremelyLongOutputIsFullyRetainedInBoundedRows() {
        val original = "BEGIN\n" + "a long terminal output line with spaces\n".repeat(10_000) + "END"
        val parts = consoleTextParts(original)
        assertEquals(original, parts.joinToString(""))
        assertTrue("Long output must span multiple lazy rows", parts.size > 1)
        assertTrue(parts.all { it.length <= 600 && it.count { c -> c == '\n' } <= 12 })
        assertTrue(parts.first().startsWith("BEGIN")); assertTrue(parts.last().endsWith("END"))
    }

    @Test fun whitespaceUnbrokenTextAndSupplementaryUnicodeAreNotLostOrSplit() {
        for (text in listOf("", "\n".repeat(20_000), "x".repeat(30_000), "x".repeat(599) + "🌑".repeat(1_000) + "  \n")) {
            val parts = consoleTextParts(text)
            assertEquals(text, parts.joinToString(""))
            assertTrue(parts.all { it.isNotEmpty() && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        }
    }

    @Test fun latestUsesActualSortDirectionAndHandlesEmptyOrSingleRow() {
        assertEquals(1, consoleLatestIndex(newestFirst = true, totalRows = 301))
        assertEquals(300, consoleLatestIndex(newestFirst = false, totalRows = 301))
        assertEquals(0, consoleLatestIndex(true, 0)); assertEquals(0, consoleLatestIndex(false, 0))
        assertEquals(0, consoleLatestIndex(true, 1)); assertEquals(0, consoleLatestIndex(false, 1))
    }

    @Test fun expandingAndCollapsingOneEventKeepsOtherSelections() {
        val both = toggleConsoleEvent(toggleConsoleEvent(emptyList(), "first"), "last")
        assertEquals(listOf("first", "last"), both)
        assertEquals(listOf("last"), toggleConsoleEvent(both, "first"))
        assertEquals(emptyList<String>(), toggleConsoleEvent(toggleConsoleEvent(emptyList(), "first"), "first"))
    }
}
