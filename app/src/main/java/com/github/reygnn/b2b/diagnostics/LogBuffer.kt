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
    private var nextId: Long = 0L

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    // In-memory only — not persisted across process restarts. The toggle
    // is opt-in by design: leaving it on after a debug session is a
    // mistake we don't have to recover from.
    private val _traceEnabled = MutableStateFlow(false)
    val traceEnabled: StateFlow<Boolean> = _traceEnabled.asStateFlow()
    fun setTraceEnabled(on: Boolean) { _traceEnabled.value = on }

    override fun log(message: String) {
        // The StateFlow assignment lives inside the lock so the snapshot
        // built under a given critical section is the one that is published.
        // Without it, two concurrent log() calls (orchestrator on
        // @DefaultDispatcher + repository on @IoDispatcher, very real once
        // trace is on) could each build a snapshot inside their own
        // synchronized block, then publish them in arbitrary order outside
        // the lock — the later snapshot can lose to the earlier one, and a
        // freshly-appended entry vanishes from the UI even though the
        // internal buffer still holds it.
        synchronized(lock) {
            val entry = LogEntry(
                id = nextId++,
                epochMs = System.currentTimeMillis(),
                message = message,
            )
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
    }

    override fun trace(message: String) {
        if (_traceEnabled.value) log(message)
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
