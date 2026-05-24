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

    // Snapshot of the last [clear]'s buffer contents, retained so the
    // 5 s undo snackbar in the Home screen can roll a stray tap back.
    // Null when no undo is pending. Mutated only under [lock] like the
    // ring buffer itself.
    private var clearedSnapshot: List<LogEntry>? = null

    private val _hasUndoableClear = MutableStateFlow(false)
    /**
     * Reactive "is there a clear that can still be undone?" signal.
     * Flips to `true` inside [clear] and back to `false` on either
     * [undoClear] or [commitClear]; the Home screen uses it to drive a
     * snackbar with an Undo action.
     */
    val hasUndoableClear: StateFlow<Boolean> = _hasUndoableClear.asStateFlow()

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

    /**
     * Wipe the visible buffer and stash its contents for a possible
     * [undoClear]. A second [clear] within the undo window commits any
     * previous snapshot before starting a new one — the user gets one
     * undo at a time, always the most recent clear.
     */
    fun clear() {
        synchronized(lock) {
            clearedSnapshot = buffer.toList()
            buffer.clear()
            _entries.value = emptyList()
            _hasUndoableClear.value = clearedSnapshot!!.isNotEmpty()
        }
    }

    /**
     * Restore the most recent [clear]'s stashed entries to the head of
     * the buffer, preserving any log lines that arrived between the
     * clear and the undo (those keep their later positions). No-op when
     * no undo is pending or when [commitClear] has already finalized it.
     */
    fun undoClear() {
        synchronized(lock) {
            val snap = clearedSnapshot ?: return
            clearedSnapshot = null
            // ArrayDeque.addFirst inserts at head, so iterate the snapshot
            // in reverse to preserve its original ordering once everything
            // has been prepended. If the combined size exceeds the cap,
            // the same FIFO eviction the live buffer uses kicks in.
            for (entry in snap.asReversed()) buffer.addFirst(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            _entries.value = buffer.toList()
            _hasUndoableClear.value = false
        }
    }

    /**
     * Finalize the most recent [clear], dropping the undo opportunity.
     * Called by [com.github.reygnn.b2b.ui.home.HomeViewModel] after the
     * 5 s snackbar window elapses; safe to call when nothing is pending.
     */
    fun commitClear() {
        synchronized(lock) {
            clearedSnapshot = null
            _hasUndoableClear.value = false
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
