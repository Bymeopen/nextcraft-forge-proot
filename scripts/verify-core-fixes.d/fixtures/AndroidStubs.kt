package android.os

open class FileObserver(path: String, mask: Int = ALL_EVENTS) {
    open fun onEvent(event: Int, path: String?) {}
    open fun startWatching() {}
    open fun stopWatching() {}
    companion object {
        const val MODIFY = 2
        const val CLOSE_WRITE = 8
        const val CREATE = 256
        const val ALL_EVENTS = 4095
    }
}

object SystemClock {
    @JvmStatic
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
}
