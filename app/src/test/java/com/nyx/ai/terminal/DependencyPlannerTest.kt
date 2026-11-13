package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyPlannerTest {

    private val debian = "proot:debian"
    private val host = "termux_host"

    private fun guestTool(origin: ExecutableOrigin = ExecutableOrigin.GUEST_NATIVE) =
        ToolState(present = true, usable = true, origin = origin)

    private fun hostTool() = ToolState(present = true, usable = true, origin = ExecutableOrigin.TERMUX_HOST)

    private fun guestTools(vararg names: String): Map<String, ToolState> =
        names.associateWith { guestTool() } + ("apt" to guestTool())

    @Test
    fun `inherited termux git does not block a generic shell objective`() {
        val plan = DependencyPlanner.plan(
            ProjectKind.GENERIC,
            debian,
            mapOf(
                "sh" to guestTool(),
                "git" to guestTool(ExecutableOrigin.GUEST_INHERITED_TERMUX),
                "apt" to guestTool(),
            ),
        )
        assertEquals(listOf("sh"), plan.satisfied)
        assertTrue(plan.missing.isEmpty())
        assertTrue(plan.candidates.isEmpty())
        assertTrue(plan.blockers.isEmpty())
        assertTrue(plan.canProceed)
    }

    @Test
    fun `termux host origin tools never count inside a guest`() {
        val plan = DependencyPlanner.plan(
            ProjectKind.GENERIC,
            debian,
            mapOf("sh" to guestTool(), "git" to hostTool(), "apt" to guestTool()),
        )
        assertTrue(plan.missing.isEmpty())
        assertFalse(plan.satisfied.contains("git"))
    }

    @Test
    fun `present but not executable tools stay missing`() {
        val plan = DependencyPlanner.plan(
            ProjectKind.NODE,
            debian,
            mapOf(
                "sh" to guestTool(),
                "node" to ToolState(present = true, usable = false, origin = ExecutableOrigin.GUEST_NATIVE),
                "apt" to guestTool(),
            ),
        )
        assertEquals(listOf("node", "npm"), plan.missing.map { it.tool })
        assertEquals(listOf("nodejs", "npm"), plan.candidates.map { it.packageName })
    }

    @Test
    fun `guest plans never propose pkg commands`() {
        val plan = DependencyPlanner.plan(
            ProjectKind.KOTLIN_JVM,
            debian,
            mapOf("sh" to guestTool(), "apt" to guestTool()),
            guestJdkPackage = "openjdk-17-jdk-headless",
        )
        assertEquals(listOf("openjdk-17-jdk-headless", "gradle"), plan.candidates.map { it.packageName })
        assertTrue(plan.candidates.all { it.manager == "apt" })
        assertTrue(plan.candidates.none { it.command.contains("pkg ") })
        assertTrue(plan.candidates.all { it.command.startsWith("apt-get install -y --no-install-recommends ") })
        assertEquals(listOf("apt", "apt"), plan.candidates.map { it.manager })
    }

    @Test
    fun `host plans never propose apt and use termux packages`() {
        val plan = DependencyPlanner.plan(ProjectKind.KOTLIN_JVM, host, mapOf("sh" to hostTool()))
        assertEquals(listOf("java", "javac", "gradle"), plan.missing.map { it.tool })
        assertEquals(listOf("openjdk-17", "gradle"), plan.candidates.map { it.packageName })
        assertEquals("pkg", plan.candidates.first().manager)
        assertEquals("pkg install -y openjdk-17", plan.candidates.first().command)
        assertEquals("Termux official repository", plan.candidates.first().note)
        assertTrue(plan.candidates.none { it.manager == "apt" })
        assertTrue(plan.candidates.none { it.command.contains("apt-get") })
        assertTrue(plan.canProceed)
    }

    @Test
    fun `kotlin jvm guest with only java missing plans the headless jdk`() {
        val plan = DependencyPlanner.plan(
            ProjectKind.KOTLIN_JVM,
            debian,
            guestTools("sh", "javac", "gradle", "git"),
            guestJdkPackage = "openjdk-17-jdk-headless",
        )
        assertEquals(listOf("java"), plan.missing.map { it.tool })
        assertEquals("openjdk-17-jdk-headless", plan.candidates.single().packageName)
        assertTrue(plan.satisfied.containsAll(listOf("sh", "javac", "gradle", "git")))
        assertTrue(plan.blockers.isEmpty())
        assertTrue(plan.canProceed)
    }

    @Test
    fun `a fully satisfied host plan can proceed without candidates`() {
        val tools = listOf("sh", "java", "javac", "gradle").associateWith { hostTool() }
        val plan = DependencyPlanner.plan(ProjectKind.KOTLIN_JVM, host, tools)
        assertEquals(listOf("sh", "java", "javac", "gradle"), plan.satisfied)
        assertTrue(plan.missing.isEmpty())
        assertTrue(plan.candidates.isEmpty())
        assertTrue(plan.blockers.isEmpty())
        assertTrue(plan.canProceed)
    }

    @Test
    fun `android app in a guest is blocked and offers no sdk package`() {
        val plan = DependencyPlanner.plan(ProjectKind.ANDROID_APP, debian, guestTools("sh", "java", "javac", "gradle"))
        val expected = "Android SDK builds require the Termux host or a desktop toolchain; " +
            "this PRoot guest cannot run sdkmanager."
        assertEquals(listOf(expected), plan.blockers)
        assertEquals(listOf("sdkmanager", "adb"), plan.missing.map { it.tool })
        assertTrue(plan.candidates.isEmpty())
        assertTrue(plan.candidates.none { it.packageName == "android-sdk" })
        assertFalse(plan.canProceed)
    }

    @Test
    fun `android app on the host proposes android sdk once for sdkmanager and adb`() {
        val tools = listOf("sh", "java", "javac", "gradle").associateWith { hostTool() }
        val plan = DependencyPlanner.plan(ProjectKind.ANDROID_APP, host, tools)
        assertEquals(listOf("sdkmanager", "adb"), plan.missing.map { it.tool })
        assertEquals(1, plan.candidates.size)
        assertEquals("android-sdk", plan.candidates.single().packageName)
        assertEquals("pkg install -y android-sdk", plan.candidates.single().command)
        assertTrue(plan.blockers.isEmpty())
        assertTrue(plan.canProceed)
    }

    @Test
    fun `guest native sdkmanager evidence lifts the android blocker`() {
        val tools = guestTools("sh", "java", "javac", "gradle", "sdkmanager", "adb")
        val plan = DependencyPlanner.plan(ProjectKind.ANDROID_APP, debian, tools)
        assertTrue(plan.missing.isEmpty())
        assertTrue(plan.blockers.isEmpty())
        assertTrue(plan.canProceed)
    }

    @Test
    fun `guest without apt is blocked when a tool is missing`() {
        val plan = DependencyPlanner.plan(ProjectKind.NODE, debian, mapOf("sh" to guestTool()))
        assertEquals(listOf("The guest package manager (apt) is unavailable."), plan.blockers)
        assertEquals(listOf("node", "npm"), plan.missing.map { it.tool })
        assertEquals(listOf("nodejs", "npm"), plan.candidates.map { it.packageName })
        assertTrue(plan.candidates.all { it.command.startsWith("apt-get install") })
        assertFalse(plan.canProceed)
    }

    @Test
    fun `unknown environment has no trusted package source`() {
        val plan = DependencyPlanner.plan(
            ProjectKind.NODE,
            "container:alpine",
            mapOf("sh" to ToolState(present = true, usable = true, origin = ExecutableOrigin.UNKNOWN)),
        )
        assertEquals(listOf("sh"), plan.satisfied)
        assertEquals(listOf("node", "npm"), plan.missing.map { it.tool })
        assertTrue(plan.candidates.isEmpty())
        assertEquals(2, plan.blockers.size)
        assertTrue(plan.blockers.all { it.contains("container:alpine") })
        assertTrue(plan.blockers.any { it.contains("'node'") })
        assertFalse(plan.canProceed)
    }

    @Test
    fun `missing shell is a blocker without an install candidate`() {
        val plan = DependencyPlanner.plan(ProjectKind.GENERIC, host, mapOf("git" to hostTool()))
        assertEquals(listOf("sh"), plan.missing.map { it.tool })
        assertTrue(plan.candidates.isEmpty())
        assertTrue(plan.blockers.single().contains("'sh'"))
        assertFalse(plan.canProceed)
    }

    @Test
    fun `gcc satisfies the c cpp compiler requirement when clang is absent`() {
        val plan = DependencyPlanner.plan(ProjectKind.C_CPP, debian, guestTools("sh", "gcc", "make", "cmake", "git"))
        assertTrue(plan.missing.isEmpty())
        assertTrue(plan.candidates.isEmpty())
        assertTrue(plan.satisfied.contains("gcc"))
        assertFalse(plan.satisfied.contains("clang"))
        assertTrue(plan.canProceed)
    }

    @Test
    fun `c cpp without either compiler proposes clang only`() {
        val plan = DependencyPlanner.plan(ProjectKind.C_CPP, debian, guestTools("sh", "make", "cmake", "git"))
        assertEquals(listOf("clang"), plan.missing.map { it.tool })
        assertEquals("clang", plan.candidates.single().packageName)
        assertTrue(plan.candidates.none { it.packageName == "gcc" })
        assertTrue(plan.canProceed)
    }

    @Test
    fun `requirements list alternatives and optional tools in order`() {
        assertEquals(
            listOf("sh", "clang", "gcc", "make", "cmake", "git"),
            DependencyPlanner.requiredTools(ProjectKind.C_CPP).map { it.tool },
        )
        assertFalse(DependencyPlanner.requiredTools(ProjectKind.C_CPP).single { it.tool == "gcc" }.required)
        assertFalse(DependencyPlanner.requiredTools(ProjectKind.KOTLIN_JVM).single { it.tool == "git" }.required)
        assertFalse(DependencyPlanner.requiredTools(ProjectKind.NODE).single { it.tool == "git" }.required)
        assertFalse(DependencyPlanner.requiredTools(ProjectKind.C_CPP).single { it.tool == "git" }.required)
        assertFalse(DependencyPlanner.requiredTools(ProjectKind.PYTHON).single { it.tool == "git" }.required)
        assertFalse(DependencyPlanner.requiredTools(ProjectKind.GENERIC).single { it.tool == "git" }.required)
        assertEquals(
            listOf("sh", "python3", "pip3", "git"),
            DependencyPlanner.requiredTools(ProjectKind.PYTHON).map { it.tool },
        )
        assertTrue(DependencyPlanner.requiredTools(ProjectKind.PYTHON).all { it.reason.isNotBlank() })
        assertEquals(listOf("sh", "git"),
            DependencyPlanner.requiredTools(ProjectKind.GENERIC).map { it.tool },
        )
    }

    @Test
    fun `python plan shares the python package across python3 and pip3`() {
        val plan = DependencyPlanner.plan(ProjectKind.PYTHON, host, mapOf("sh" to hostTool()))
        assertEquals(listOf("python3", "pip3"), plan.missing.map { it.tool })
        assertEquals(listOf("python"), plan.candidates.map { it.packageName })
        assertEquals(plan.candidates.map { it.command }.distinct(), plan.candidates.map { it.command })
        assertTrue(plan.canProceed)
    }

    @Test
    fun `npm on the host points at the nodejs lts package that bundles it`() {
        val plan = DependencyPlanner.plan(ProjectKind.NODE, host, mapOf("sh" to hostTool(), "git" to hostTool()))
        assertEquals(listOf("node", "npm"), plan.missing.map { it.tool })
        val candidate = plan.candidates.single()
        assertEquals("nodejs-lts", candidate.packageName)
        assertEquals("pkg install -y nodejs-lts", candidate.command)
        assertTrue(candidate.note.orEmpty().contains("npm"))
        assertTrue(plan.canProceed)
    }

    @Test
    fun `project kind is inferred from case insensitive goal keywords`() {
        assertEquals(ProjectKind.ANDROID_APP, DependencyPlanner.inferProjectKind("Build an Android app with Kotlin"))
        assertEquals(ProjectKind.ANDROID_APP, DependencyPlanner.inferProjectKind("ANDROID APK release build"))
        assertEquals(ProjectKind.KOTLIN_JVM, DependencyPlanner.inferProjectKind("compile a Kotlin library"))
        assertEquals(ProjectKind.KOTLIN_JVM, DependencyPlanner.inferProjectKind("run the gradle build"))
        assertEquals(ProjectKind.C_CPP, DependencyPlanner.inferProjectKind("fix the C++ parser"))
        assertEquals(ProjectKind.C_CPP, DependencyPlanner.inferProjectKind("configure a CMake native library"))
        assertEquals(ProjectKind.NODE, DependencyPlanner.inferProjectKind("add a TypeScript React frontend"))
        assertEquals(ProjectKind.NODE, DependencyPlanner.inferProjectKind("npm install fails"))
        assertEquals(ProjectKind.PYTHON, DependencyPlanner.inferProjectKind("deploy the Django service"))
        assertEquals(ProjectKind.PYTHON, DependencyPlanner.inferProjectKind("flask route returns 500"))
        assertEquals(ProjectKind.GENERIC, DependencyPlanner.inferProjectKind("write release notes"))
        assertEquals(ProjectKind.GENERIC, DependencyPlanner.inferProjectKind("   "))
        assertEquals(ProjectKind.GENERIC, DependencyPlanner.inferProjectKind("Automate shell backups; do not install Gradle or the Android SDK."))
        assertEquals(ProjectKind.GENERIC, DependencyPlanner.inferProjectKind("Do not build an Android application or use Kotlin; write a shell script only."))
        assertEquals(ProjectKind.ANDROID_APP, DependencyPlanner.inferProjectKind("Build an Android application; use Kotlin and Gradle."))
    }

    @Test
    fun `plan preserves requirement order and echoes the project kind`() {
        val plan = DependencyPlanner.plan(ProjectKind.NODE, debian, guestTools("sh", "node", "npm", "git"))
        assertEquals(ProjectKind.NODE, plan.projectKind)
        assertEquals(listOf("sh", "node", "npm", "git"), plan.requirements.map { it.tool })
        assertEquals(listOf("sh", "node", "npm", "git"), plan.satisfied)
        assertTrue(plan.candidates.isEmpty())
        assertTrue(plan.canProceed)
    }

    @Test
    fun `trixie plan uses the resolved openjdk 21 package instead of hardcoded 17`() {
        val plan = DependencyPlanner.plan(
            ProjectKind.KOTLIN_JVM,
            debian,
            guestTools("sh", "git"),
            guestJdkPackage = "openjdk-21-jdk-headless",
            jdkDiagnostic = "Resolved openjdk-21-jdk-headless for debian/13/trixie.",
        )
        assertEquals(listOf("openjdk-21-jdk-headless", "gradle"), plan.candidates.map { it.packageName })
        assertTrue(plan.candidates.all { it.manager == "apt" })
        assertTrue(plan.blockers.isEmpty())
        assertTrue(plan.canProceed)
    }

    @Test
    fun `unresolved guest jdk is blocked instead of blindly installing 17`() {
        val plan = DependencyPlanner.plan(
            ProjectKind.KOTLIN_JVM,
            debian,
            guestTools("sh", "git"),
            jdkDiagnostic = "No installable JDK package was found. The APT sources may be stale.",
        )
        assertTrue(plan.candidates.none { it.packageName.startsWith("openjdk-") })
        assertTrue(plan.candidates.any { it.packageName == "gradle" })
        assertTrue(plan.blockers.any { it.contains("No installable JDK") })
        assertFalse(plan.canProceed)
    }
}
