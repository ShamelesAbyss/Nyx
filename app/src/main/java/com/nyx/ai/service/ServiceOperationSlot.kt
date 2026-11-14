package com.nyx.ai.service

/**
 * Admission lasts through cleanup, not merely while a coroutine is active. Android start IDs attach
 * to an operation generation; duplicate/rejected starts cannot orphan the current finalizer.
 */
internal class ServiceOperationSlot<T : Any> {
    private var owner: T? = null
    private var latestStartId: Int = 0

    @Synchronized fun current(): T? = owner

    @Synchronized fun attachStart(startId: Int) { latestStartId = startId }

    @Synchronized fun admit(candidate: T): Boolean {
        if (owner != null) return false
        owner = candidate
        return true
    }

    /** Replacement is allowed only when its execution joins the predecessor before side effects. */
    @Synchronized fun replace(candidate: T): T? {
        val previous = owner
        owner = candidate
        return previous
    }

    @Synchronized fun withOwner(candidate: T, action: () -> Unit): Boolean {
        if (owner !== candidate) return false
        action()
        return true
    }

    /** Old generations cannot clear ownership or remove a newer notification. */
    @Synchronized fun finish(candidate: T, cleanup: (Int) -> Unit): Boolean {
        if (owner !== candidate) return false
        try { cleanup(latestStartId) } finally { owner = null }
        return true
    }
}
