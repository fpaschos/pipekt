package io.github.fpaschos.pipekt.core

/**
 * In-memory [SourceAdapter] test double for use in [io.github.fpaschos.pipekt.runtime.PipelineRuntime] happy-path tests.
 *
 * Holds a pre loaded queue of [SourceRecord]s. [poll] drains from the front up to [maxItems].
 * [ack] and [nack] record the ids of acknowledged/nacked records so tests can assert on
 * durable-append-before-ack behaviour.
 *
 * This class lives in `commonTest` only — it is not part of the published library artifact.
 * Library users who need a fake source for their own tests can implement [SourceAdapter] directly;
 * the interface is small.
 *
 * @param T Payload type of the records dispensed by this adapter.
 * @param records Pre-loaded records to return from [poll] in order.
 */
class FakeSourceAdapter<T>(
    records: List<SourceRecord<T>>,
) : SourceAdapter<T> {
    private val queue = ArrayDeque(records)

    /** Ids of records that were successfully acknowledged via [ack]. */
    val ackedIds = mutableSetOf<String>()

    /** Ids of records that were negatively acknowledged via [nack]. */
    val nackedIds = mutableSetOf<String>()

    /**
     * Returns up to [maxItems] records from the front of the pre-loaded queue.
     * Returns an empty list when the queue is exhausted.
     */
    override suspend fun poll(maxItems: Int): List<SourceRecord<T>> {
        val batch = mutableListOf<SourceRecord<T>>()
        repeat(maxItems) { if (queue.isNotEmpty()) batch.add(queue.removeFirst()) }
        return batch
    }

    /** Records the ids of [records] as acknowledged. */
    override suspend fun ack(records: List<SourceRecord<T>>) {
        ackedIds += records.map { it.id }
    }

    /** Records the ids of [records] as nacked. */
    override suspend fun nack(
        records: List<SourceRecord<T>>,
        retry: Boolean,
    ) {
        nackedIds += records.map { it.id }
    }
}
