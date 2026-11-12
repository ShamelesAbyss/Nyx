package com.nyx.ai.development

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalClassifierTest {
    @Test
    fun `build failure classifies as build repair`() {
        assertClassification(GoalKind.REPAIR_BUILD, "the build fails with a compile error, fix it")
    }

    @Test
    fun `failing tests classify as test repair`() {
        assertClassification(GoalKind.REPAIR_TEST, "tests are failing, make them pass")
    }

    @Test
    fun `new function classifies as feature creation`() {
        assertClassification(GoalKind.CREATE_FEATURE, "add a new subtract() function with tests")
    }

    @Test
    fun `text change classifies as feature modification`() {
        assertClassification(GoalKind.MODIFY_FEATURE, "change the greeting text to Hello")
    }

    @Test
    fun `inspection classifies as diagnosis`() {
        assertClassification(GoalKind.DIAGNOSE, "inspect this project and report its structure")
    }

    @Test
    fun `artifact verification classifies as verification`() {
        assertClassification(GoalKind.VERIFY_ARTIFACT, "verify the existing artifact builds")
    }

    @Test
    fun `repair wins over an incidental feature verb`() {
        assertClassification(GoalKind.REPAIR_BUILD, "add a null check because the build crashes")
    }

    @Test
    fun `classification is deterministic`() {
        val goal = "tests are failing, make them pass"
        assertEquals(GoalClassifier.classify(goal), GoalClassifier.classify(goal))
    }

    @Test
    fun `empty goal falls back to a low confidence modification`() {
        val result = GoalClassifier.classify("")
        assertEquals(GoalKind.MODIFY_FEATURE, result.kind)
        assertTrue(result.confidence in 0.0..0.5)
        assertTrue(result.reasons.isNotEmpty())
    }

    @Test
    fun `oversized goal is bounded without failing`() {
        val result = GoalClassifier.classify("add a new feature ".repeat(2_000))
        assertEquals(GoalKind.CREATE_FEATURE, result.kind)
        assertTrue(result.confidence in 0.0..1.0)
        assertTrue(result.reasons.size <= 5)
    }

    private fun assertClassification(expected: GoalKind, goal: String) {
        val result = GoalClassifier.classify(goal)
        assertEquals(goal, expected, result.kind)
        assertTrue(goal, result.confidence in 0.0..1.0)
        assertTrue(goal, result.reasons.isNotEmpty())
        assertTrue(goal, result.reasons.size <= 5)
    }
}
