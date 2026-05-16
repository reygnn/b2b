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
    val epochMs: Long,
    val message: String,
)
