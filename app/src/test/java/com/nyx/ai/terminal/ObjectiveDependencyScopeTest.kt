package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ObjectiveDependencyScopeTest {
    @Test
    fun `ordinary shell automation never selects a development toolchain`() {
        val kind = ObjectiveDependencyScope.requiredProjectKind(
            listOf(
                PlannedDependencyTask(
                    workerKind = "terminal",
                    title = "Automate the workspace backup",
                    instructions = "Write and run a POSIX shell script; preserve existing data.",
                    verification = "Run the script and inspect its status.",
                ),
            ),
        )
        assertNull(kind)
    }

    @Test
    fun `negated toolchain words in a planned shell task do not trigger bootstrap`() {
        val kind = ObjectiveDependencyScope.requiredProjectKind(
            listOf(
                PlannedDependencyTask(
                    workerKind = "terminal",
                    title = "Keep this as shell automation",
                    instructions = "Do not use Android SDK, Gradle, Kotlin, Python, or Node; use POSIX shell only.",
                    verification = "Confirm the shell script works without those development tools.",
                ),
            ),
        )
        assertNull(kind)
    }

    @Test
    fun `shell automation bypasses the dependency bootstrap callback`() = runBlocking {
        val tasks = listOf(
            PlannedDependencyTask(
                workerKind = "terminal",
                title = "Run shell automation",
                instructions = "Execute a POSIX shell script in the authorized workspace.",
                verification = "Check the output file.",
            ),
        )
        var bootstrapCalled = false
        val mayProceed = ObjectiveDependencyScope.beforeExecution(tasks) {
            bootstrapCalled = true
            false
        }
        assertTrue(mayProceed)
        assertEquals(false, bootstrapCalled)
    }

    @Test
    fun `affirmative Android development task selects the Android toolchain`() {
        val kind = ObjectiveDependencyScope.requiredProjectKind(
            listOf(
                PlannedDependencyTask(
                    workerKind = "development",
                    title = "Build an Android application",
                    instructions = "Compile the Android APK using Kotlin and Gradle.",
                    verification = "Verify the Android APK output.",
                ),
            ),
        )
        assertEquals(ProjectKind.ANDROID_APP, kind)
    }

    @Test
    fun `non-executable planned work does not trigger dependency bootstrap`() {
        val kind = ObjectiveDependencyScope.requiredProjectKind(
            listOf(
                PlannedDependencyTask(
                    workerKind = "inference",
                    title = "Discuss Android build options",
                    instructions = "Explain the tradeoffs of Gradle and the Android SDK.",
                    verification = "Return a concise comparison.",
                ),
                PlannedDependencyTask(
                    workerKind = "verification",
                    title = "Check the plan",
                    instructions = "Review the response only.",
                    verification = "Confirm the explanation covers the question.",
                ),
            ),
        )
        assertNull(kind)
    }
}
