package io.github.fpaschos.pipekt.adapters.fake

import io.github.fpaschos.pipekt.core.SourceAdapter
import io.github.fpaschos.pipekt.core.SourceRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [SourceAdapter] backed by a mutable queue.
 *
 * Records are added via [enqueue] and consumed via [poll].
 * Nacked records are re-added to the front of the queue for immediate redelivery.
 */
class FakeSourceAdapter<T> : SourceAdapter<T> {
    private val mutex = Mutex()
    private val queue = ArrayDeque<SourceRecord<T>>()
    private val pending = mutableMapOf<String, SourceRecord<T>>()

    /** Adds [record] to the tail of the delivery queue. */
    suspend fun enqueue(record: SourceRecord<T>) =
        mutex.withLock {
            queue.addLast(record)
        }

    /** Convenience overload for enqueueing by id and payload directly. */
    suspend fun enqueue(
        id: String,
        payload: T,
    ) = enqueue(SourceRecord(id = id, payload = payload))

    override suspend fun poll(max: Int): List<SourceRecord<T>> =
        mutex.withLock {
            val batch = mutableListOf<SourceRecord<T>>()
            repeat(minOf(max, queue.size)) {
                val record = queue.removeFirst()
                pending[record.id] = record
                batch += record
            }
            batch
        }

    override suspend fun ack(id: String) =
        mutex.withLock {
            pending.remove(id)
            Unit
        }

    override suspend fun nack(id: String) =
        mutex.withLock {
            pending.remove(id)?.let { queue.addFirst(it) }
            Unit
        }

    /** Returns the number of records still waiting in the queue (not yet polled). */
    suspend fun queueSize(): Int = mutex.withLock { queue.size }

    /** Returns the number of records that have been polled but not yet acked or nacked. */
    suspend fun pendingSize(): Int = mutex.withLock { pending.size }
}
