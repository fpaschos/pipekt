package io.github.fpaschos.pipekt.core

/**
 * Abstraction over an external message source.
 *
 * Implementations must be thread-safe; the runtime may call [poll] from a
 * single coroutine at a time but [ack]/[nack] may arrive concurrently.
 */
interface SourceAdapter<T> {
    /**
     * Returns up to [max] records available from the source without blocking
     * indefinitely. Returns an empty list when no records are ready.
     */
    suspend fun poll(max: Int = 1): List<SourceRecord<T>>

    /** Acknowledges successful processing of the record identified by [id]. */
    suspend fun ack(id: String)

    /** Negative-acknowledges the record identified by [id], making it available for redelivery. */
    suspend fun nack(id: String)
}
