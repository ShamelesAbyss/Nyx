package androidx.room

import com.nyx.ai.data.NyxDatabase

/** Host fake only: this is not a claim about Android SQLite transaction execution. */
suspend fun <T> NyxDatabase.withTransaction(block: suspend () -> T): T = transaction(block)
