package com.nyx.ai.autonomy.policy

import android.content.Context
import java.util.UUID

/**
 * Process-wide access to the single autonomy policy. Every backend calls this object instead of
 * keeping its own mode or grant list, so Manual, Smart, and Full cannot diverge between features.
 */
object AutonomyRuntime {
    private val lock = Any()

    @Volatile
    private var engine: AutonomyPolicyEngine = AutonomyPolicyEngine()

    @Volatile
    private var store: AutonomyPolicyStore? = null

    fun initialize(context: Context) {
        if (store != null) return
        synchronized(lock) {
            if (store != null) return
            val created = AutonomyPolicyStore(context.applicationContext)
            val snapshot = runCatching { created.load() }.getOrDefault(PolicySnapshot(AutonomyMode.MANUAL, false, emptyList()))
            engine = AutonomyPolicyEngine(snapshot.mode, snapshot.grants)
            if (snapshot.stopped) engine.emergencyStop()
            store = created
        }
    }

    fun policy(): AutonomyPolicyEngine = engine

    fun mode(): AutonomyMode = engine.mode

    fun setMode(context: Context, mode: AutonomyMode) {
        initialize(context)
        engine.updateMode(mode)
        persist()
    }

    fun emergencyStop(context: Context) {
        initialize(context)
        engine.emergencyStop()
        persist()
    }

    fun resetStop(context: Context) {
        initialize(context)
        engine.resetStop()
        persist()
    }

    fun grant(context: Context, grant: CapabilityGrant) {
        initialize(context)
        engine.grant(grant)
        persist()
    }

    fun revoke(context: Context, grantId: String): Boolean {
        initialize(context)
        val removed = engine.revoke(grantId)
        if (removed) persist()
        return removed
    }

    /** Removes every grant and returns the device to Manual approval. */
    fun clearGrants(context: Context) {
        initialize(context)
        engine.replaceGrants(emptyList())
        engine.updateMode(AutonomyMode.MANUAL)
        persist()
    }

    /**
     * Creates the routine grants implied by an explicit mode selection. Publishing, Git pushes, APK
     * installation, system configuration, and credential use are deliberately never granted here.
     */
    fun grantRoutineScope(context: Context, environmentId: String, workspacePath: String?) {
        initialize(context)
        val now = System.currentTimeMillis()
        val scope = workspacePath?.takeIf { it.startsWith('/') } ?: "*"
        val capabilities = listOf(
            "workspace.read" to scope,
            "workspace.write" to scope,
            "workspace.delete" to scope,
            "command.readonly" to scope,
            "command.mutating" to scope,
            "packages.install" to "*",
            "network.read" to "*",
            "network.download" to "*",
            "git.commit" to scope,
            "delegate" to "*",
        )
        for ((capability, grantScope) in capabilities) {
            engine.grant(
                CapabilityGrant(
                    id = UUID.randomUUID().toString(),
                    capability = capability,
                    environmentId = environmentId,
                    scope = grantScope,
                    createdAt = now,
                )
            )
        }
        persist()
    }

    /** Authorizes and records one request through the shared policy. */
    fun authorize(
        context: Context,
        request: ActionRequest,
        delegated: DelegatedPermissionScope? = null,
    ): AuthorizationDecision {
        initialize(context)
        val decision = engine.authorize(request, delegated)
        val entry = engine.journalEntries().lastOrNull()
        if (entry != null) runCatching { store?.appendJournal(entry) }
        return decision
    }

    private fun persist() {
        runCatching { store?.save(engine.snapshot()) }
    }
}
