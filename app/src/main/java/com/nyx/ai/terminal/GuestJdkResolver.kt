package com.nyx.ai.terminal

private const val JDK_CANDIDATE_NONE = "(none)"

/** One JDK package name and the candidate version APT actually reported, if any. */
data class JdkPackageCandidate(
    val packageName: String,
    val candidateVersion: String?,
) {
    val available: Boolean
        get() = !candidateVersion.isNullOrBlank() && candidateVersion != JDK_CANDIDATE_NONE
}

/**
 * Result of a read-only guest package probe. [packageName] is null unless APT reported a real
 * candidate; a missing candidate is a diagnostic, never a reason to try a guessed package.
 */
data class JdkResolution(
    val packageName: String?,
    val osId: String? = null,
    val versionId: String? = null,
    val codename: String? = null,
    val architecture: String? = null,
    val aptSourceCount: Int? = null,
    val aptCacheAvailable: Boolean = false,
    val candidates: List<JdkPackageCandidate> = emptyList(),
    val gradleVersion: String? = null,
    val diagnostic: String = "",
) {
    val resolved: Boolean get() = packageName != null
}

/**
 * Resolves a JDK package from the guest's real Debian identity and APT cache instead of assuming
 * `openjdk-17-jdk-headless` exists. The probe is read-only: it never runs `apt-get update`, installs
 * a package, or modifies sources. Debian Trixie is checked for OpenJDK 21 and the default metapackage
 * first; older releases prefer the matching default/17 package.
 */
object GuestJdkResolver {
    private const val OS_ID = "NYX_OS_ID="
    private const val VERSION_ID = "NYX_VERSION_ID="
    private const val CODENAME = "NYX_VERSION_CODENAME="
    private const val ARCH = "NYX_ARCH="
    private const val APT_SOURCES = "NYX_APT_SOURCES="
    private const val APT_CACHE = "NYX_APT_CACHE="
    private const val GRADLE_VERSION = "NYX_GRADLE_VERSION="
    private const val PACKAGE_PREFIX = "NYX_PKG:"
    private const val MAX_VERSION_CHARS = 120

    private val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.-]{0,63}")

    val CANDIDATE_ORDER_TRIXIE = listOf(
        "openjdk-21-jdk-headless",
        "default-jdk-headless",
        "openjdk-17-jdk-headless",
    )

    val CANDIDATE_ORDER_LEGACY = listOf(
        "openjdk-17-jdk-headless",
        "default-jdk-headless",
        "openjdk-21-jdk-headless",
    )

    val CANDIDATE_ORDER_UNKNOWN = listOf(
        "default-jdk-headless",
        "openjdk-21-jdk-headless",
        "openjdk-17-jdk-headless",
    )

    /**
     * Read-only probe. `apt-cache policy` is used because it reports the candidate that APT would
     * install without downloading or changing the package lists.
     */
    fun probeScript(): String = """
        if [ -r /etc/os-release ]; then
          . /etc/os-release
          printf 'NYX_OS_ID=%s\n' "${'$'}{ID:-}"
          printf 'NYX_VERSION_ID=%s\n' "${'$'}{VERSION_ID:-}"
          printf 'NYX_VERSION_CODENAME=%s\n' "${'$'}{VERSION_CODENAME:-}"
        fi
        printf 'NYX_ARCH=%s\n' "$(dpkg --print-architecture 2>/dev/null || uname -m 2>/dev/null)"
        sources=$(grep -RhsE '^[[:space:]]*deb[[:space:]]' /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null | wc -l | tr -d ' ')
        printf 'NYX_APT_SOURCES=%s\n' "${'$'}{sources:-0}"
        if command -v apt-cache >/dev/null 2>&1; then
          printf 'NYX_APT_CACHE=1\n'
        else
          printf 'NYX_APT_CACHE=0\n'
        fi
        for p in default-jdk-headless openjdk-21-jdk-headless openjdk-17-jdk-headless; do
          c=$(apt-cache policy "${'$'}p" 2>/dev/null | awk '/^[[:space:]]*Candidate:/{print ${'$'}2; exit}')
          [ -z "${'$'}c" ] && c="(none)"
          printf 'NYX_PKG:%s=%s\n' "${'$'}p" "${'$'}c"
        done
        if command -v gradle >/dev/null 2>&1; then
          printf 'NYX_GRADLE_VERSION=%s\n' "$(gradle --version 2>/dev/null | awk '/^Gradle /{print ${'$'}2; exit}')"
        fi
    """.trimIndent()

    fun parse(stdout: String, stderr: String, exitCode: Int): JdkResolution {
        var osId: String? = null
        var versionId: String? = null
        var codename: String? = null
        var architecture: String? = null
        var aptSources: Int? = null
        var aptCache = false
        var gradleVersion: String? = null
        val candidates = LinkedHashMap<String, String?>()

        for (raw in stdout.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith(OS_ID) -> osId = clean(line.removePrefix(OS_ID))
                line.startsWith(VERSION_ID) -> versionId = clean(line.removePrefix(VERSION_ID))
                line.startsWith(CODENAME) -> codename = clean(line.removePrefix(CODENAME))
                line.startsWith(ARCH) -> architecture = clean(line.removePrefix(ARCH))
                line.startsWith(APT_SOURCES) -> aptSources = line.removePrefix(APT_SOURCES).trim().toIntOrNull()
                line.startsWith(APT_CACHE) -> aptCache = line.removePrefix(APT_CACHE).trim() == "1"
                line.startsWith(GRADLE_VERSION) -> gradleVersion = clean(line.removePrefix(GRADLE_VERSION))
                line.startsWith(PACKAGE_PREFIX) -> {
                    val body = line.removePrefix(PACKAGE_PREFIX)
                    val name = body.substringBefore('=', "").trim()
                    if (PACKAGE_NAME.matches(name)) {
                        val version = body.substringAfter('=', "").trim().take(MAX_VERSION_CHARS)
                        candidates[name] = version.takeIf { it.isNotEmpty() && it != JDK_CANDIDATE_NONE }
                    }
                }
            }
        }

        val order = candidateOrder(osId, versionId, codename)
        // A cached apt-cache answer is not proof that a repository is configured. With no deb
        // sources, treat every candidate as unresolved instead of installing from stale lists.
        val repositoryEvidence = aptCache && aptSources != 0
        val selected = if (!repositoryEvidence) {
            null
        } else {
            order.firstOrNull { name ->
                candidates[name]?.let { it.isNotBlank() && it != JDK_CANDIDATE_NONE } == true
            }
        }
        val resolution = JdkResolution(
            packageName = selected,
            osId = osId,
            versionId = versionId,
            codename = codename,
            architecture = architecture,
            aptSourceCount = aptSources,
            aptCacheAvailable = aptCache,
            candidates = order.map { JdkPackageCandidate(it, candidates[it]) },
            gradleVersion = gradleVersion,
            diagnostic = "",
        )
        return resolution.copy(diagnostic = diagnostic(resolution, stderr, exitCode))
    }

    /** Chooses the Debian release's likely package order. Unknown releases still use real candidates. */
    fun candidateOrder(osId: String?, versionId: String?, codename: String?): List<String> {
        val trixie = versionId == "13" || codename.equals("trixie", ignoreCase = true)
        val bookworm = versionId == "12" || codename.equals("bookworm", ignoreCase = true)
        return when {
            trixie -> CANDIDATE_ORDER_TRIXIE
            bookworm -> CANDIDATE_ORDER_LEGACY
            else -> CANDIDATE_ORDER_UNKNOWN
        }
    }

    private fun diagnostic(resolution: JdkResolution, stderr: String, exitCode: Int): String {
        val distro = listOfNotNull(
            resolution.osId,
            resolution.versionId,
            resolution.codename,
        ).filter { it.isNotBlank() }.joinToString("/").ifBlank { "unknown Debian guest" }
        val checked = resolution.candidates.joinToString { candidate ->
            "${candidate.packageName}=${candidate.candidateVersion ?: JDK_CANDIDATE_NONE}"
        }
        val gradle = resolution.gradleVersion?.let { " Gradle $it was observed separately." }.orEmpty()
        if (resolution.packageName != null) {
            val candidate = resolution.candidates.firstOrNull { it.packageName == resolution.packageName }
            return "Resolved ${resolution.packageName} for $distro (${resolution.architecture ?: "unknown arch"}). " +
                "APT candidate: ${candidate?.candidateVersion ?: "reported"}.$gradle"
        }
        val reason = when {
            !resolution.aptCacheAvailable ->
                "apt-cache is unavailable in the guest, so JDK package candidates could not be verified."
            (resolution.aptSourceCount ?: 0) == 0 ->
                "No APT deb sources were found in /etc/apt/sources.list or /etc/apt/sources.list.d."
            else ->
                "No installable JDK package was found for $distro (${resolution.architecture ?: "unknown arch"}). " +
                    "The APT sources may be stale."
        }
        val detail = stderr.trim().take(180)
        val exit = if (exitCode != 0) " Probe exit code: $exitCode." else ""
        return "$reason Checked: $checked. APT sources: ${resolution.aptSourceCount ?: "unknown"}.$exit" +
            (if (detail.isNotEmpty()) " $detail" else "") +
            " Run apt-get update inside Debian and retry.$gradle"
    }

    private fun clean(value: String): String? =
        value.trim().removeSurrounding("\"").takeIf { it.isNotBlank() }?.take(MAX_VERSION_CHARS)
}
