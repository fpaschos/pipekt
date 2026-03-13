package io.github.fpaschos.pipekt.store

/**
 * Persistent run record for a pipeline execution.
 *
 * @param id Unique run id.
 * @param pipeline Pipeline name.
 * @param planVersion Version of the pipeline plan (for compatibility).
 * @param status Run status (e.g. ACTIVE, FAILED).
 * @param createdAtMs Epoch ms when the run was created.
 * @param updatedAtMs Epoch ms when the run was last updated.
 */
data class RunRecord(
    val id: String,
    val pipeline: String,
    val planVersion: String,
    val status: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)
