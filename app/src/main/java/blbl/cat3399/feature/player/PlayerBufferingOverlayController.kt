package blbl.cat3399.feature.player

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.media3.common.Player
import blbl.cat3399.R
import blbl.cat3399.databinding.ActivityPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEFAULT_BUFFERING_OVERLAY_SHOW_DELAY_MS = 1_000L
private const val GATE_FADE_IN_MS = 150L
private const val GATE_FADE_OUT_MS = 220L

/**
 * 播放页缓冲提示 + 音画起播闸门遮罩。
 *
 * 普通缓冲仍延迟显示；闸门打开时立刻全屏淡入过渡动画，解冻后淡出。
 */
internal class PlayerBufferingOverlayController(
    private val context: Context,
    private val bindingProvider: () -> ActivityPlayerBinding?,
    private val scope: CoroutineScope,
    private val playbackStateProvider: () -> Int?,
    private val showDelayMs: Long = DEFAULT_BUFFERING_OVERLAY_SHOW_DELAY_MS,
) {
    private val speedMeter = BufferingSpeedMeter()
    private var showJob: Job? = null
    private var bufferingStateStartedAtMs: Long = 0L
    @Volatile private var speedTrackingEnabled: Boolean = false
    private var overlaySuppressedUntilMs: Long = 0L
    private var overlayEligibleAtMs: Long = 0L
    private var audioWarmupActive: Boolean = false

    /**
     * 音频输出唤醒 / 视频冻帧期间：立刻展示全屏过渡遮罩。
     *
     * @param active true=进入闸门，false=音频已起、画面解冻
     */
    fun setAudioWarmup(active: Boolean) {
        if (audioWarmupActive == active) return
        audioWarmupActive = active
        val binding = bindingProvider() ?: return
        val gate = binding.playbackGateOverlay
        if (active) {
            // 闸门期间隐藏普通缓冲条，避免两层叠在一起。
            showJob?.cancel()
            showJob = null
            binding.bufferingOverlay.animate().cancel()
            binding.bufferingOverlay.visibility = View.GONE
            gate.animate().cancel()
            if (gate.visibility != View.VISIBLE) {
                gate.alpha = 0f
                gate.visibility = View.VISIBLE
            }
            gate.animate().alpha(1f).setDuration(GATE_FADE_IN_MS).start()
        } else if (gate.visibility == View.VISIBLE) {
            gate.animate().cancel()
            gate.animate()
                .alpha(0f)
                .setDuration(GATE_FADE_OUT_MS)
                .withEndAction {
                    if (!audioWarmupActive) {
                        gate.visibility = View.GONE
                        gate.alpha = 1f
                    }
                }
                .start()
        }
    }

    fun recordTransferBytes(bytes: Long) {
        if (!speedTrackingEnabled) return
        speedMeter.addBytes(bytes)
    }

    fun reset() {
        if (audioWarmupActive) return
        showJob?.cancel()
        showJob = null
        bufferingStateStartedAtMs = 0L
        speedTrackingEnabled = false
        speedMeter.reset()
        overlaySuppressedUntilMs = 0L
        overlayEligibleAtMs = 0L
        val binding = bindingProvider() ?: return
        binding.bufferingOverlay.visibility = View.GONE
        binding.tvBuffering.text = context.getString(R.string.player_loading)
    }

    fun onBufferingStarted(
        resetSpeedSample: Boolean,
        trackSpeed: Boolean = true,
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        if (bufferingStateStartedAtMs <= 0L) bufferingStateStartedAtMs = nowMs
        if (resetSpeedSample) speedMeter.reset()
        speedTrackingEnabled = trackSpeed
        update()
    }

    fun suppressFor(durationMs: Long, graceMs: Long) {
        val nowMs = SystemClock.elapsedRealtime()
        val suppressedUntil = nowMs + durationMs.coerceAtLeast(0L) + graceMs.coerceAtLeast(0L)
        if (suppressedUntil > overlaySuppressedUntilMs) {
            overlaySuppressedUntilMs = suppressedUntil
        }
        if (overlayEligibleAtMs < overlaySuppressedUntilMs) {
            overlayEligibleAtMs = overlaySuppressedUntilMs
        }
    }

    fun clearSuppression() {
        overlaySuppressedUntilMs = 0L
        overlayEligibleAtMs = 0L
    }

    fun update() {
        val binding = bindingProvider() ?: return
        if (audioWarmupActive) return
        val nowMs = SystemClock.elapsedRealtime()
        val isBuffering = playbackStateProvider() == Player.STATE_BUFFERING
        val suppressedUntil = overlaySuppressedUntilMs
        if (suppressedUntil > 0L && nowMs < suppressedUntil) {
            showJob?.cancel()
            showJob = null
            if (binding.bufferingOverlay.visibility != View.GONE) {
                binding.bufferingOverlay.visibility = View.GONE
            }
            binding.tvBuffering.text = context.getString(R.string.player_loading)
            if (isBuffering && overlayEligibleAtMs < suppressedUntil) {
                overlayEligibleAtMs = suppressedUntil
            }
            return
        }

        if (!isBuffering) {
            reset()
            return
        }

        if (suppressedUntil > 0L) {
            overlaySuppressedUntilMs = 0L
        }
        if (bufferingStateStartedAtMs <= 0L) {
            bufferingStateStartedAtMs = nowMs
        }

        val bufferingStartedAtMs =
            maxOf(
                bufferingStateStartedAtMs,
                overlayEligibleAtMs.takeIf { it > 0L } ?: 0L,
            )
        val remainingDelayMs = showDelayMs - (nowMs - bufferingStartedAtMs)
        if (remainingDelayMs > 0L) {
            if (binding.bufferingOverlay.visibility != View.VISIBLE) {
                binding.bufferingOverlay.visibility = View.GONE
            }
            binding.tvBuffering.text = context.getString(R.string.player_loading)
            scheduleDelayedUpdate(remainingDelayMs)
            return
        }

        showJob?.cancel()
        showJob = null
        val text =
            speedMeter
                .currentBytesPerSecond(nowMs)
                ?.takeIf { it > 0L }
                ?.let { context.getString(R.string.player_loading_speed, formatTransferBytes(it)) }
                ?: context.getString(R.string.player_loading)

        binding.tvBuffering.text = text
        if (binding.bufferingOverlay.visibility != View.VISIBLE) {
            binding.bufferingOverlay.visibility = View.VISIBLE
        }
    }

    private fun scheduleDelayedUpdate(delayMs: Long) {
        if (showJob?.isActive == true) return
        showJob =
            scope.launch {
                delay(delayMs.coerceAtLeast(1L))
                showJob = null
                update()
            }
    }
}
