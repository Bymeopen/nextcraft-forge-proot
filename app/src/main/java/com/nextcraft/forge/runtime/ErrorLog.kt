package com.nextcraft.forge.runtime

import kotlinx.coroutines.CancellationException

/**
 * The last errors the app caught and carried on from (best-effort cleanups, optional probes). They used to vanish, so a
 * field problem left no trace. Kept in memory only, bounded, and shown in Tools > System Diagnostics.
 */
object ErrorLog {
    data class Entry(val atMillis: Long, val tag: String, val message: String, val count: Int)

    private const val MAX_ENTRIES = 60
    private const val MAX_MESSAGE = 240
    private val entries = ArrayDeque<Entry>()

    /** Records [error] under [tag] (the place that swallowed it). Never throws. */
    @Synchronized
    fun swallowed(tag: String, error: Throwable, now: Long = System.currentTimeMillis()) {
        // A cancelled coroutine or interrupted thread is control flow, not a failure.
        if (error is CancellationException || error is InterruptedException) return
        val message = "${error::class.java.simpleName}: ${error.message.orEmpty()}".take(MAX_MESSAGE)
        val last = entries.lastOrNull()
        if (last != null && last.tag == tag && last.message == message) {
            // The same error in a row (a loop failing every second) is one line with a counter, not 60 lines.
            entries[entries.lastIndex] = last.copy(atMillis = now, count = last.count + 1)
            return
        }
        entries.addLast(Entry(now, tag, message, 1))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun recent(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    /** Newest first, one line each: "HH:mm:ss Tag ×3 IOException: ...". */
    fun asText(): String = recent().asReversed().joinToString("\n") { e ->
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.atMillis))
        val times = if (e.count > 1) " ×${e.count}" else ""
        "$time ${e.tag}$times ${e.message}"
    }
}
