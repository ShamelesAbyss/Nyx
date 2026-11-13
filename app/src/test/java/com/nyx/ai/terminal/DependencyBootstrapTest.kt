package com.nyx.ai.terminal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyBootstrapTest {
    private class FakeGateway(
        private var capabilities: EnvironmentCapabilities,
        private val jdk: JdkResolution = JdkResolution("openjdk-17-jdk-headless"),
        private val report: (DependencyPlan) -> InstallationReport = { plan ->
            InstallationReport(
                steps = plan.candidates.map {
                    InstallStep(it.packageName, it.manager, it.command, "succeeded", exitCode = 0, output = "ok")
                },
                verifiedTools = plan.missing.map { it.tool }.toSet(),
                message = "All required tools are verified.",
            )
        },
    ) : DependencyGateway {
        var installCalls = 0
        var jdkCalls = 0
        var lastPlan: DependencyPlan? = null
        var lastObjectiveId: String? = null
        override suspend fun probeCapabilities(
            environment: TerminalEnvironment,
            guestAlias: String,
        ): EnvironmentCapabilities = capabilities

        override suspend fun resolveGuestJdk(guestAlias: String): JdkResolution {
            jdkCalls++
            return jdk
        }

        override suspend fun install(
            plan: DependencyPlan,
            environment: TerminalEnvironment,
            guestAlias: String,
            objectiveId: String?,
            authorize: (PackageCandidate) -> Boolean,
        ): InstallationReport {
            installCalls++
            lastPlan = plan
            lastObjectiveId = objectiveId
            return report(plan)
        }
    }

    private val workspace = "/home/nyx/project"

    private fun capability(name: String, state: CapabilityState = CapabilityState.VERIFIED) = ToolCapability(
        name = name,
        state = state,
        version = if (state == CapabilityState.VERIFIED) "1.0" else null,
        origin = if (state == CapabilityState.VERIFIED) ExecutableOrigin.GUEST_NATIVE else ExecutableOrigin.UNKNOWN,
    )

    private fun completeGuest(): EnvironmentCapabilities = EnvironmentCapabilities(
        environmentId = "proot:debian",
        kind = "proot:debian",
        checkedAt = 1L,
        tools = listOf("sh", "git", "java", "javac", "gradle", "apt").associateWith { capability(it) },
    )

    private fun request(kind: ProjectKind = ProjectKind.KOTLIN_JVM) = BootstrapRequest(
        objectiveId = "objective-1",
        projectKind = kind,
        environment = TerminalEnvironment.DEBIAN_PROOT,
        guestAlias = "debian",
        workspace = workspace,
        environmentId = "proot:debian",
    )

    @Test
    fun `already verified guest is ready without installing`() = runBlocking {
        val gateway = FakeGateway(completeGuest())
        val progress = mutableListOf<String>()
        val outcome = DependencyBootstrap.prepare(request(), gateway, authorize = { true }) { kind, _ ->
            progress += kind
        }
        assertEquals(BootstrapStatus.READY, outcome.status)
        assertEquals(0, gateway.installCalls)
        assertTrue(outcome.plan!!.missing.isEmpty())
        assertTrue(progress.contains("dependency_plan"))
        assertFalse(progress.contains("dependency_install"))
    }

    @Test
    fun `missing guest jdk is installed and verified before continuation`() = runBlocking {
        val tools = completeGuest().tools.toMutableMap()
        tools["java"] = capability("java", CapabilityState.MISSING)
        tools["javac"] = capability("javac", CapabilityState.MISSING)
        val gateway = FakeGateway(
            completeGuest().copy(tools = tools),
            jdk = JdkResolution("openjdk-21-jdk-headless", diagnostic = "Debian trixie"),
        )
        val outcome = DependencyBootstrap.prepare(request(), gateway, authorize = { true })
        assertEquals(BootstrapStatus.INSTALLED, outcome.status)
        assertEquals(1, gateway.installCalls)
        assertEquals("objective-1", gateway.lastObjectiveId)
        assertEquals(setOf("java", "javac"), gateway.lastPlan!!.missing.map { it.tool }.toSet())
        assertEquals("openjdk-21-jdk-headless", gateway.lastPlan!!.candidates.single().packageName)
        assertTrue(outcome.message.contains("original objective can continue"))
    }

    @Test
    fun `resumed planned Kotlin build prepares dependencies before continuing`() = runBlocking {
        val resumedTasks = listOf(
            PlannedDependencyTask(
                workerKind = "development",
                title = "Compile the Kotlin library",
                instructions = "Run the Gradle build and fix compilation errors.",
                verification = "Confirm the JVM build passes.",
            ),
        )
        val tools = completeGuest().tools.toMutableMap()
        tools["gradle"] = capability("gradle", CapabilityState.MISSING)
        val gateway = FakeGateway(completeGuest().copy(tools = tools))
        var resumedObjectiveMayContinue = false
        val mayExecute = ObjectiveDependencyScope.beforeExecution(resumedTasks) { projectKind ->
            assertEquals(ProjectKind.KOTLIN_JVM, projectKind)
            val outcome = DependencyBootstrap.prepare(request(projectKind), gateway, authorize = { true })
            resumedObjectiveMayContinue = outcome.status == BootstrapStatus.INSTALLED || outcome.status == BootstrapStatus.READY
            resumedObjectiveMayContinue
        }

        assertTrue(mayExecute)
        assertTrue(resumedObjectiveMayContinue)
        assertEquals(1, gateway.installCalls)
        assertEquals("objective-1", gateway.lastObjectiveId)
    }

    @Test
    fun `non JVM project does not resolve an unnecessary guest JDK`() = runBlocking {
        val tools = completeGuest().tools.toMutableMap().apply {
            put("node", capability("node", CapabilityState.MISSING))
            put("npm", capability("npm", CapabilityState.MISSING))
        }
        val gateway = FakeGateway(completeGuest().copy(tools = tools))
        val outcome = DependencyBootstrap.prepare(request(ProjectKind.NODE), gateway, authorize = { true })

        assertEquals(BootstrapStatus.INSTALLED, outcome.status)
        assertEquals(0, gateway.jdkCalls)
        assertEquals(listOf("nodejs", "npm"), gateway.lastPlan!!.candidates.map { it.packageName })
    }

    @Test
    fun `inherited termux java does not satisfy a guest requirement`() = runBlocking {
        val tools = completeGuest().tools.toMutableMap()
        tools["java"] = capability("java").copy(origin = ExecutableOrigin.GUEST_INHERITED_TERMUX)
        tools["javac"] = capability("javac").copy(origin = ExecutableOrigin.GUEST_INHERITED_TERMUX)
        val gateway = FakeGateway(completeGuest().copy(tools = tools))
        val outcome = DependencyBootstrap.prepare(request(), gateway, authorize = { true })
        assertEquals(BootstrapStatus.INSTALLED, outcome.status)
        assertEquals(setOf("java", "javac"), gateway.lastPlan!!.missing.map { it.tool }.toSet())
    }

    @Test
    fun `approval blocked plan is reported and never installed`() = runBlocking {
        val tools = completeGuest().tools.toMutableMap()
        tools["gradle"] = capability("gradle", CapabilityState.MISSING)
        val gateway = FakeGateway(
            completeGuest().copy(tools = tools),
            report = { plan ->
                InstallationReport(
                    needsApproval = plan.candidates,
                    message = "Approval is required before installing ${plan.candidates.size} package(s).",
                )
            },
        )
        val outcome = DependencyBootstrap.prepare(request(), gateway, authorize = { false })
        assertEquals(BootstrapStatus.APPROVAL_REQUIRED, outcome.status)
        assertEquals(1, gateway.installCalls)
        assertTrue(outcome.message.contains("Approval is required"))
    }

    @Test
    fun `missing guest apt is a blocker and never runs an install`() = runBlocking {
        val tools = completeGuest().tools.toMutableMap()
        tools["gradle"] = capability("gradle", CapabilityState.MISSING)
        tools.remove("apt")
        val gateway = FakeGateway(completeGuest().copy(tools = tools))
        val outcome = DependencyBootstrap.prepare(request(), gateway, authorize = { true })
        assertEquals(BootstrapStatus.BLOCKED, outcome.status)
        assertEquals(0, gateway.installCalls)
        assertTrue(outcome.message.contains("apt"))
    }

    @Test
    fun `unresolved jdk candidate is a diagnostic not a guessed install`() = runBlocking {
        val tools = completeGuest().tools.toMutableMap()
        tools["java"] = capability("java", CapabilityState.MISSING)
        tools["javac"] = capability("javac", CapabilityState.MISSING)
        val gateway = FakeGateway(
            completeGuest().copy(tools = tools),
            jdk = JdkResolution(null, diagnostic = "No JDK candidate was reported by APT."),
        )
        val outcome = DependencyBootstrap.prepare(request(), gateway, authorize = { true })
        assertEquals(BootstrapStatus.BLOCKED, outcome.status)
        assertEquals(0, gateway.installCalls)
        assertTrue(outcome.message.contains("JDK"))
    }

    @Test
    fun `invalid workspace is rejected before any probe`() = runBlocking {
        val gateway = FakeGateway(completeGuest())
        val outcome = DependencyBootstrap.prepare(request().copy(workspace = "relative/project"), gateway, authorize = { true })
        assertEquals(BootstrapStatus.BLOCKED, outcome.status)
        assertEquals(0, gateway.installCalls)
    }
}
