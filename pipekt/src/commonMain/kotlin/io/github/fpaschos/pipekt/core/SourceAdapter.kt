package io.github.fpaschos.pipekt.core

/**
 * Engine-level contract for ingesting records into a pipeline.
 *
 * The runtime polls via [poll], then after durable append to the store it calls [ack] for
 * successfully ingested records. On failure it can call [nack] with [retry] to indicate
 * whether the broker should redeliver. Implementations are typically platform-specific
 * (e.g. AMQP, Kafka); the core engine depends only on this interface.
 *
 * @param T The payload type of [SourceRecord] produced by this adapter.
 */
interface SourceAdapter<T> {
    /**
     * Poll for up to [maxItems] records from the source.
     * @param maxItems Maximum number of records to return (backpressure limit).
     * @return List of records (may be empty); order is preserved for ack/nack.
     */
    suspend fun poll(maxItems: Int): List<SourceRecord<T>>

    /**
     * Acknowledge that [records] were successfully ingested (e.g. after durable append to the store).
     * The runtime calls this only after records are persisted; safe to commit broker offsets.
     */
    suspend fun ack(records: List<SourceRecord<T>>)

    /**
     * Negative acknowledgement for [records] that could not be ingested or processed.
     * @param retry If true, the broker may redeliver; if false, treat as dead-letter or discard.
     */
    suspend fun nack(
        records: List<SourceRecord<T>>,
        retry: Boolean,
    )
}
