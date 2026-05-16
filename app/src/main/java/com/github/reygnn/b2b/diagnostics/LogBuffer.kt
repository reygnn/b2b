package com.github.reygnn.b2b.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory ring buffer of [LogEntry]s for the in-app log viewer. Capacity
 * is [MAX_ENTRIES]; once full, the oldest entry is evicted on every new
 * log call.
 *
 * Thread-safety: `log()` may be called from any coroutine dispatcher
 * (worker, orchestrator, UI). The internal deque is mutated under a
 * `synchronized` block; the [StateFlow] gets a fresh immutable snapshot
 * per emit so collectors never see a partial state.
 *
 * Not persisted — the buffer is wiped on process death, which is fine for
 * "did anything happen in the last few minutes?" diagnostics.
 */
@Singleton
class LogBuffer @Inject constructor() : LogSink {
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    override fun log(message: String) {
        val entry = LogEntry(epochMs = System.currentTimeMillis(), message = message)
        val snapshot = synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            buffer.toList()
        }
        _entries.value = snapshot
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _entries.value = emptyList()
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
