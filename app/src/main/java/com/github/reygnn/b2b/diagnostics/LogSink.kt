package com.github.reygnn.b2b.diagnostics

/**
 * Minimal write-only contract for the in-app diagnostic log. Behind an
 * interface so unit tests can substitute a recording fake without grabbing
 * the singleton.
 */
interface LogSink {
    fun log(message: String)

    /**
     * High-frequency diagnostic log. Behaves like [log] when the buffer's
     * trace toggle is on, otherwise drops the message. Call sites are
     * things like state-event dumps, trigger-arm/cancel/fire, HTTP
     * response codes — useful for chasing races, noisy in normal use.
     */
    fun trace(message: String)
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
