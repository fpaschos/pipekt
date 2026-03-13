package gr.pipekt.streams.core

interface SourceAdapter<T> {
    suspend fun poll(maxItems: Int): List<SourceRecord<T>>

    suspend fun ack(records: List<SourceRecord<T>>)

    suspend fun nack(records: List<SourceRecord<T>>, retry: Boolean)
}
