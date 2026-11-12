package com.nyx.ai.autonomy.policy

import android.content.Context
import java.util.UUID

/**
 * Process-wide access to the single autonomy policy. Every backend calls this object instead of
 * keeping its own mode or grant list, so Manual, Smart, and Full cannot diverge between features.
 *
 * All mutating and authorization calls are serialized on [lock]. The engine itself is synchronized
 * as well, but keeping the snapshot/persist pair under one lock prevents an older snapshot from
 * overwriting a newer Emergency Stop or revocation.
 */
object AutonomyRuntime {
    private val lock = Any()

    @Volatile
    private var engine: AutonomyPolicyEngine = AutonomyPolicyEngine()

    @Volatile
    private var store: AutonomyPolicyStore? = null

    fun initialize(context: Context) {
        synchronized(lock) { initializeLocked(context) }
    }

    fun policy(): AutonomyPolicyEngine = engine

    fun mode(): AutonomyMode = engine.mode

    fun setMode(context: Context, mode: AutonomyMode) {
        synchronized(lock) {
            initializeLocked(context)
            engine.updateMode(mode)
            persistLocked()
        }
    }

    fun emergencyStop(context: Context) {
        synchronized(lock) {
            initializeLocked(context)
            engine.emergencyStop()
            persistLocked()
        }
    }

    fun resetStop(context: Context) {
        synchronized(lock) {
            initializeLocked(context)
            engine.resetStop()
            persistLocked()
        }
    }

    fun grant(context: Context, grant: CapabilityGrant) {
        synchronized(lock) {
            initializeLocked(context)
            engine.grant(grant)
            persistLocked()
        }
    }

    fun revoke(context: Context, grantId: String): Boolean = synchronized(lock) {
        initializeLocked(context)
        val removed = engine.revoke(grantId)
        if (removed) persistLocked()
        removed
    }

    /** Removes every grant and returns the device to Manual approval. */
    fun clearGrants(context: Context) {
        synchronized(lock) {
            initializeLocked(context)
            engine.replaceGrants(emptyList())
            engine.updateMode(AutonomyMode.MANUAL)
            persistLocked()
        }
    }

    /**
     * Creates the routine grants implied by an explicit mode selection.
     *
     * A workspace is mandatory for every workspace-derived capability. When no workspace has been
     * selected, wildcard routine grants are removed and nothing new is issued; NYX must not acquire
     * command or file authority for a project that does not exist yet. No `"*"` scope is issued.
     */
    fun grantRoutineScope(context: Context, environmentId: String, workspacePath: String?) {
        synchronized(lock) {
            initializeLocked(context)
            val routineCapabilities = RoutineGrantPolicy.CAPABILITIES
            val retained = engine.snapshot().grants.filterNot { grant ->
                grant.capability in routineCapabilities || grant.scope.trim() == "*"
            }
            val workspace = RoutineGrantPolicy.normalizeWorkspace(workspacePath)
            if (workspace == null) {
                engine.replaceGrants(retained)
                persistLocked()
                return@synchronized
            }
            val now = System.currentTimeMillis()
            val grants = RoutineGrantPolicy.build(environmentId, workspace, now) { UUID.randomUUID().toString() }
            engine.replaceGrants(retained + grants)
            persistLocked()
        }
    }

    /** Authorizes and records one request through the shared policy. */
    fun authorize(
        context: Context,
        request: ActionRequest,
        delegated: DelegatedPermissionScope? = null,
    ): AuthorizationDecision {
        val result = synchronized(lock) {
            initializeLocked(context)
            val decision = engine.authorize(request, delegated)
            decision to engine.journalEntries().lastOrNull()
        }
        // Journal file I/O is intentionally outside the state lock. A Stop can be observed while a
        // previous decision is being appended, and the append itself is synchronized by the store.
        result.second?.let { entry -> runCatching { store?.appendJournal(entry) } }
        return result.first
    }

    private fun initializeLocked(context: Context) {
        if (store != null) return
        val created = AutonomyPolicyStore(context.applicationContext)
        val loaded = runCatching { created.load() }
            .getOrDefault(PolicySnapshot(AutonomyMode.MANUAL, false, emptyList()))
        // v0.7.0 could persist `"*"` routine grants. They are intentionally discarded on load;
        // the user must select a concrete workspace before any command or file authority exists.
        val safeGrants = loaded.grants.filterNot { it.scope.trim() == "*" }
        engine = AutonomyPolicyEngine(loaded.mode, safeGrants)
        if (loaded.stopped) engine.emergencyStop()
        store = created
    }

    private fun persistLocked() {
        runCatching { store?.save(engine.snapshot()) }
    }
}
