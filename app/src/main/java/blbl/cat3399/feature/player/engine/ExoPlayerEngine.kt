@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player.engine

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.ParsingLoadable
import blbl.cat3399.core.api.video.VideoMediaRequestProfile
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.player.CdnFailoverDataSourceFactory
import blbl.cat3399.feature.player.CdnFailoverState
import blbl.cat3399.feature.player.DebugStreamKind
import blbl.cat3399.feature.player.Playable
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToLong

internal data class LiveHlsDebugInfo(
    val mediaSequence: Long?,
    val targetDurationSec: Double?,
    val mapUri: String?,
    val segmentCount: Int,
    val recentSegmentCount: Int,
    val recentBitrateBps: Long?,
    val lastSegmentUri: String?,
    val lastSegmentDurationSec: Double?,
    val lastSegmentBytes: Long?,
    val lastSegmentSequence: Long?,
    val lastAuxRaw: String?,
)

internal class ExoPlayerEngine(
    context: Context,
    private val okHttpClient: OkHttpClient = BiliClient.cdnOkHttp,
    private val onTransferHost: ((kind: DebugStreamKind, host: String) -> Unit)? = null,
    private val onBytesTransferred: ((kind: DebugStreamKind, bytesTransferred: Long) -> Unit)? = null,
    private val onLiveHlsDebugInfo: ((LiveHlsDebugInfo) -> Unit)? = null,
    audioBalanceLevel: AudioBalanceLevel = AudioBalanceLevel.Off,
) : BlblPlayerEngine {
    private val appContext: Context = context.applicationContext
    private val seamlessManifestFile: File = File(appContext.cacheDir, "blbl_seamless_dash_${System.identityHashCode(this)}.mpd")

    private val volumeBalanceProcessor = VolumeBalanceAudioProcessor(level = audioBalanceLevel)
    private val loadControl: DefaultLoadControl =
        DefaultLoadControl.Builder()
            // Keep roughly one forward buffer window behind the playhead so in-buffer seek
            // does not immediately discard media that was already fetched.
            .setBackBuffer(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, true)
            .build()
    private val liveHlsPlaylistParserFactory: HlsPlaylistParserFactory =
        ExtXStartStrippingHlsPlaylistParserFactory(onPlaylistParsed = onLiveHlsDebugInfo)
    private val seamlessQualityController = SeamlessQualitySelectionController()
    private val trackSelector = DefaultTrackSelector(context, SeamlessQualityTrackSelectionFactory(seamlessQualityController))

    val exoPlayer: ExoPlayer =
        ExoPlayer.Builder(
            context,
            BlblRenderersFactory(context.applicationContext, volumeBalanceProcessor) { waiting -> dispatchAudioWarmup(waiting) },
        )
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()

    private val listeners: MutableSet<BlblPlayerEngine.Listener> = CopyOnWriteArraySet()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Called from the playback thread by the audio sink; listeners expect the main thread.
    private fun dispatchAudioWarmup(waiting: Boolean) {
        mainHandler.post { listeners.forEach { it.onAudioWarmupChanged(waiting) } }
    }
    private var seamlessQualitySource: Boolean = false
    private var seamlessAvailableQns: Set<Int> = emptySet()
    private var seamlessAvailableTracks: Set<Pair<Int, Int>> = emptySet()
    private var pendingSeamlessQn: Int? = null
    private var currentSeamlessCodecid: Int? = null
    private var appliedSeamlessQn: Int? = null
    private var appliedSeamlessCodecid: Int? = null
    private var qualitySwitchTargetQn: Int? = null
    private var qualitySwitchStartedAtMs: Long = 0L
    private var qualitySwitchStartedPositionMs: Long = 0L
    private var qualitySwitchFormatChangedAtMs: Long = 0L

    override val kind: PlayerEngineKind = PlayerEngineKind.ExoPlayer
    override val capabilities: EngineCapabilities = EngineCapabilities(subtitlesSupported = true)

    override val playbackState: Int
        get() = exoPlayer.playbackState

    override val isPlaying: Boolean
        get() = exoPlayer.isPlaying

    override var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
        }

    override val duration: Long
        get() = exoPlayer.duration

    override val currentPosition: Long
        get() = exoPlayer.currentPosition

    override val bufferedPosition: Long
        get() = exoPlayer.bufferedPosition

    override fun setVideoSurface(surface: Surface?) {
        // No-op: ExoPlayer renders through PlayerView.
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    override val playbackSpeed: Float
        get() = exoPlayer.playbackParameters.speed

    override fun setPlaybackSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
    }

    override val isSeamlessQualitySource: Boolean
        get() = seamlessQualitySource

    override fun selectVideoQuality(qn: Int): Boolean {
        if (!seamlessQualitySource) {
            AppLog.w("QualitySwitch", "engine reject qn=$qn reason=not_seamless_source available=$seamlessAvailableQns")
            return false
        }
        if (qn !in seamlessAvailableQns) {
            AppLog.w("QualitySwitch", "engine reject qn=$qn reason=qn_not_available available=$seamlessAvailableQns")
            return false
        }
        val activeCodecid = currentSeamlessCodecid
        if (
            activeCodecid != null &&
            (qn to activeCodecid) in seamlessAvailableTracks &&
            isVideoTrackKnownUnsupported(qn = qn, codecid = activeCodecid)
        ) {
            AppLog.w("QualitySwitch", "engine reject qn=$qn codecid=$activeCodecid reason=decoder_unsupported")
            return false
        }
        qualitySwitchTargetQn = qn
        qualitySwitchStartedAtMs = SystemClock.elapsedRealtime()
        qualitySwitchStartedPositionMs = exoPlayer.currentPosition
        qualitySwitchFormatChangedAtMs = 0L
        pendingSeamlessQn = qn
        if (activeCodecid != null && (qn to activeCodecid) in seamlessAvailableTracks) {
            seamlessQualityController.setTarget(qn, activeCodecid)
            AppLog.i(
                "QualitySwitch",
                "controller request qn=$qn codecid=$activeCodecid pos=${exoPlayer.currentPosition} buffered=${exoPlayer.bufferedPosition}",
            )
            return true
        }
        AppLog.i(
            "QualitySwitch",
            "engine accept qn=$qn currentCodec=$currentSeamlessCodecid pos=${exoPlayer.currentPosition} " +
                "buffered=${exoPlayer.bufferedPosition} state=${exoPlayer.playbackState} available=$seamlessAvailableQns",
        )
        applyPendingSeamlessVideoOverride(exoPlayer.currentTracks)
        return true
    }

    private fun isVideoTrackKnownUnsupported(qn: Int, codecid: Int): Boolean {
        var matchingTrackFound = false
        exoPlayer.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
            for (trackIndex in 0 until group.length) {
                val parsed = DashMpdGenerator.parseVideoRepresentationId(group.getTrackFormat(trackIndex).id) ?: continue
                if (parsed.first != qn || parsed.second != codecid) continue
                matchingTrackFound = true
                if (group.isTrackSupported(trackIndex)) return false
            }
        }
        return matchingTrackFound
    }

    override var repeatMode: Int
        get() = exoPlayer.repeatMode
        set(value) {
            exoPlayer.repeatMode = value
        }

    init {
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    listeners.forEach { it.onPlayerError(error) }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listeners.forEach { it.onIsPlayingChanged(isPlaying) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    listeners.forEach { it.onPlaybackStateChanged(playbackState) }
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    AppLog.i(
                        "QualitySwitch",
                        "position discontinuity reason=$reason old=${oldPosition.positionMs} new=${newPosition.positionMs} " +
                            "delta=${newPosition.positionMs - oldPosition.positionMs} targetQn=${qualitySwitchTargetQn ?: -1}",
                    )
                    listeners.forEach { it.onPositionDiscontinuity(newPosition.positionMs) }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    listeners.forEach { it.onVideoSizeChanged(videoSize.width, videoSize.height) }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (qualitySwitchTargetQn != null) {
                        AppLog.i("QualitySwitch", "tracks changed targetQn=$qualitySwitchTargetQn ${describeVideoTracks(tracks)}")
                    }
                    applyPendingSeamlessVideoOverride(tracks)
                }
            },
        )
        exoPlayer.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                    qualitySwitchTargetQn?.let { targetQn ->
                        AppLog.i(
                            "QualitySwitch",
                            "first frame after request targetQn=$targetQn elapsedMs=${SystemClock.elapsedRealtime() - qualitySwitchStartedAtMs} " +
                                "formatToFrameMs=${qualitySwitchFormatChangedAtMs.takeIf { it > 0L }?.let { SystemClock.elapsedRealtime() - it } ?: -1L} " +
                                "requestPos=$qualitySwitchStartedPositionMs currentPos=${exoPlayer.currentPosition}",
                        )
                        qualitySwitchTargetQn = null
                    }
                    listeners.forEach { it.onRenderedFirstFrame() }
                }

                override fun onVideoInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                ) {
                    val (qn, codecid) = DashMpdGenerator.parseVideoRepresentationId(format.id) ?: return
                    val targetQn = qualitySwitchTargetQn
                    AppLog.i(
                        "QualitySwitch",
                        "video format changed qn=$qn codecid=$codecid targetQn=${targetQn ?: -1} id=${format.id} " +
                            "size=${format.width}x${format.height} bitrate=${format.bitrate} " +
                            "reuseResult=${decoderReuseEvaluation?.result ?: -1} discardReasons=${decoderReuseEvaluation?.discardReasons ?: -1} " +
                            "elapsedMs=${if (qualitySwitchStartedAtMs > 0L) SystemClock.elapsedRealtime() - qualitySwitchStartedAtMs else -1L} " +
                            "requestPos=$qualitySwitchStartedPositionMs currentPos=${exoPlayer.currentPosition}",
                    )
                    if (targetQn == qn) qualitySwitchFormatChangedAtMs = SystemClock.elapsedRealtime()
                    currentSeamlessCodecid = codecid
                    listeners.forEach { it.onVideoTrackChanged(qn = qn, codecid = codecid) }
                }

                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                ) {
                    AppLog.i(
                        "QualitySwitch",
                        "audio format changed id=${format.id} mime=${format.sampleMimeType} codecs=${format.codecs} " +
                            "bitrate=${format.bitrate} channels=${format.channelCount} sampleRate=${format.sampleRate}",
                    )
                }
            },
        )
    }

    override fun setSource(source: PlaybackSource) {
        AppLog.i(
            "QualitySwitch",
            "setSource type=${source.javaClass.simpleName} seamlessRequested=${(source as? PlaybackSource.Vod)?.seamlessQualitySwitchEnabled == true} " +
                "playable=${(source as? PlaybackSource.Vod)?.playable?.javaClass?.simpleName ?: "-"}",
        )
        seamlessQualitySource = false
        seamlessAvailableQns = emptySet()
        seamlessAvailableTracks = emptySet()
        seamlessQualityController.setTarget(0, 0)
        pendingSeamlessQn = null
        currentSeamlessCodecid = null
        appliedSeamlessQn = null
        appliedSeamlessCodecid = null
        qualitySwitchTargetQn = null
        qualitySwitchStartedAtMs = 0L
        qualitySwitchStartedPositionMs = 0L
        qualitySwitchFormatChangedAtMs = 0L
        when (source) {
            is PlaybackSource.Vod -> {
                val dash = source.playable as? Playable.Dash
                AppLog.i(
                    "QualitySwitch",
                    "source eligibility enabled=${source.seamlessQualitySwitchEnabled} dash=${dash != null} " +
                        "representations=${dash?.videoRepresentations?.size ?: 0} " +
                        "segmentRepresentations=${dash?.videoRepresentations?.count { it.videoTrackInfo.segmentBase != null } ?: 0} " +
                        "qns=${dash?.videoRepresentations?.map { it.qn }?.distinct().orEmpty()} " +
                        "audioSegmentBase=${dash?.audioTrackInfo?.segmentBase != null}",
                )
                val seamlessApplied =
                    if (source.seamlessQualitySwitchEnabled && dash != null) {
                        runCatching {
                            setSeamlessVodDash(
                                dash = dash,
                                subtitle = source.subtitle,
                                durationMs = source.durationMs,
                                initialPositionMs = source.initialPositionMs,
                            )
                        }.onFailure { throwable ->
                            AppLog.w("QualitySwitch", "build seamless DASH source failed; fallback to legacy source", throwable)
                        }.isSuccess
                    } else {
                        false
                    }
                if (!seamlessApplied) {
                    AppLog.w("QualitySwitch", "using legacy source seamlessRequested=${source.seamlessQualitySwitchEnabled} dash=${dash != null}")
                    setVodPlayable(
                        playable = source.playable,
                        subtitle = source.subtitle,
                        initialPositionMs = source.initialPositionMs,
                    )
                }
            }

            is PlaybackSource.Live -> {
                val url = source.url.trim()
                val uri = Uri.parse(url)
                val factory = createCdnFactory(DebugStreamKind.MAIN, urlCandidates = listOf(url))

                val isM3u8 = url.substringBefore('?').trim().lowercase(Locale.US).endsWith(".m3u8")
                if (isM3u8) {
                    val hlsSource =
                        HlsMediaSource.Factory(factory)
                            .setPlaylistParserFactory(liveHlsPlaylistParserFactory)
                            .createMediaSource(MediaItem.fromUri(uri))
                    exoPlayer.setMediaSource(hlsSource)
                } else {
                    val mediaSourceFactory = DefaultMediaSourceFactory(factory)
                    val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(uri))
                    exoPlayer.setMediaSource(mediaSource)
                }
            }
        }
    }

    override fun prepare() {
        exoPlayer.prepare()
    }

    override fun play() {
        exoPlayer.play()
    }

    override fun pause() {
        exoPlayer.pause()
    }

    override fun stop() {
        exoPlayer.stop()
    }

    override fun release() {
        exoPlayer.release()
    }

    override fun addListener(listener: BlblPlayerEngine.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: BlblPlayerEngine.Listener) {
        listeners.remove(listener)
    }

    fun setAudioBalanceLevel(level: AudioBalanceLevel) {
        volumeBalanceProcessor.setLevel(level)
    }

    private fun createCdnFactory(
        kind: DebugStreamKind,
        urlCandidates: List<String>? = null,
        mediaRequestProfile: VideoMediaRequestProfile = VideoMediaRequestProfile.WEB,
    ): DataSource.Factory {
        val listener =
            object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    val host = dataSpec.uri.host?.trim().orEmpty()
                    if (host.isBlank()) return
                    onTransferHost?.invoke(kind, host.lowercase(Locale.US))
                }

                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                    if (!isNetwork || bytesTransferred <= 0) return
                    onBytesTransferred?.invoke(kind, bytesTransferred.toLong())
                }

                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            }

        val client =
            when (mediaRequestProfile) {
                VideoMediaRequestProfile.WEB -> okHttpClient
                VideoMediaRequestProfile.APP -> BiliClient.appCdnOkHttp
            }
        val upstream = OkHttpDataSource.Factory(client).setTransferListener(listener)
        val uris =
            urlCandidates
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { Uri.parse(it) }
        if (uris.size <= 1) return upstream
        return CdnFailoverDataSourceFactory(upstreamFactory = upstream, state = CdnFailoverState(kind = kind, candidates = uris))
    }

    private fun buildMerged(
        videoFactory: DataSource.Factory,
        audioFactory: DataSource.Factory,
        videoUrl: String,
        audioUrl: String,
        subtitle: MediaItem.SubtitleConfiguration?,
    ): MediaSource {
        val subs = listOfNotNull(subtitle)
        val videoSource =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, videoFactory))
                .createMediaSource(
                    MediaItem.Builder().setUri(Uri.parse(videoUrl)).setSubtitleConfigurations(subs).build(),
                )
        val audioSource =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, audioFactory))
                .createMediaSource(
                    MediaItem.Builder().setUri(Uri.parse(audioUrl)).build(),
                )
        return MergingMediaSource(true, true, videoSource, audioSource)
    }

    private fun buildProgressive(factory: DataSource.Factory, url: String, subtitle: MediaItem.SubtitleConfiguration?): MediaSource {
        val subs = listOfNotNull(subtitle)
        val item =
            MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setSubtitleConfigurations(subs)
                .build()
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, factory)).createMediaSource(item)
    }

    private fun setSeamlessVodDash(
        dash: Playable.Dash,
        subtitle: MediaItem.SubtitleConfiguration?,
        durationMs: Long?,
        initialPositionMs: Long?,
    ) {
        val videos = dash.videoRepresentations.filter { it.videoTrackInfo.segmentBase != null }
        require(videos.size > 1) { "Seamless DASH needs at least two video representations" }
        require(videos.any { it.qn == dash.qn && it.codecid == dash.codecid }) { "Selected DASH video representation is missing" }
        require(dash.audioTrackInfo.segmentBase != null) { "Selected DASH audio representation is missing SegmentBase" }

        val routeFactories = LinkedHashMap<String, DataSource.Factory>()
        videos.forEach { video ->
            routeFactories[video.videoUrl] =
                createCdnFactory(
                    kind = DebugStreamKind.VIDEO,
                    urlCandidates = video.videoUrlCandidates,
                    mediaRequestProfile = video.videoMediaRequestProfile,
                )
        }
        routeFactories[dash.audioUrl] =
            createCdnFactory(
                kind = DebugStreamKind.AUDIO,
                urlCandidates = dash.audioUrlCandidates,
                mediaRequestProfile = dash.audioMediaRequestProfile,
            )

        val routedHttpFactory =
            RoutedHttpDataSourceFactory(
                routeFactories = routeFactories,
                fallbackFactory = createCdnFactory(DebugStreamKind.MAIN),
            )
        val dataSourceFactory = DefaultDataSource.Factory(appContext, routedHttpFactory)
        val mpd = DashMpdGenerator.buildAdaptiveOnDemandMpd(dash = dash, durationMs = durationMs)
        seamlessManifestFile.writeText(mpd, Charsets.UTF_8)
        val item =
            MediaItem.Builder()
                .setUri(Uri.fromFile(seamlessManifestFile))
                .setMimeType(MimeTypes.APPLICATION_MPD)
                .setSubtitleConfigurations(listOfNotNull(subtitle))
                .build()

        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
        setMediaSource(
            mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(item),
            initialPositionMs = initialPositionMs,
        )
        seamlessQualitySource = true
        seamlessAvailableQns = videos.mapTo(LinkedHashSet()) { it.qn }
        seamlessAvailableTracks = videos.mapTo(LinkedHashSet()) { it.qn to it.codecid }
        pendingSeamlessQn = dash.qn
        currentSeamlessCodecid = dash.codecid
        seamlessQualityController.setTarget(dash.qn, dash.codecid)
        AppLog.i(
            "QualitySwitch",
            "seamless DASH source ready qns=${seamlessAvailableQns.toList()} representations=${videos.size} " +
                "allRepresentations=${dash.videoRepresentations.size} initialQn=${dash.qn} codecid=${dash.codecid} " +
                "audioSegmentBase=true",
        )
    }

    private fun applyPendingSeamlessVideoOverride(tracks: Tracks) {
        val targetQn = pendingSeamlessQn ?: return
        if (!seamlessQualitySource) return
        val activeCodecid = currentSeamlessCodecid
        if (activeCodecid != null && (targetQn to activeCodecid) in seamlessAvailableTracks) {
            AppLog.i("QualitySwitch", "controller keeps sample stream qn=$targetQn codecid=$activeCodecid")
            return
        }

        data class Candidate(
            val group: Tracks.Group,
            val trackIndex: Int,
            val qn: Int,
            val codecid: Int,
        )

        val candidates = ArrayList<Candidate>()
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
            for (trackIndex in 0 until group.length) {
                val (qn, codecid) = DashMpdGenerator.parseVideoRepresentationId(group.getTrackFormat(trackIndex).id) ?: continue
                if (qn == targetQn && group.isTrackSupported(trackIndex)) {
                    candidates += Candidate(group = group, trackIndex = trackIndex, qn = qn, codecid = codecid)
                }
            }
        }
        val candidate =
            candidates.firstOrNull { it.codecid == currentSeamlessCodecid }
                ?: candidates.firstOrNull()
                ?: run {
                    AppLog.w(
                        "QualitySwitch",
                        "override missing targetQn=$targetQn currentCodec=$currentSeamlessCodecid ${describeVideoTracks(tracks)}",
                    )
                    return
                }
        if (appliedSeamlessQn == candidate.qn && appliedSeamlessCodecid == candidate.codecid) {
            AppLog.i("QualitySwitch", "override unchanged qn=${candidate.qn} codecid=${candidate.codecid}")
            return
        }

        val override = TrackSelectionOverride(candidate.group.mediaTrackGroup, listOf(candidate.trackIndex))
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(override)
                .build()
        appliedSeamlessQn = candidate.qn
        appliedSeamlessCodecid = candidate.codecid
        AppLog.i(
            "QualitySwitch",
            "override applied qn=${candidate.qn} codecid=${candidate.codecid} trackIndex=${candidate.trackIndex} " +
                "pos=${exoPlayer.currentPosition} buffered=${exoPlayer.bufferedPosition} state=${exoPlayer.playbackState}",
        )
    }

    private fun describeVideoTracks(tracks: Tracks): String {
        val groups =
            tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.mapIndexed { groupIndex, group ->
                val formats =
                    (0 until group.length).joinToString(prefix = "[", postfix = "]") { trackIndex ->
                        val format = group.getTrackFormat(trackIndex)
                        val parsed = DashMpdGenerator.parseVideoRepresentationId(format.id)
                        "id=${format.id}:${parsed?.first ?: -1}/${parsed?.second ?: -1}:supported=${group.isTrackSupported(trackIndex)}:" +
                            "selected=${group.isTrackSelected(trackIndex)}"
                    }
                "g$groupIndex$formats"
            }
        return "videoGroups=${groups.joinToString()}"
    }

    private fun setVodPlayable(
        playable: Playable,
        subtitle: MediaItem.SubtitleConfiguration?,
        initialPositionMs: Long?,
    ) {
        when (playable) {
            is Playable.Dash -> {
                val videoFactory =
                    createCdnFactory(
                        DebugStreamKind.VIDEO,
                        urlCandidates = playable.videoUrlCandidates,
                        mediaRequestProfile = playable.videoMediaRequestProfile,
                    )
                val audioFactory =
                    createCdnFactory(
                        DebugStreamKind.AUDIO,
                        urlCandidates = playable.audioUrlCandidates,
                        mediaRequestProfile = playable.audioMediaRequestProfile,
                    )
                setMediaSource(
                    mediaSource = buildMerged(videoFactory, audioFactory, playable.videoUrl, playable.audioUrl, subtitle),
                    initialPositionMs = initialPositionMs,
                )
            }

            is Playable.VideoOnly -> {
                val mainFactory =
                    createCdnFactory(
                        DebugStreamKind.MAIN,
                        urlCandidates = playable.videoUrlCandidates,
                        mediaRequestProfile = playable.videoMediaRequestProfile,
                    )
                setMediaSource(
                    mediaSource = buildProgressive(mainFactory, playable.videoUrl, subtitle),
                    initialPositionMs = initialPositionMs,
                )
            }

            is Playable.Progressive -> {
                val mainFactory =
                    createCdnFactory(
                        DebugStreamKind.MAIN,
                        urlCandidates = playable.urlCandidates,
                        mediaRequestProfile = playable.mediaRequestProfile,
                    )
                setMediaSource(
                    mediaSource = buildProgressive(mainFactory, playable.url, subtitle),
                    initialPositionMs = initialPositionMs,
                )
            }
        }
    }

    private fun setMediaSource(mediaSource: MediaSource, initialPositionMs: Long?) {
        val initialPosition = initialPositionMs?.takeIf { it > 0L }
        if (initialPosition != null) {
            exoPlayer.setMediaSource(mediaSource, initialPosition)
        } else {
            exoPlayer.setMediaSource(mediaSource)
        }
    }
}

private class RoutedHttpDataSourceFactory(
    private val routeFactories: Map<String, DataSource.Factory>,
    private val fallbackFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutedHttpDataSource(routeFactories, fallbackFactory)
}

private class RoutedHttpDataSource(
    private val routeFactories: Map<String, DataSource.Factory>,
    private val fallbackFactory: DataSource.Factory,
) : DataSource {
    private val transferListeners = ArrayList<TransferListener>(2)
    private var upstream: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val factory = routeFactories[dataSpec.uri.toString()] ?: fallbackFactory
        val dataSource = factory.createDataSource().also { dataSource ->
            transferListeners.forEach(dataSource::addTransferListener)
            upstream = dataSource
        }
        return try {
            dataSource.open(dataSpec)
        } catch (throwable: Throwable) {
            close()
            throw throwable
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return checkNotNull(upstream) { "read() before open()" }.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream?.uri

    override fun close() {
        runCatching { upstream?.close() }
        upstream = null
    }
}

private class ExtXStartStrippingHlsPlaylistParserFactory(
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
    private val onPlaylistParsed: ((LiveHlsDebugInfo) -> Unit)? = null,
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        return ExtXStartStrippingParser(delegate.createPlaylistParser(), onPlaylistParsed = onPlaylistParsed)
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> {
        return ExtXStartStrippingParser(
            delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist),
            onPlaylistParsed = onPlaylistParsed,
        )
    }
}

private class ExtXStartStrippingParser(
    private val delegate: ParsingLoadable.Parser<HlsPlaylist>,
    private val onPlaylistParsed: ((LiveHlsDebugInfo) -> Unit)? = null,
) : ParsingLoadable.Parser<HlsPlaylist> {
    override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
        val bytes = inputStream.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        parseLiveHlsDebugInfo(text = text)?.let { info -> onPlaylistParsed?.invoke(info) }
        if (!text.contains("#EXT-X-START", ignoreCase = true)) {
            return delegate.parse(uri, ByteArrayInputStream(bytes))
        }

        val filtered =
            text
                .lineSequence()
                .filterNot { it.trimStart().startsWith("#EXT-X-START", ignoreCase = true) }
                .joinToString("\n")
        return delegate.parse(uri, ByteArrayInputStream(filtered.toByteArray(Charsets.UTF_8)))
    }
}

private data class ParsedLiveHlsSegment(
    val durationSec: Double,
    val uri: String,
    val bytes: Long?,
    val auxRaw: String?,
)

private fun parseLiveHlsDebugInfo(text: String): LiveHlsDebugInfo? {
    var mediaSequence: Long? = null
    var targetDurationSec: Double? = null
    var mapUri: String? = null
    var pendingDurationSec: Double? = null
    var pendingAuxRaw: String? = null
    val segments = ArrayList<ParsedLiveHlsSegment>()

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isBlank()) continue
        when {
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                mediaSequence = line.substringAfter(':').trim().toLongOrNull()
            }

            line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                targetDurationSec = line.substringAfter(':').trim().toDoubleOrNull()
            }

            line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                mapUri = parseHlsQuotedAttr(line = line, key = "URI")
            }

            line.startsWith("#EXT-BILI-AUX:", ignoreCase = true) -> {
                pendingAuxRaw = line.substringAfter(':').trim().ifBlank { null }
            }

            line.startsWith("#EXTINF:", ignoreCase = true) -> {
                pendingDurationSec = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
            }

            line.startsWith("#") -> Unit

            else -> {
                val durationSec = pendingDurationSec
                if (durationSec != null && durationSec > 0.0) {
                    segments +=
                        ParsedLiveHlsSegment(
                            durationSec = durationSec,
                            uri = line,
                            bytes = parseLiveHlsAuxBytes(pendingAuxRaw),
                            auxRaw = pendingAuxRaw,
                        )
                }
                pendingDurationSec = null
                pendingAuxRaw = null
            }
        }
    }

    val last = segments.lastOrNull()
    val recentSegments = segments.takeLast(LIVE_HLS_DEBUG_RECENT_SEGMENT_COUNT)
    val recentWithBytes = recentSegments.filter { it.bytes != null && it.durationSec > 0.0 }
    val recentDurationSec = recentWithBytes.sumOf { it.durationSec }
    val recentBytes = recentWithBytes.sumOf { it.bytes ?: 0L }
    val recentBitrateBps =
        if (recentBytes > 0L && recentDurationSec > 0.0) {
            ((recentBytes * 8.0) / recentDurationSec).roundToLong()
        } else {
            null
        }
    val lastSegmentSequence =
        if (mediaSequence != null && last != null) {
            mediaSequence + segments.lastIndex.toLong()
        } else {
            null
        }

    val info =
        LiveHlsDebugInfo(
            mediaSequence = mediaSequence,
            targetDurationSec = targetDurationSec,
            mapUri = mapUri,
            segmentCount = segments.size,
            recentSegmentCount = recentWithBytes.size,
            recentBitrateBps = recentBitrateBps,
            lastSegmentUri = last?.uri,
            lastSegmentDurationSec = last?.durationSec,
            lastSegmentBytes = last?.bytes,
            lastSegmentSequence = lastSegmentSequence,
            lastAuxRaw = last?.auxRaw,
        )
    return if (
        info.mediaSequence != null ||
        info.targetDurationSec != null ||
        !info.mapUri.isNullOrBlank() ||
        !info.lastSegmentUri.isNullOrBlank() ||
        !info.lastAuxRaw.isNullOrBlank()
    ) {
        info
    } else {
        null
    }
}

private fun parseHlsQuotedAttr(line: String, key: String): String? {
    val pattern = Regex("""(?:^|,)${Regex.escape(key)}=\"([^\"]+)\"""")
    return pattern.find(line)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
}

private fun parseLiveHlsAuxBytes(auxRaw: String?): Long? {
    val parts = auxRaw?.split('|') ?: return null
    val value = parts.getOrNull(2)?.trim().orEmpty()
    if (value.isBlank()) return null
    return value.toLongOrNull()
        ?: value.toLongOrNull(radix = 16)
}

private const val LIVE_HLS_DEBUG_RECENT_SEGMENT_COUNT = 3
private const val AUDIO_TRACK_MIN_BUFFER_US = 120_000
private const val AUDIO_TRACK_MAX_BUFFER_US = 300_000
private const val AUDIO_TRACK_BUFFER_MULTIPLIER = 2
// Audio output idle-standby handling (see WakeBeforePlayAudioSink).
private const val AUDIO_WAKE_IDLE_THRESHOLD_MS = 1_500L
private const val AUDIO_WAKE_MIN_MS = 120L
private const val AUDIO_WAKE_CONFIRM_MS = 120L
private const val AUDIO_WAKE_TIMEOUT_MS = 900L
private const val AUDIO_WAKE_POLL_MS = 15L
private const val AUDIO_WAKE_RELEASE_DELAY_MS = 400L
private const val AUDIO_FADE_IN_MS = 180L
private const val AUDIO_FADE_STEP_MS = 12L

private class BlblRenderersFactory(
    context: Context,
    private val volumeBalanceProcessor: VolumeBalanceAudioProcessor,
    private val onAudioWarmupChanged: (Boolean) -> Unit,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        // The default AudioTrack buffer (250ms min, x4 multiplier, 750ms max) is large on TV/box hardware.
        // After first play / resume from pause the sink has to fill it before sound comes out, while video
        // already advances, so audio is heard noticeably later than the picture. Use a smaller buffer.
        val bufferSizeProvider =
            DefaultAudioTrackBufferSizeProvider.Builder()
                .setMinPcmBufferDurationUs(AUDIO_TRACK_MIN_BUFFER_US)
                .setMaxPcmBufferDurationUs(AUDIO_TRACK_MAX_BUFFER_US)
                .setPcmBufferMultiplicationFactor(AUDIO_TRACK_BUFFER_MULTIPLIER)
                .build()
        val sink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(volumeBalanceProcessor))
                .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                .build()
        return WarmupDelayAudioSink(sink, onAudioWarmupChanged)
    }
}

/**
 * Fix for "sound comes out later than the picture" on devices whose audio output goes to standby when idle
 * (AudioFlinger puts the output thread to standby ~3s after the last active track; HDMI/TV sinks also re-sync).
 *
 * Wake before play: when the sink has been idle for a while (first start, switching episodes, long pause), the
 *    first part of the audio is swallowed while the output wakes up, but the playback position already advances,
 *    so the picture runs ahead. Before really starting the sink we play a near-silent AudioTrack on the same
 *    output, wait until it is actually being consumed, then start the real track. Position is frozen meanwhile, so
 *    picture and sound start together.
 */
private class WarmupDelayAudioSink(
    sink: AudioSink,
    private val onWaitingChanged: (Boolean) -> Unit,
) : ForwardingAudioSink(sink) {
    // Only touched on the playback thread.
    private var handler: Handler? = null
    private var sampleRateHz: Int = 48_000
    private var lastInactiveAtMs: Long = 0L // 0 = never played: treat as idle.
    private var startPending: Boolean = false
    private var wakeStartedAtMs: Long = 0L
    private var wakeTrack: AudioTrack? = null
    private var userVolume: Float = 1f
    private var fading: Boolean = false
    private var fadeStartedAtMs: Long = 0L

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
                    startPending = false
                    onWaitingChanged(false)
                    releaseWakeTrackLater()
                    startNow(fadeIn = true)
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
        startPending = true
        onWaitingChanged(true)
        h.postDelayed(pollRunnable, AUDIO_WAKE_POLL_MS)
    }

    private fun startNow(fadeIn: Boolean = false) {
        if (fadeIn && handler != null) {
            // Start silent and ramp up, so the first audible samples after the output wakes are not abrupt.
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
        if (startPending) {
            startPending = false
            handler?.removeCallbacks(pollRunnable)
            onWaitingChanged(false)
        }
        releaseWakeTrack()
    }

    /** Near-silent (+-1 LSB, inaudible) so sinks with digital-silence auto-mute still treat the output as active. */
    private fun createWakeTrack(): AudioTrack? {
        return runCatching {
            val rate = sampleRateHz.coerceIn(8_000, 192_000)
            val bytes = rate * 4 // 1s of 16-bit stereo
            val pcm = ByteArray(bytes)
            var i = 0
            while (i + 1 < bytes) {
                // little-endian 16-bit: alternate +1 / -1
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

    /** Keep the wake track alive briefly so the output never goes idle between it and the real track. */
    private fun releaseWakeTrackLater() {
        val track = wakeTrack ?: return
        wakeTrack = null
        val release = Runnable {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        val h = handler
        if (h != null) h.postDelayed(release, AUDIO_WAKE_RELEASE_DELAY_MS) else release.run()
    }
}
