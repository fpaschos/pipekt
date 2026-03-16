package io.github.fpaschos.pipekt.store

import kotlin.time.Instant

/**
 * Persistent run record for a pipeline execution.
 *
 * Immutable value type produced by [DurableStore]. Represents the long-lived anchor for a single
 * active execution of a named pipeline at a specific plan version.
 *
 * For v1 `INFINITE` pipelines, a run is long-lived and does not reach a terminal state on its own.
 * Items flowing through the pipeline are individually terminal (`COMPLETED`, `FILTERED`, `FAILED`);
 * the run itself continues indefinitely and serves as the durable grouping key for all associated
 * [WorkItem] rows and for recovery/backpressure operations.
 *
 * The [id] is a stable, durable identifier. It is used as the foreign key for all `WorkItem` rows
 * belonging to this run and as the anchor for [DurableStore.countNonTerminal] backpressure and
 * restart recovery.
 *
 * The [planVersion] is the logical version of the compiled pipeline plan. [DurableStore.findOrCreateRun]
 * is conceptually keyed by `(pipeline, planVersion)`: calling it with the same pipeline name and plan
 * version returns the existing active run, while bumping [planVersion] creates a new long-lived run
 * with a fresh [id]. Callers must bump [planVersion] whenever they introduce changes that are
 * incompatible with existing work items (eg schema changes to step payloads or pipeline topology
 * rewrites). Older runs remain in the store at their original version.
 *
 * [status] is a coarse run lifecycle and health indicator. Valid v1 values are documented in
 * [STATUS_ACTIVE] and [STATUS_FAILED]. Infinite runs are typically [STATUS_ACTIVE] for their entire
 * lifetime; [STATUS_FAILED] is reserved for unrecoverable conditions that prevent further ingestion
 * or execution for this run. [DurableStore.findAllActiveRuns] excludes [STATUS_FAILED] runs.
 *
 * [createdAt] and [updatedAt] are UTC instants. [updatedAt] is updated on every state-changing
 * store operation and is monotonically non-decreasing for a given run.
 *
 * @param id Stable, unique run id. Used as the foreign key for all [WorkItem] rows belonging to
 *   this run and as the anchor for recovery and backpressure operations. Survives process restarts.
 * @param pipeline Logical pipeline name. Must match the name used in the corresponding
 *   `PipelineDefinition`. Used together with [planVersion] as the lookup key in [DurableStore.findOrCreateRun].
 * @param planVersion Logical version of the compiled pipeline plan. [DurableStore.findOrCreateRun]
 *   is keyed by `(pipeline, planVersion)`; change this value when plan changes are incompatible
 *   with existing work items to create a new run with a fresh [id].
 * @param status Coarse run lifecycle and health status. Expected values in v1: [STATUS_ACTIVE]
 *   (run is healthy and available for ingestion and execution) and [STATUS_FAILED] (run is
 *   permanently unusable; excluded from [DurableStore.findAllActiveRuns] and active-run indices).
 *   Infinite runs do not have a `COMPLETED` status in v1.
 * @param createdAt UTC instant when the run was created. Immutable after creation.
 * @param updatedAt UTC instant when the run was last updated by a store operation.
 *   Monotonically non-decreasing; updated on every state-changing operation for this run.
 */
data class RunRecord(
    val id: String,
    val pipeline: String,
    val planVersion: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /** Run is healthy and available for ingestion and execution. The normal state for an INFINITE run. */
        const val STATUS_ACTIVE = "ACTIVE"

        /**
         * Run is permanently unusable due to an unrecoverable failure. Excluded from
         * [DurableStore.findAllActiveRuns] and the active-run Postgres index. Infinite runs
         * should only reach this state under conditions that prevent any further progress.
         */
        const val STATUS_FAILED = "FAILED"
    }
}
