package com.nyx.ai.terminal

/**
 * The kind of project a user goal describes. It selects the toolchain that a build needs, so it is
 * inferred from goal text and never from the tools that happen to be installed.
 */
enum class ProjectKind {
    /** A Kotlin/JVM project built with Gradle on the JVM. */
    KOTLIN_JVM,

    /** An Android application that needs the Android SDK and device tooling. */
    ANDROID_APP,

    /** A C or C++ project built with clang/gcc, make and CMake. */
    C_CPP,

    /** A Node.js or TypeScript project built with npm. */
    NODE,

    /** A Python project built with python3 and pip. */
    PYTHON,

    /** A project with no specific toolchain, typically scripts and version control. */
    GENERIC,
}

/**
 * Observed state of one tool in one environment.
 *
 * @param present a file was found for the tool.
 * @param usable the file exists and the probe could execute it.
 * @param origin where the executable came from, as classified by [ExecutableClassifier].
 * @param version bounded version text reported by the probe, or null when no version was observed.
 */
data class ToolState(
    val present: Boolean,
    val usable: Boolean,
    val origin: ExecutableOrigin = ExecutableOrigin.UNKNOWN,
    val version: String? = null,
)

/**
 * One tool a project kind needs.
 *
 * @param tool stable tool token, for example `git` or `pip3`.
 * @param reason why the project kind needs the tool, shown to the user.
 * @param required false for optional tools and documented alternatives; an absent optional tool is
 *   never reported as missing, never proposed as a package, and never blocks the plan.
 */
data class ToolRequirement(val tool: String, val reason: String, val required: Boolean = true)

/**
 * One install candidate for a missing tool.
 *
 * @param environment environment kind the command belongs to, for example `proot:debian`.
 * @param manager package manager name, `apt` inside a guest and `pkg` on the Termux host.
 * @param packageName distribution package that provides the tool.
 * @param command exact install command for [manager].
 * @param trusted true only for an official distribution or Termux official repository package.
 * @param note provenance or bundling detail, or null when the package name speaks for itself.
 */
data class PackageCandidate(
    val environment: String,
    val manager: String,
    val packageName: String,
    val command: String,
    val trusted: Boolean,
    val note: String? = null,
)

/**
 * Result of planning one project kind in one environment.
 *
 * @param requirements merged requirements in evaluation order, including optional alternatives.
 * @param satisfied tools that count as usable, in requirement order.
 * @param missing required tools with no usable implementation; inherited Termux tools land here.
 * @param candidates install candidates for missing tools only, deduplicated by command.
 * @param blockers reasons the plan cannot proceed, in reporting order.
 * @param canProceed true only when no blocker exists and every missing tool has a candidate.
 */
data class DependencyPlan(
    val projectKind: ProjectKind,
    val requirements: List<ToolRequirement>,
    val satisfied: List<String>,
    val missing: List<ToolRequirement>,
    val candidates: List<PackageCandidate>,
    val blockers: List<String>,
    val canProceed: Boolean,
)

/**
 * Plans the toolchain for a project kind from observed tool evidence.
 *
 * The planner is the only place that decides whether an executable counts. Inside a guest a tool
 * is usable only when it is present, executable, and [ExecutableOrigin.GUEST_NATIVE]; an inherited
 * Termux `git` that runs perfectly is still reported as missing because only the distribution
 * package makes it a real guest tool. Plans are bounded and deterministic: requirements keep a
 * fixed order, duplicate tools collapse to their first requirement, and every candidate is one
 * exact command from a trusted repository.
 */
object DependencyPlanner {
    private const val MAX_TOOLS = 32
    private const val MAX_CANDIDATES = 16
    private const val MAX_BLOCKERS = 16
    private const val MAX_TOOL_NAME_CHARS = 32
    private const val MAX_ENVIRONMENT_CHARS = 64

    /** Install manager used inside a PRoot guest. */
    const val GUEST_MANAGER = "apt"

    /** Install manager used on the Termux host. */
    const val HOST_MANAGER = "pkg"

    /** Reported whenever an Android build is requested inside a guest without guest-native SDK evidence. */
    const val ANDROID_GUEST_BLOCKER =
        "Android SDK builds require the Termux host or a desktop toolchain; this PRoot guest cannot run sdkmanager."

    /** Reported when a guest plan needs an install but `apt` itself is not a usable guest tool. */
    const val APT_BLOCKER = "The guest package manager (apt) is unavailable."

    private const val GUEST_NOTE = "official distribution repository"
    private const val HOST_NOTE = "Termux official repository"

    /** The host package for `nodejs-lts` already bundles npm, so the note must say so. */
    private const val NPM_HOST_NOTE = "Node.js from the Termux official repository bundles npm."

    /**
     * Tools that may satisfy another requirement.
     *
     * Only C/C++ has an alternative: `gcc` can replace `clang`. See [requiredTools] for how the
     * alternative is represented.
     */
    private val ALTERNATIVES: Map<String, List<String>> = mapOf("clang" to listOf("gcc"))

    /** Guest packages that provide each tool. `sh` is intentionally absent: it is base system. */
    private val GUEST_PACKAGES: Map<String, String> = mapOf(
        "java" to "openjdk-17-jdk-headless",
        "javac" to "openjdk-17-jdk-headless",
        "gradle" to "gradle",
        "kotlinc" to "kotlin",
        "clang" to "clang",
        "make" to "make",
        "cmake" to "cmake",
        "git" to "git",
        "node" to "nodejs",
        "npm" to "npm",
        "python3" to "python3",
        "pip3" to "python3-pip",
    )

    /** Termux packages that provide each tool. `sh` is intentionally absent: it is base system. */
    private val HOST_PACKAGES: Map<String, String> = mapOf(
        "java" to "openjdk-17",
        "javac" to "openjdk-17",
        "gradle" to "gradle",
        "kotlinc" to "kotlin",
        "sdkmanager" to "android-sdk",
        "adb" to "android-sdk",
        "clang" to "clang",
        "make" to "make",
        "cmake" to "cmake",
        "git" to "git",
        "node" to "nodejs-lts",
        "npm" to "nodejs-lts",
        "python3" to "python",
        "pip3" to "python",
    )

    /** Tools that cannot be installed inside this ARM64 PRoot guest. */
    private val BLOCKED_IN_GUEST: Map<String, String> = mapOf(
        "sdkmanager" to ANDROID_GUEST_BLOCKER,
        "adb" to ANDROID_GUEST_BLOCKER,
    )

    /**
     * Builds the plan for [projectKind] in [environmentKind] from observed [tools].
     *
     * Requirements keep [requiredTools] order and duplicates collapse to their first entry. A
     * required tool is satisfied by its own usable binary or, for `clang`, by a usable `gcc`
     * alternative. Everything else becomes missing, and only missing tools get candidates. In a
     * guest, `apt` is required only when something must actually be installed, so an already
     * complete guest toolchain is not blocked by an unused package manager.
     *
     * Blockers report capability limits first, then the package manager, then each missing tool
     * that has no trusted source. [DependencyPlan.canProceed] is true only when no blocker remains
     * and every missing tool is covered by a candidate.
     */
    fun plan(projectKind: ProjectKind, environmentKind: String, tools: Map<String, ToolState>): DependencyPlan {
        val environment = environmentKind.trim().take(MAX_ENVIRONMENT_CHARS)
        val guest = ExecutableClassifier.isGuestEnvironment(environment)
        val host = !guest && ExecutableClassifier.isHostEnvironment(environment)
        val requirements = mergeRequirements(requiredTools(projectKind))
        val satisfied = LinkedHashSet<String>()
        val missing = ArrayList<ToolRequirement>()
        val blockers = LinkedHashSet<String>()

        if (guest && projectKind == ProjectKind.ANDROID_APP && !isUsable("sdkmanager", tools, guest)) {
            addBlocker(blockers, ANDROID_GUEST_BLOCKER)
        }

        for (requirement in requirements) {
            val tool = requirement.tool
            if (isUsable(tool, tools, guest)) {
                satisfied += tool
                continue
            }
            if (!requirement.required) continue
            val alternative = ALTERNATIVES[tool].orEmpty().firstOrNull { isUsable(it, tools, guest) }
            if (alternative != null) {
                satisfied += alternative
                continue
            }
            missing += requirement
        }

        if (guest && missing.isNotEmpty() && !isUsable("apt", tools, guest)) {
            addBlocker(blockers, APT_BLOCKER)
        }

        val candidates = ArrayList<PackageCandidate>()
        for (requirement in missing) {
            val tool = requirement.tool
            val blocked = if (guest) BLOCKED_IN_GUEST[tool] else null
            if (blocked != null) {
                addBlocker(blockers, blocked)
                continue
            }
            val packageName = packageFor(tool, guest, host)
            if (packageName == null) {
                addBlocker(blockers, missingToolBlocker(tool, environment))
                continue
            }
            if (candidates.size >= MAX_CANDIDATES) {
                addBlocker(blockers, missingToolBlocker(tool, environment))
                continue
            }
            val candidate = candidateFor(environment, guest, packageName)
            if (candidates.none { it.command == candidate.command }) candidates += candidate
        }

        val covered = missing.all { covers(it.tool, candidates, guest, host) }
        return DependencyPlan(
            projectKind = projectKind,
            requirements = requirements,
            satisfied = satisfied.toList(),
            missing = missing.toList(),
            candidates = candidates.toList(),
            blockers = blockers.toList(),
            canProceed = blockers.isEmpty() && (missing.isEmpty() || covered),
        )
    }

    /**
     * Infers the project kind from goal text.
     *
     * Matching is case-insensitive and ordered: Android wins over Kotlin because an Android app
     * also mentions Kotlin or Gradle, and a Kotlin/JVM goal wins over C/C++ because it may mention
     * CMake or C++. Text with no known keyword is [ProjectKind.GENERIC].
     */
    fun inferProjectKind(goal: String): ProjectKind {
        val text = goal.lowercase()
        if (text.isBlank()) return ProjectKind.GENERIC
        if (text.contains("android") && (text.contains("app") || text.contains("apk"))) {
            return ProjectKind.ANDROID_APP
        }
        if (text.contains("kotlin") || text.contains("gradle") || text.contains("jvm")) {
            return ProjectKind.KOTLIN_JVM
        }
        if (text.contains("c++") || text.contains("cmake") || text.contains("c project") || text.contains("qt")) {
            return ProjectKind.C_CPP
        }
        if (text.contains("node") || text.contains("npm") || text.contains("typescript") || text.contains("react")) {
            return ProjectKind.NODE
        }
        if (text.contains("python") || text.contains("django") || text.contains("flask")) {
            return ProjectKind.PYTHON
        }
        return ProjectKind.GENERIC
    }

    /**
     * Tools [projectKind] needs, in evaluation order.
     *
     * C/C++ lists both compilers: `clang` is the required primary tool and `gcc` is a documented
     * alternative. NYX accepts either compiler, so a usable `gcc` satisfies the `clang`
     * requirement, while an absent `gcc` is never reported as missing and never triggers an
     * install. Keeping one canonical package (clang) avoids proposing two compilers for the same
     * requirement and keeps the install plan deterministic.
     *
     * `git` is optional for Kotlin/JVM builds because a local build can proceed without cloning.
     * `sh` has no package candidate: it is base system in both environments, so its absence is a
     * broken environment rather than an installable gap.
     */
    fun requiredTools(projectKind: ProjectKind): List<ToolRequirement> = when (projectKind) {
        ProjectKind.KOTLIN_JVM -> listOf(
            ToolRequirement("sh", "Runs build scripts and shell steps."),
            ToolRequirement("java", "Runs the JVM build tools."),
            ToolRequirement("javac", "Compiles JVM sources for the build."),
            ToolRequirement("gradle", "Drives the Gradle build."),
            ToolRequirement("git", "Fetches and versions project sources.", required = false),
        )

        ProjectKind.ANDROID_APP -> listOf(
            ToolRequirement("sh", "Runs build scripts and shell steps."),
            ToolRequirement("java", "Runs the Android build tools on the JVM."),
            ToolRequirement("javac", "Compiles JVM sources for the build."),
            ToolRequirement("gradle", "Drives the Gradle build."),
            ToolRequirement("sdkmanager", "Installs Android SDK platforms and build tools."),
            ToolRequirement("adb", "Installs and inspects the APK on a device."),
        )

        ProjectKind.C_CPP -> listOf(
            ToolRequirement("sh", "Runs build scripts and shell steps."),
            ToolRequirement("clang", "Compiles C and C++ sources; gcc is accepted as an alternative."),
            ToolRequirement("gcc", "Alternative compiler accepted when clang is unavailable.", required = false),
            ToolRequirement("make", "Runs Makefile-based build steps."),
            ToolRequirement("cmake", "Generates the native build system."),
            ToolRequirement("git", "Fetches and versions project sources."),
        )

        ProjectKind.NODE -> listOf(
            ToolRequirement("sh", "Runs build scripts and shell steps."),
            ToolRequirement("node", "Runs the Node.js runtime."),
            ToolRequirement("npm", "Installs JavaScript dependencies."),
            ToolRequirement("git", "Fetches and versions project sources."),
        )

        ProjectKind.PYTHON -> listOf(
            ToolRequirement("sh", "Runs build scripts and shell steps."),
            ToolRequirement("python3", "Runs Python build and test steps."),
            ToolRequirement("pip3", "Installs Python dependencies."),
            ToolRequirement("git", "Fetches and versions project sources."),
        )

        ProjectKind.GENERIC -> listOf(
            ToolRequirement("sh", "Runs build scripts and shell steps."),
            ToolRequirement("git", "Fetches and versions project sources."),
        )
    }

    /**
     * True when [state] is usable evidence in an environment of the given family.
     *
     * Presence and executability are never enough inside a guest: the origin must also be
     * [ExecutableOrigin.GUEST_NATIVE], which is exactly the rule that rejects inherited Termux
     * tools. Host environments do not constrain origin because their tools are Termux tools by
     * definition.
     */
    private fun isUsable(tool: String, tools: Map<String, ToolState>, guest: Boolean): Boolean {
        val state = tools[tool] ?: return false
        if (!state.present || !state.usable) return false
        return !guest || state.origin == ExecutableOrigin.GUEST_NATIVE
    }

    /** Collapses duplicate requirements while preserving first-seen order. */
    private fun mergeRequirements(requirements: List<ToolRequirement>): List<ToolRequirement> {
        val merged = LinkedHashMap<String, ToolRequirement>()
        for (requirement in requirements) {
            val tool = requirement.tool.trim()
            if (tool.isEmpty() || tool.length > MAX_TOOL_NAME_CHARS) continue
            val existing = merged[tool]
            merged[tool] = when {
                existing == null -> requirement.copy(tool = tool)
                requirement.required && !existing.required -> existing.copy(required = true)
                else -> existing
            }
            if (merged.size >= MAX_TOOLS) break
        }
        return merged.values.toList()
    }

    /** Resolves the package that provides [tool], or null when the environment has no source. */
    private fun packageFor(tool: String, guest: Boolean, host: Boolean): String? = when {
        guest -> GUEST_PACKAGES[tool]
        host -> HOST_PACKAGES[tool]
        else -> null
    }

    /** Builds the single exact install command for [packageName] in [environment]. */
    private fun candidateFor(
        environment: String,
        guest: Boolean,
        packageName: String,
    ): PackageCandidate {
        val manager = if (guest) GUEST_MANAGER else HOST_MANAGER
        val command = if (guest) {
            "apt-get install -y --no-install-recommends $packageName"
        } else {
            "pkg install -y $packageName"
        }
        val note = when {
            guest -> GUEST_NOTE
            packageName == "nodejs-lts" -> NPM_HOST_NOTE
            else -> HOST_NOTE
        }
        return PackageCandidate(
            environment = environment,
            manager = manager,
            packageName = packageName,
            command = command,
            trusted = true,
            note = note,
        )
    }

    /** True when [candidates] contain the package that provides [tool]. */
    private fun covers(tool: String, candidates: List<PackageCandidate>, guest: Boolean, host: Boolean): Boolean {
        val packageName = packageFor(tool, guest, host) ?: return false
        return candidates.any { it.packageName == packageName }
    }

    /** Blocker naming a tool that no trusted package source can provide in [environment]. */
    private fun missingToolBlocker(tool: String, environment: String): String {
        val shown = environment.ifEmpty { "unknown" }
        return "Required tool '$tool' is missing and no trusted package source is available for environment '$shown'."
    }

    private fun addBlocker(blockers: MutableSet<String>, message: String) {
        if (blockers.size < MAX_BLOCKERS) blockers += message
    }
}
