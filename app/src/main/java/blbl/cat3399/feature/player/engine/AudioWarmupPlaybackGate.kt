@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player.engine

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import blbl.cat3399.core.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 起播/恢复时的音画闸门：音频输出唤醒完成前冻结视频出帧，并通知 UI 显示过渡遮罩。
 *
 * 解决机顶盒/TV 上 AudioFlinger 或 HDMI 待机后「先出画、后出声」的问题。
 */
internal class AudioWarmupPlaybackGate {
    /** 为 true 时视频渲染器跳过 render，画面停在上一帧（或黑场）。 */
    val videoFrozen: AtomicBoolean = AtomicBoolean(false)

    /** 解冻后尚未放出第一帧：遮罩应继续盖住，避免「动画没了画面还卡一下」。 */
    private val awaitFirstFrameAfterUnfreeze: AtomicBoolean = AtomicBoolean(false)

    @Volatile private var onFirstFrameAfterUnfreeze: (() -> Unit)? = null

    /**
     * 解冻后等待首帧再收遮罩。
     *
     * @param onFirstFrame 播放线程回调：已经强制放出解冻后的第一帧
     */
    fun armAwaitFirstFrame(onFirstFrame: () -> Unit) {
        onFirstFrameAfterUnfreeze = onFirstFrame
        awaitFirstFrameAfterUnfreeze.set(true)
    }

    fun clearAwaitFirstFrame() {
        awaitFirstFrameAfterUnfreeze.set(false)
        onFirstFrameAfterUnfreeze = null
    }

    fun isAwaitingFirstFrame(): Boolean = awaitFirstFrameAfterUnfreeze.get()

    /**
     * 解冻后第一次可出帧时调用：消费等待状态并通知。
     *
     * @return true 表示应强制放出这一帧
     */
    fun tryConsumeFirstFrameAfterUnfreeze(): Boolean {
        if (!awaitFirstFrameAfterUnfreeze.compareAndSet(true, false)) return false
        val cb = onFirstFrameAfterUnfreeze
        onFirstFrameAfterUnfreeze = null
        cb?.invoke()
        return true
    }
}

/** 短暂停再播也需要唤醒；过长会漏掉常见 pause/play。 */
private const val AUDIO_WAKE_IDLE_THRESHOLD_MS = 400L
private const val AUDIO_WAKE_MIN_MS = 120L
private const val AUDIO_WAKE_CONFIRM_MS = 120L
private const val AUDIO_WAKE_TIMEOUT_MS = 900L
private const val AUDIO_WAKE_POLL_MS = 15L
private const val AUDIO_WAKE_RELEASE_DELAY_MS = 400L
private const val AUDIO_FADE_IN_MS = 180L
private const val AUDIO_FADE_STEP_MS = 12L
/** 解冻后若一直等不到首帧，超时仍收遮罩，避免卡死。 */
private const val VIDEO_SETTLE_TIMEOUT_MS = 480L
/** 首帧放出后再稍留一拍，让 Surface 真正换上再淡出遮罩。 */
private const val VIDEO_SETTLE_AFTER_FRAME_MS = 64L

/**
 * 在 AudioSink.play() 前用近乎无声的 AudioTrack 唤醒输出；等待期间冻结视频。
 *
 * @param sink 实际出声的 sink
 * @param gate 与视频渲染器共享的闸门
 * @param onWaitingChanged 主线程可转投的等待状态（true=遮罩/过渡中）
 */
internal class WarmupDelayAudioSink(
    sink: AudioSink,
    private val gate: AudioWarmupPlaybackGate,
    private val onWaitingChanged: (Boolean) -> Unit,
) : ForwardingAudioSink(sink) {
    // 以下状态只在播放线程触碰。
    private var handler: Handler? = null
    private var sampleRateHz: Int = 48_000
    /** 0 表示从未播放过，一律视为空闲需要唤醒。 */
    private var lastInactiveAtMs: Long = 0L
    private var startPending: Boolean = false
    private var wakeStartedAtMs: Long = 0L
    private var wakeTrack: AudioTrack? = null
    private var userVolume: Float = 1f
    private var fading: Boolean = false
    private var fadeStartedAtMs: Long = 0L
    private var uiDismissed: Boolean = true

    private val fadeRunnable =
        object : Runnable {
            override fun run() {
                if (!fading) return
                val t = ((SystemClock.elapsedRealtime() - fadeStartedAtMs).toFloat() / AUDIO_FADE_IN_MS).coerceIn(0f, 1f)
                if (t >= 1f) {
                    fading = false
                    applyVolume(userVolume)
                } else {
                    applyVolume(userVolume * t * t)
                    handler?.postDelayed(this, AUDIO_FADE_STEP_MS)
                }
            }
        }

    private val settleTimeoutRunnable = Runnable { dismissUiIfNeeded(reason = "settle-timeout") }

    private fun applyVolume(volume: Float) = super.setVolume(volume)

    override fun setVolume(volume: Float) {
        userVolume = volume
        if (!fading) super.setVolume(volume)
    }

    private fun cancelFade() {
        if (!fading) return
        fading = false
        handler?.removeCallbacks(fadeRunnable)
        super.setVolume(userVolume)
    }

    private val pollRunnable =
        object : Runnable {
            override fun run() {
                if (!startPending) return
                val elapsedMs = SystemClock.elapsedRealtime() - wakeStartedAtMs
                val consumedFrames = runCatching { wakeTrack?.playbackHeadPosition ?: 0 }.getOrDefault(0)
                val confirmFrames = sampleRateHz * AUDIO_WAKE_CONFIRM_MS / 1000
                val awake = elapsedMs >= AUDIO_WAKE_MIN_MS && consumedFrames >= confirmFrames
                if (awake || elapsedMs >= AUDIO_WAKE_TIMEOUT_MS) {
                    AppLog.i("AudioWake", "output awake after ${elapsedMs}ms consumedFrames=$consumedFrames timeout=${!awake}")
                    finishWakeAndStart(fadeIn = true)
                } else {
                    handler?.postDelayed(this, AUDIO_WAKE_POLL_MS)
                }
            }
        }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (inputFormat.sampleRate > 0) sampleRateHz = inputFormat.sampleRate
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun play() {
        if (startPending) return
        val now = SystemClock.elapsedRealtime()
        val idleMs = if (lastInactiveAtMs == 0L) Long.MAX_VALUE else now - lastInactiveAtMs
        if (idleMs < AUDIO_WAKE_IDLE_THRESHOLD_MS) {
            startNow()
            return
        }
        val track = createWakeTrack()
        if (track == null) {
            startNow()
            return
        }
        AppLog.i("AudioWake", "waking output before play idleMs=${if (idleMs == Long.MAX_VALUE) -1L else idleMs}")
        val h = handler ?: Handler(Looper.myLooper() ?: Looper.getMainLooper()).also { handler = it }
        wakeTrack = track
        wakeStartedAtMs = now
        // 先冻视频，再通知 UI，最后才真正出声。
        gate.clearAwaitFirstFrame()
        gate.videoFrozen.set(true)
        startPending = true
        uiDismissed = false
        onWaitingChanged(true)
        h.postDelayed(pollRunnable, AUDIO_WAKE_POLL_MS)
    }

    /**
     * 唤醒结束：先开音频，再解冻视频；遮罩等到首帧真正放出后再收，盖住解冻瞬间的卡顿。
     */
    private fun finishWakeAndStart(fadeIn: Boolean) {
        startPending = false
        releaseWakeTrackLater()
        startNow(fadeIn = fadeIn)
        val h = handler ?: Handler(Looper.myLooper() ?: Looper.getMainLooper()).also { handler = it }
        gate.videoFrozen.set(false)
        gate.armAwaitFirstFrame {
            // 首帧已强制放出：再留一拍让 Surface 换上，然后淡出遮罩。
            h.removeCallbacks(settleTimeoutRunnable)
            h.postDelayed({ dismissUiIfNeeded(reason = "first-frame") }, VIDEO_SETTLE_AFTER_FRAME_MS)
        }
        h.removeCallbacks(settleTimeoutRunnable)
        h.postDelayed(settleTimeoutRunnable, VIDEO_SETTLE_TIMEOUT_MS)
    }

    private fun dismissUiIfNeeded(reason: String) {
        if (uiDismissed) return
        uiDismissed = true
        gate.clearAwaitFirstFrame()
        handler?.removeCallbacks(settleTimeoutRunnable)
        AppLog.i("AudioWake", "dismiss gate overlay reason=$reason")
        onWaitingChanged(false)
    }

    private fun startNow(fadeIn: Boolean = false) {
        if (fadeIn && handler != null) {
            // 输出刚醒时首包不要爆音，从静音二次曲线爬到用户音量。
            fading = true
            fadeStartedAtMs = SystemClock.elapsedRealtime()
            applyVolume(0f)
            handler?.postDelayed(fadeRunnable, AUDIO_FADE_STEP_MS)
        }
        super.play()
    }

    override fun pause() {
        cancelFade()
        cancelPendingStart()
        lastInactiveAtMs = SystemClock.elapsedRealtime()
        super.pause()
    }

    override fun reset() {
        cancelFade()
        cancelPendingStart()
        lastInactiveAtMs = SystemClock.elapsedRealtime()
        super.reset()
    }

    private fun cancelPendingStart() {
        handler?.removeCallbacks(pollRunnable)
        handler?.removeCallbacks(settleTimeoutRunnable)
        gate.clearAwaitFirstFrame()
        gate.videoFrozen.set(false)
        if (startPending || !uiDismissed) {
            startPending = false
            uiDismissed = true
            onWaitingChanged(false)
        } else {
            startPending = false
        }
        releaseWakeTrack()
    }

    /** 近乎无声（±1 LSB），避免数字静音自动待机仍把输出当活跃。 */
    private fun createWakeTrack(): AudioTrack? {
        return runCatching {
            val rate = sampleRateHz.coerceIn(8_000, 192_000)
            val bytes = rate * 4 // 1s 的 16-bit 立体声
            val pcm = ByteArray(bytes)
            var i = 0
            while (i + 1 < bytes) {
                if ((i / 4) % 2 == 0) {
                    pcm[i] = 1
                    pcm[i + 1] = 0
                } else {
                    pcm[i] = -1
                    pcm[i + 1] = -1
                }
                i += 2
            }
            val track =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            track.write(pcm, 0, bytes)
            track.play()
            track
        }.onFailure { AppLog.w("AudioWake", "create wake track failed", it) }.getOrNull()
    }

    private fun releaseWakeTrack() {
        val track = wakeTrack ?: return
        wakeTrack = null
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    /** 唤醒轨与真正播放轨之间留一点重叠，避免输出再次进待机。 */
    private fun releaseWakeTrackLater() {
        val track = wakeTrack ?: return
        wakeTrack = null
        val release =
            Runnable {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        val h = handler
        if (h != null) h.postDelayed(release, AUDIO_WAKE_RELEASE_DELAY_MS) else release.run()
    }
}

/**
 * 闸门打开时跳过 render（不推进解码出帧）；解冻后强制放出第一帧，再通知收遮罩。
 */
internal class GatedMediaCodecVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
    private val gate: AudioWarmupPlaybackGate,
) : MediaCodecVideoRenderer(
        context,
        codecAdapterFactory,
        mediaCodecSelector,
        allowedJoiningTimeMs,
        enableDecoderFallback,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify,
    ) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        // 冻帧期间不跑 render，避免 shouldDropFrame 狂丢导致解冻后追帧卡顿。
        if (gate.videoFrozen.get()) return
        super.render(positionUs, elapsedRealtimeUs)
    }

    override fun shouldDropFrame(earlyUs: Long, elapsedRealtimeUs: Long, isLastFrame: Boolean): Boolean {
        // 解冻后第一帧必须留下，不能被判 late 丢掉。
        if (gate.isAwaitingFirstFrame()) return false
        return super.shouldDropFrame(earlyUs, elapsedRealtimeUs, isLastFrame)
    }

    override fun shouldForceReleaseFrame(earlyUs: Long, elapsedRealtimeUs: Long): Boolean {
        if (gate.tryConsumeFirstFrameAfterUnfreeze()) return true
        return super.shouldForceReleaseFrame(earlyUs, elapsedRealtimeUs)
    }

    override fun shouldIgnoreFrame(
        earlyUs: Long,
        elapsedRealtimeUs: Long,
        positionUs: Long,
        isLastFrame: Boolean,
        treatDroppedBuffersAsSkipped: Boolean,
    ): Boolean {
        if (gate.isAwaitingFirstFrame()) return false
        return super.shouldIgnoreFrame(
            earlyUs,
            elapsedRealtimeUs,
            positionUs,
            isLastFrame,
            treatDroppedBuffersAsSkipped,
        )
    }
}

/** 供 BlblRenderersFactory 使用的掉帧通知上限，与 DefaultRenderersFactory 默认一致。 */
internal const val GATED_VIDEO_MAX_DROPPED_FRAMES_TO_NOTIFY =
    DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
