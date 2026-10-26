package com.tanveer.aiva.data.local

import android.content.Context
import com.tanveer.aiva.domain.models.ChatThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface HistoryLoad {
    data object Missing : HistoryLoad
    data class Ready(val threads: List<ChatThread>) : HistoryLoad
    data class Unreadable(val recoveryName: String) : HistoryLoad
}

/**
 * Private JSON history. Writes are atomic. A caller must also serialize snapshots
 * (see SnapshotPersister) so an older save cannot overwrite a newer one.
 *
 * Missing file means a first launch or history that was turned off — never sample chats.
 * An unreadable file is copied aside and reported; it is not treated as a new install.
 */
class ThreadStore(context: Context) {
    private val file = File(context.filesDir, "threads.v1.json")
    private val lock = Mutex()

    suspend fun load(): HistoryLoad = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext HistoryLoad.Missing
        try {
            HistoryLoad.Ready(ThreadCodec.decode(file.readText()))
        } catch (_: Exception) {
            val recovery = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            runCatching { file.copyTo(recovery, overwrite = false) }
            runCatching { file.delete() }
            HistoryLoad.Unreadable(recovery.name)
        }
    }

    suspend fun save(threads: List<ChatThread>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val payload = ThreadCodec.encode(threads)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
                tmp.delete()
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            file.delete()
            File(file.parentFile, file.name + ".tmp").delete()
        }
    }
}
