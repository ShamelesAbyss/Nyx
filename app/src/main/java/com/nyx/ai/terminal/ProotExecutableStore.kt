package com.nyx.ai.terminal

import android.content.Context

/**
 * Chooses the verified PRoot path from a fresh host probe first, then from the last persisted
 * discovery. Kept pure so a fresh bridge/manager instance can be tested without Android.
 */
object ProotPathResolver {
    fun choose(discoveredPath: String?, persistedPath: String?): String? =
        sanitize(discoveredPath) ?: sanitize(persistedPath)

    fun sanitize(path: String?): String? = ProotExecutableStore.sanitize(path)
}

/**
 * Process-wide and persisted location of the `proot-distro` executable that environment discovery
 * actually found on the Termux host.
 *
 * TerminalRepository, the Ollama manager, and the objective service each construct a
 * [TerminalBridge]. They must all see the same verified path; otherwise a bridge created before
 * discovery keeps its null probe and refuses a valid Debian command. The path is deliberately not
 * checked with [java.io.File] here: Termux's private directory is not readable by NYX, so a host
 * probe is the only valid evidence.
 */
object ProotExecutableStore {
    private const val PREFS_NAME = "nyx_terminal_runtime"
    private const val KEY_PATH = "verified_proot_distro_path"
    private const val MAX_PATH_CHARS = 4_096

    @Volatile
    private var cached: String? = null

    @Volatile
    private var loaded = false

    @Synchronized
    fun resolve(context: Context): String? {
        if (!loaded) {
            cached = sanitize(
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_PATH, null),
            )
            loaded = true
        }
        return cached
    }

    @Synchronized
    fun remember(context: Context, path: String?) {
        val safe = sanitize(path)
        cached = safe
        loaded = true
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PATH, safe)
            .apply()
    }

    /** Accepts only an absolute, bounded, NUL-free path. No filesystem access is attempted. */
    fun sanitize(path: String?): String? =
        path?.takeIf { it.startsWith('/') && it.length <= MAX_PATH_CHARS && '\u0000' !in it }
}
