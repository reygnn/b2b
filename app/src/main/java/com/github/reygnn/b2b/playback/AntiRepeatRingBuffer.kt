package com.github.reygnn.b2b.playback

/**
 * Fixed-capacity ring buffer for the most-recently-played URIs.
 *
 * Backed by an ArrayDeque (FIFO order) plus a HashSet for O(1) `contains`.
 * Not thread-safe; the orchestrator service confines it to a single coroutine.
 */
class AntiRepeatRingBuffer(val capacity: Int) {
    init { require(capacity > 0) { "capacity must be > 0" } }

    private val queue: ArrayDeque<String> = ArrayDeque(capacity)
    private val lookup: HashSet<String> = HashSet(capacity * 2)

    val size: Int get() = queue.size

    fun add(uri: String) {
        if (lookup.contains(uri)) {
            // Move-to-front: drop existing occurrence, re-add at tail.
            queue.remove(uri)
        }
        queue.addLast(uri)
        lookup.add(uri)
        while (queue.size > capacity) {
            val evicted = queue.removeFirst()
            lookup.remove(evicted)
        }
    }

    fun contains(uri: String): Boolean = lookup.contains(uri)

    fun snapshot(): Set<String> = lookup.toSet()

    fun clear() {
        queue.clear()
        lookup.clear()
    }
}
