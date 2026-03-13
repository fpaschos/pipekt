package io.github.fpaschos.pipekt.store

/**
 * Result of a bulk [DurableStore.appendIngress] call.
 *
 * @param appended Number of records inserted.
 * @param duplicates Number of records skipped (already present for same (runId, sourceId).
 */
data class AppendIngressResult(
    val appended: Int,
    val duplicates: Int,
)
