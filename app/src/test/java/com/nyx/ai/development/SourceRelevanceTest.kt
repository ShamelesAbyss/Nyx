package com.nyx.ai.development

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRelevanceTest {
    private val greeting = SourceCandidate("app/src/main/java/com/nyx/Greeting.kt", 1_200)
    private val helper = SourceCandidate("app/src/main/java/com/nyx/Helper.kt", 900)
    private val nested = SourceCandidate("app/src/main/java/com/nyx/deep/pkg/Other.kt", 400)

    @Test
    fun `ranking is deterministic and ordered`() {
        val candidates = listOf(greeting, helper, nested)
        val first = SourceRelevance.rank(candidates, "change greeting text", emptyList(), "", 3)
        val second = SourceRelevance.rank(candidates, "change greeting text", emptyList(), "", 3)
        assertEquals(first, second)
        assertEquals(3, first.size)
        for (index in 1 until first.size) {
            val previous = first[index - 1]
            val current = first[index]
            assertTrue(
                "score desc then path asc",
                previous.score > current.score || (previous.score == current.score && previous.path <= current.path),
            )
        }
    }

    @Test
    fun `basename mention and identifier boost the matching file`() {
        val ranked = SourceRelevance.rank(listOf(greeting, helper), "change Greeting.kt text", emptyList(), "", 2)
        assertEquals(greeting.path, ranked.first().path)
        assertTrue(ranked.first().reasons.any { it.contains("basename") })
    }

    @Test
    fun `failure paths outrank unrelated files`() {
        val ranked = SourceRelevance.rank(listOf(greeting, helper), "fix the build", listOf(helper.path), "", 2)
        assertEquals(helper.path, ranked.first().path)
        assertTrue(ranked.first().reasons.any { it.contains("failure") })
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `same directory as a failure is boosted`() {
        val sibling = SourceCandidate("app/src/main/java/com/nyx/Other.kt", 100)
        val ranked = SourceRelevance.rank(
            listOf(nested, sibling),
            "fix the build",
            listOf(helper.path),
            "",
            2,
        )
        assertEquals(sibling.path, ranked.first().path)
        assertTrue(ranked.first().reasons.any { it.contains("directory") })
    }

    @Test
    fun `diagnostics mentions are ranked above silence`() {
        val ranked = SourceRelevance.rank(
            listOf(greeting, helper),
            "fix it",
            emptyList(),
            "error: app/src/main/java/com/nyx/Helper.kt:12 unresolved reference",
            2,
        )
        assertEquals(helper.path, ranked.first().path)
        assertTrue(ranked.first().reasons.any { it.contains("diagnostics") })
    }

    @Test
    fun `test files win for test repair goals`() {
        val testFile = SourceCandidate("app/src/test/java/com/nyx/GreetingTest.kt", 800)
        val mainFile = SourceCandidate("app/src/main/java/com/nyx/Greeting.kt", 800)
        val ranked = SourceRelevance.rank(
            listOf(mainFile, testFile),
            "tests are failing, make them pass",
            emptyList(),
            "",
            2,
        )
        assertEquals(testFile.path, ranked.first().path)
        assertTrue(ranked.first().reasons.any { it.contains("test file") })
    }

    @Test
    fun `unsafe paths are excluded`() {
        val candidates = listOf(
            SourceCandidate("../outside/Evil.kt"),
            SourceCandidate("/absolute/Evil.kt"),
            SourceCandidate(".git/config.kt"),
            SourceCandidate("build/generated/Gen.kt"),
            SourceCandidate("app/node_modules/lib/index.js"),
            greeting,
        )
        val ranked = SourceRelevance.rank(candidates, "fix the build", emptyList(), "", 10)
        assertEquals(listOf(greeting.path), ranked.map { it.path })
    }

    @Test
    fun `limit is coerced and reasons stay bounded`() {
        val candidates = (1..5).map { SourceCandidate("src/main/File$it.kt", 10) }
        assertEquals(1, SourceRelevance.rank(candidates, "fix the build", emptyList(), "", 0).size)
        assertEquals(5, SourceRelevance.rank(candidates, "fix the build", emptyList(), "", 99).size)
        for (ranked in SourceRelevance.rank(candidates, "fix the build", emptyList(), "", 5)) {
            assertTrue(ranked.reasons.isNotEmpty())
            assertTrue(ranked.reasons.size <= 4)
        }
    }

    @Test
    fun `empty inputs are handled`() {
        assertTrue(SourceRelevance.rank(emptyList(), "", emptyList(), "", 5).isEmpty())
        val ranked = SourceRelevance.rank(listOf(greeting), "", emptyList(), "", 5)
        assertEquals(1, ranked.size)
        assertTrue(ranked.first().reasons.isNotEmpty())
    }

    @Test
    fun `large files are penalized`() {
        val small = SourceCandidate("app/src/main/java/com/nyx/Small.kt", 1_000)
        val large = SourceCandidate("app/src/main/java/com/nyx/Large.kt", 200_000)
        val ranked = SourceRelevance.rank(listOf(large, small), "fix the build", emptyList(), "", 2)
        assertEquals(small.path, ranked.first().path)
        assertTrue(ranked.last().reasons.any { it.contains("large") })
    }
}
