package com.nyx.ai.terminal

/**
 * Where a resolved executable actually comes from.
 *
 * NYX runs commands on the Termux host and inside a PRoot guest. The guest `PATH` ends with
 * `/data/data/com.termux/files/usr/bin`, so `git`, `clang`, `make`, `python3`, and even `pkg` can
 * resolve to Termux host binaries that were inherited through the bind mount. Those binaries may
 * start and print a version inside the guest, but they are built against Termux's prefix and must
 * never be counted as guest tools or as evidence that a Debian package is installed.
 */
enum class ExecutableOrigin {
    /** A Termux binary executed on the Termux host itself. */
    TERMUX_HOST,

    /** A binary installed by the guest distribution, normally under `/usr`, `/bin` or `/usr/local`. */
    GUEST_NATIVE,

    /** A Termux host binary reached through the inherited Termux prefix inside a guest. */
    GUEST_INHERITED_TERMUX,

    /** An Android platform binary under `/system` or `/apex`, or an app-private executable. */
    ANDROID_NATIVE,

    /** No usable evidence about the origin. Never trusted as a guest tool. */
    UNKNOWN,
}

/**
 * Classifies executable paths by origin from the environment kind and the resolved absolute path.
 *
 * The classifier is pure: it performs no file-system access, runs nothing, and never infers origin
 * from a tool name. Callers pass evidence produced by a probe, such as the `path` recorded by
 * `CapabilityProbe`, and may treat a tool as a guest tool only when [ExecutableClassifier.classify]
 * returns [ExecutableOrigin.GUEST_NATIVE] and [ExecutableClassifier.isUsableInGuest] is true.
 *
 * Environment kinds match `EnvironmentCapabilities.kind`: `termux_host` for the Termux host and
 * `proot:<alias>` for a PRoot guest. The legacy `guest` prefix is accepted as well because earlier
 * probes used it.
 */
object ExecutableClassifier {
    /** Kind token that identifies the Termux host environment. */
    const val TERMUX_HOST_KIND = "termux_host"

    /** Termux's own prefix. Inside a guest this directory is an inherited host mount. */
    const val TERMUX_USR = "/data/data/com.termux/files/usr"

    private const val TERMUX_USR_DIR = "$TERMUX_USR/"
    private const val GUEST_KIND_PREFIX = "proot:"
    private const val LEGACY_GUEST_KIND_PREFIX = "guest"
    private const val ANDROID_USER_PREFIX = "/data/user/"
    private const val ANDROID_DATA_PREFIX = "/data/data/"
    private const val ANDROID_SYSTEM_PREFIX = "/system/"
    private const val ANDROID_APEX_PREFIX = "/apex/"

    /**
     * Returns the origin of the executable at [path] resolved in an environment of [environmentKind].
     *
     * A blank [path] is [ExecutableOrigin.UNKNOWN] in every environment: without a resolved
     * location there is no evidence of origin, and absent evidence must not become a claim. A
     * relative path is also UNKNOWN because it was not resolved to an absolute file. On the Termux
     * host any absolute path is [ExecutableOrigin.TERMUX_HOST], because every tool the host runs
     * belongs to the Termux installation regardless of the path shape the probe reported.
     *
     * Inside a guest, a path under [TERMUX_USR] is [ExecutableOrigin.GUEST_INHERITED_TERMUX], a
     * path under `/system` or `/apex` is [ExecutableOrigin.ANDROID_NATIVE], and any other absolute
     * path is [ExecutableOrigin.GUEST_NATIVE]. For an unrecognized environment kind only
     * app-private locations under `/data/user/` or `/data/data/<app>/` are recognized as
     * [ExecutableOrigin.ANDROID_NATIVE]; everything else stays [ExecutableOrigin.UNKNOWN] so that
     * an unrecognized environment can never unlock guest tools.
     */
    fun classify(environmentKind: String, path: String?): ExecutableOrigin {
        val kind = environmentKind.trim().lowercase()
        val resolved = path?.trim().orEmpty()
        if (resolved.isEmpty() || !resolved.startsWith('/') || resolved.contains('\u0000')) {
            return ExecutableOrigin.UNKNOWN
        }
        if (kind == TERMUX_HOST_KIND) return ExecutableOrigin.TERMUX_HOST
        if (isGuestEnvironment(kind)) {
            return when {
                isTermuxPrefix(resolved) -> ExecutableOrigin.GUEST_INHERITED_TERMUX
                resolved.startsWith(ANDROID_SYSTEM_PREFIX) || resolved.startsWith(ANDROID_APEX_PREFIX) ->
                    ExecutableOrigin.ANDROID_NATIVE

                else -> ExecutableOrigin.GUEST_NATIVE
            }
        }
        return if (isAppPrivate(resolved)) ExecutableOrigin.ANDROID_NATIVE else ExecutableOrigin.UNKNOWN
    }

    /**
     * True only for a genuinely guest-native binary.
     *
     * An inherited Termux binary is deliberately unusable here even when it runs: installing the
     * distribution package is the only fix that makes the tool a real guest tool.
     */
    fun isUsableInGuest(origin: ExecutableOrigin): Boolean = origin == ExecutableOrigin.GUEST_NATIVE

    /** True when [environmentKind] identifies a PRoot guest rather than the Termux host. */
    internal fun isGuestEnvironment(environmentKind: String): Boolean {
        val kind = environmentKind.trim().lowercase()
        return kind.startsWith(GUEST_KIND_PREFIX) || kind.startsWith(LEGACY_GUEST_KIND_PREFIX)
    }

    /** True when [environmentKind] identifies the Termux host. */
    internal fun isHostEnvironment(environmentKind: String): Boolean =
        environmentKind.trim().lowercase() == TERMUX_HOST_KIND

    private fun isTermuxPrefix(path: String): Boolean = path == TERMUX_USR || path.startsWith(TERMUX_USR_DIR)

    private fun isAppPrivate(path: String): Boolean = when {
        path.startsWith(ANDROID_USER_PREFIX) -> true
        path.startsWith(ANDROID_DATA_PREFIX) -> !isTermuxPrefix(path)
        else -> false
    }
}
