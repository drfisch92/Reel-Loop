package at.reelloop.prototype

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MasterClock(private val scope: CoroutineScope) {
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private var ticker: Job? = null
    private var originNs = 0L
    private var loopDurationNs = 1L

    fun start(loopDurationMs: Long, initialPositionMs: Long = 0L) {
        val now = nowNs()
        startAt(
            originNs = now - initialPositionMs.coerceAtLeast(0L) * 1_000_000L,
            loopDurationNs = loopDurationMs.coerceAtLeast(1L) * 1_000_000L
        )
    }

    fun startAt(originNs: Long, loopDurationNs: Long) {
        stop(reset = false)
        this.originNs = originNs
        this.loopDurationNs = loopDurationNs.coerceAtLeast(1L)

        ticker = scope.launch {
            while (isActive) {
                val now = nowNs()
                val elapsedNs = (now - this@MasterClock.originNs).coerceAtLeast(0L)
                _elapsedMs.value =
                    (elapsedNs % this@MasterClock.loopDurationNs) / 1_000_000L
                delay(8L)
            }
        }
    }

    fun stop(reset: Boolean = true) {
        ticker?.cancel()
        ticker = null
        if (reset) _elapsedMs.value = 0L
    }

    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()

    suspend fun awaitUntil(targetNs: Long) {
        while (true) {
            val remainingNs = targetNs - nowNs()
            if (remainingNs <= 0L) return

            val remainingMs = remainingNs / 1_000_000L
            if (remainingMs > 1L) {
                delay((remainingMs - 1L).coerceAtLeast(1L))
            } else {
                // Yield without using Java APIs that are unavailable on Android 11.
                delay(1L)
            }
        }
    }
}
