package com.tanveer.aiva.data.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes history writes so an older snapshot cannot land after a newer one.
 * [submit] bumps a generation counter synchronously. A queued write runs only if
 * its generation is still the latest when the lock is acquired, which also makes
 * clear/delete a barrier in front of in-flight saves.
 */
class SnapshotPersister<T>(
    private val scope: CoroutineScope,
    private val write: suspend (T) -> Unit,
) {
    private val mutex = Mutex()
    private val gate = Any()
    private var generation = 0L

    fun submit(value: T) {
        val revision = synchronized(gate) { ++generation }
        scope.launch {
            mutex.withLock {
                val latest = synchronized(gate) { generation }
                if (revision != latest) return@withLock
                write(value)
            }
        }
    }
}
