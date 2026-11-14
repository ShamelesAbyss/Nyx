package com.nyx.ai.autonomy

/** Pure predicates; callers must re-read persisted rows inside the same transaction as mutations. */
object ExecutionLeasePolicy {
    fun mayCheckpoint(acquired: Boolean, lost: Boolean, owner: String, persistedOwner: String?): Boolean =
        acquired && !lost && owner == persistedOwner

    fun needsRecovery(status: String, expiresAt: Long?, now: Long): Boolean =
        status in setOf("planning", "running") && (expiresAt == null || expiresAt <= now)
}
