package com.nextcraft.forge.runtime

/** Bytes received while a task ran, and the recent speed. */
data class TransferStats(val bytes: Long = 0L, val bytesPerSecond: Double = 0.0)

/** A step the install script announced with a `##STEP n/m label` line. */
data class InstallStep(val n: Int, val total: Int, val label: String) {
    /** Share of the install finished before this step started, 0..99. */
    val percentBefore: Int get() = if (total <= 0) 0 else ((n - 1).coerceAtLeast(0) * 100 / total).coerceIn(0, 99)
}

internal object InstallProgress {
    private val STEP = Regex("^##STEP (\\d+)/(\\d+) (.*)$")

    fun parseStep(line: String): InstallStep? {
        val match = STEP.find(line.trim()) ?: return null
        val n = match.groupValues[1].toIntOrNull() ?: return null
        val total = match.groupValues[2].toIntOrNull() ?: return null
        return InstallStep(n, total, match.groupValues[3].trim())
    }

    /**
     * Splits [pending] plus the new [chunk] into complete lines. Returns the text to show (step lines removed), the last
     * step seen, and the unfinished last line to carry into the next chunk (a marker may arrive in two pieces).
     */
    fun consume(pending: String, chunk: String): Triple<String, InstallStep?, String> {
        val all = pending + chunk
        val cut = all.lastIndexOf('\n')
        if (cut < 0) return Triple("", null, all)
        val complete = all.substring(0, cut + 1)
        val rest = all.substring(cut + 1)
        var step: InstallStep? = null
        val shown = StringBuilder()
        complete.lineSequence().forEachIndexed { index, line ->
            val parsed = parseStep(line)
            if (parsed != null) step = parsed else if (index < complete.count { it == '\n' }) shown.append(line).append('\n')
        }
        return Triple(shown.toString(), step, rest)
    }
}

/** Speed over the last [window] samples; the first samples have no history, so they average what exists. */
internal class SpeedSmoother(private val window: Int = 3) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Adds the byte counter [bytes] read at [atMillis] and returns bytes per second over the window. */
    fun add(bytes: Long, atMillis: Long): Double {
        samples.addLast(bytes to atMillis)
        while (samples.size > window + 1) samples.removeFirst()
        val first = samples.first()
        val last = samples.last()
        val seconds = (last.second - first.second) / 1000.0
        return if (seconds <= 0.0) 0.0 else ((last.first - first.first).coerceAtLeast(0L)) / seconds
    }
}
