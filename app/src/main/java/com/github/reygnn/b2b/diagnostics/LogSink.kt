package com.github.reygnn.b2b.diagnostics

/**
 * Minimal write-only contract for the in-app diagnostic log. Behind an
 * interface so unit tests can substitute a recording fake without grabbing
 * the singleton.
 */
interface LogSink {
    fun log(message: String)
}

data class LogEntry(
    // Monotonically-increasing sequence number assigned by the LogBuffer.
    // The LazyColumn in the log panel keys items by this value so multiple
    // log calls within the same millisecond don't collide (which would crash
    // the column with `Key "..." was already used`).
    val id: Long,
    val epochMs: Long,
    val message: String,
)
