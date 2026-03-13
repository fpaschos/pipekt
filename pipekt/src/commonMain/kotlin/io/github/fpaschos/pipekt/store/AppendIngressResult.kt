package io.github.fpaschos.pipekt.store

/**
 * Result of a bulk [DurableStore.appendIngress] call.
 *
 * The sum of [appended] and [duplicates] always equals the number of records passed in,
 * reflecting `ON CONFLICT (run_id, source_id) DO NOTHING` semantics: every record is
 * either inserted or identified as a duplicate.
 *
 * @param appended Number of records inserted.
 * @param duplicates Number of records skipped (already present for the same `(runId, sourceId)`).
 */
data class AppendIngressResult(
    val appended: Int,
    val duplicates: Int,
)
