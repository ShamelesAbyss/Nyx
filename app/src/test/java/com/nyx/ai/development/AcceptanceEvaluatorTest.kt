package com.nyx.ai.development

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceEvaluatorTest {
    private val featureGoal = "add a new subtract() function with tests"
    private val changeGoal = "change the greeting text to Hello"

    @Test
    fun `healthy baseline never verifies a feature`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.CREATE_FEATURE, featureGoal)
        val evidence = evidence(
            baselinePassed = true,
            changedSourceCount = 0,
            postBuildPassed = true,
            postTestsPassed = true,
        )
        assertEquals(DevelopmentOutcome.GOAL_NOT_VERIFIED, AcceptanceEvaluator.evaluate(criteria, evidence))
    }

    @Test
    fun `feature is verified only with a source change and green post checks`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.CREATE_FEATURE, featureGoal)
        val evidence = evidence(
            baselinePassed = true,
            appliedFileCount = 2,
            changedSourceCount = 2,
            postBuildPassed = true,
            postTestsPassed = true,
            testCountBaseline = 4,
            testCountAfter = 6,
        )
        assertEquals(
            DevelopmentOutcome.FEATURE_IMPLEMENTED_AND_VERIFIED,
            AcceptanceEvaluator.evaluate(criteria, evidence),
        )
    }

    @Test
    fun `feature without new test evidence is not verified`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.CREATE_FEATURE, featureGoal)
        val evidence = evidence(
            changedSourceCount = 1,
            postBuildPassed = true,
            postTestsPassed = true,
            testCountBaseline = 4,
            testCountAfter = 4,
        )
        assertEquals(DevelopmentOutcome.GOAL_NOT_VERIFIED, AcceptanceEvaluator.evaluate(criteria, evidence))
    }

    @Test
    fun `explicit acceptance check overrides missing test growth`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.CREATE_FEATURE, featureGoal)
        val evidence = evidence(
            changedSourceCount = 1,
            postBuildPassed = true,
            postTestsPassed = true,
            acceptanceCheckPassed = true,
            testCountBaseline = 4,
            testCountAfter = 4,
        )
        assertEquals(
            DevelopmentOutcome.FEATURE_IMPLEMENTED_AND_VERIFIED,
            AcceptanceEvaluator.evaluate(criteria, evidence),
        )
    }

    @Test
    fun `failed acceptance check blocks a feature`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.CREATE_FEATURE, featureGoal)
        val evidence = evidence(
            changedSourceCount = 1,
            postBuildPassed = true,
            postTestsPassed = true,
            acceptanceCheckPassed = false,
            testCountBaseline = 4,
            testCountAfter = 9,
        )
        assertEquals(DevelopmentOutcome.GOAL_NOT_VERIFIED, AcceptanceEvaluator.evaluate(criteria, evidence))
    }

    @Test
    fun `repair with failing baseline and passing post checks is verified`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.REPAIR_BUILD, "the build fails, fix it")
        val evidence = evidence(
            baselinePassed = false,
            appliedFileCount = 1,
            changedSourceCount = 1,
            postBuildPassed = true,
            postTestsPassed = true,
        )
        assertEquals(DevelopmentOutcome.REPAIR_VERIFIED, AcceptanceEvaluator.evaluate(criteria, evidence))
    }

    @Test
    fun `repair without a source change is not verified`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.REPAIR_TEST, "tests are failing")
        val evidence = evidence(
            baselinePassed = false,
            changedSourceCount = 0,
            postBuildPassed = true,
            postTestsPassed = true,
        )
        assertEquals(DevelopmentOutcome.GOAL_NOT_VERIFIED, AcceptanceEvaluator.evaluate(criteria, evidence))
    }

    @Test
    fun `healthy repair baseline is reported instead of a repair`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.REPAIR_BUILD, "fix the build")
        val evidence = evidence(
            baselinePassed = true,
            changedSourceCount = 1,
            postBuildPassed = true,
            postTestsPassed = true,
        )
        assertEquals(DevelopmentOutcome.BASELINE_HEALTHY, AcceptanceEvaluator.evaluate(criteria, evidence))
    }

    @Test
    fun `missing toolchain is an environment failure`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.REPAIR_BUILD, "fix the build")
        val evidence = evidence(
            baselinePassed = false,
            changedSourceCount = 1,
            postBuildPassed = true,
            postTestsPassed = true,
            infrastructureOk = false,
        )
        assertEquals(DevelopmentOutcome.ENVIRONMENT_UNAVAILABLE, AcceptanceEvaluator.evaluate(criteria, evidence))
    }

    @Test
    fun `human review outranks a verified feature`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.CREATE_FEATURE, featureGoal)
        val evidence = evidence(
            changedSourceCount = 1,
            postBuildPassed = true,
            postTestsPassed = true,
            testCountBaseline = 1,
            testCountAfter = 2,
            reviewRequired = true,
        )
        assertEquals(DevelopmentOutcome.HUMAN_REVIEW_REQUIRED, AcceptanceEvaluator.evaluate(criteria, evidence))
    }

    @Test
    fun `missing infrastructure outranks human review`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.CREATE_FEATURE, featureGoal)
        val evidence = evidence(infrastructureOk = false, reviewRequired = true)
        assertEquals(DevelopmentOutcome.ENVIRONMENT_UNAVAILABLE, AcceptanceEvaluator.evaluate(criteria, evidence))
    }

    @Test
    fun `diagnosis requires an explicit acceptance check`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.DIAGNOSE, "inspect this project")
        assertEquals(
            DevelopmentOutcome.GOAL_NOT_VERIFIED,
            AcceptanceEvaluator.evaluate(criteria, evidence(postBuildPassed = true, postTestsPassed = true)),
        )
        assertEquals(
            DevelopmentOutcome.FEATURE_IMPLEMENTED_AND_VERIFIED,
            AcceptanceEvaluator.evaluate(criteria, evidence(acceptanceCheckPassed = true)),
        )
    }

    @Test
    fun `artifact verification accepts build and test evidence`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.VERIFY_ARTIFACT, "verify the artifact")
        assertEquals(
            DevelopmentOutcome.FEATURE_IMPLEMENTED_AND_VERIFIED,
            AcceptanceEvaluator.evaluate(criteria, evidence(postBuildPassed = true, postTestsPassed = true)),
        )
        assertEquals(
            DevelopmentOutcome.GOAL_NOT_VERIFIED,
            AcceptanceEvaluator.evaluate(criteria, evidence(postBuildPassed = true)),
        )
    }

    @Test
    fun `exhausted budget cannot manufacture success`() {
        val criteria = AcceptanceEvaluator.criteriaFor(GoalKind.CREATE_FEATURE, featureGoal)
        val failing = evidence(changedSourceCount = 0, budgetExhausted = true)
        assertEquals(DevelopmentOutcome.GOAL_NOT_VERIFIED, AcceptanceEvaluator.evaluate(criteria, failing))
        val verified = evidence(
            changedSourceCount = 1,
            postBuildPassed = true,
            postTestsPassed = true,
            testCountBaseline = 2,
            testCountAfter = 3,
            budgetExhausted = true,
        )
        assertEquals(
            DevelopmentOutcome.FEATURE_IMPLEMENTED_AND_VERIFIED,
            AcceptanceEvaluator.evaluate(criteria, verified),
        )
    }

    @Test
    fun `criteria describe commands and obligations`() {
        val feature = AcceptanceEvaluator.criteriaFor(
            GoalKind.CREATE_FEATURE,
            "add a new Greeting.kt helper with tests",
        )
        assertEquals(setOf(ProjectRunKind.BUILD, ProjectRunKind.TEST), feature.requiredCommands)
        assertTrue(feature.requiresSourceChange)
        assertTrue(feature.requiresNewTestEvidence)
        assertEquals("Greeting.kt", feature.expectedArtifact)
        assertTrue(feature.failureConditions.isNotEmpty())

        val diagnose = AcceptanceEvaluator.criteriaFor(GoalKind.DIAGNOSE, "inspect this project")
        assertTrue(diagnose.requiredCommands.isEmpty())
        assertFalse(diagnose.requiresSourceChange)
        assertFalse(diagnose.requiresNewTestEvidence)

        val repair = AcceptanceEvaluator.criteriaFor(GoalKind.REPAIR_TEST, "tests are failing")
        assertEquals(setOf(ProjectRunKind.TEST), repair.requiredCommands)

        val modify = AcceptanceEvaluator.criteriaFor(GoalKind.MODIFY_FEATURE, changeGoal)
        assertFalse(modify.requiresNewTestEvidence)
        assertEquals(GoalKind.MODIFY_FEATURE, modify.goalKind)
        assertEquals(changeGoal, modify.requestedChange)
    }

    private fun evidence(
        baselinePassed: Boolean = false,
        appliedFileCount: Int = 0,
        changedSourceCount: Int = 0,
        postBuildPassed: Boolean = false,
        postTestsPassed: Boolean = false,
        acceptanceCheckPassed: Boolean? = null,
        testCountBaseline: Int? = null,
        testCountAfter: Int? = null,
        infrastructureOk: Boolean = true,
        budgetExhausted: Boolean = false,
        reviewRequired: Boolean = false,
    ): AcceptanceEvidence = AcceptanceEvidence(
        baselinePassed = baselinePassed,
        appliedFileCount = appliedFileCount,
        changedSourceCount = changedSourceCount,
        postBuildPassed = postBuildPassed,
        postTestsPassed = postTestsPassed,
        acceptanceCheckPassed = acceptanceCheckPassed,
        testCountBaseline = testCountBaseline,
        testCountAfter = testCountAfter,
        infrastructureOk = infrastructureOk,
        budgetExhausted = budgetExhausted,
        reviewRequired = reviewRequired,
    )
}
