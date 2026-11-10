package `is`.xyz.mpv

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

import android.net.Uri
import com.potato.player.BuildConfig
import `is`.xyz.mpv.state.VideoDisplayMode
import `is`.xyz.mpv.state.VideoDisplayState
import `is`.xyz.mpv.state.VideoTransform
import `is`.xyz.mpv.ui.UriUtils

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

data class PlayerUiState(
    val isPlaying: Boolean? = null,
    val currentFileName: String? = null,
    val engineStatus: EngineStatus = EngineStatus.NotStarted,
    val trackState: TrackState = TrackState(),
    // FIX #5: Expose isLoadingNewFile in PlayerUiState
    val isLoadingNewFile: Boolean = false,
) {
    val isEngineReady: Boolean get() = engineStatus is EngineStatus.Ready
}

/** Ingredients PlayerActivity needs to resolve + apply orientation. Dumb data only. */
data class OrientationState(
    val mode: OrientationMode = OrientationMode.AUTO_BY_VIDEO,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoRotationDegrees: Int = 0,
    val manualOverride: OrientationMode? = null,
)

private data class SubAndLangPrefs(
    val status: EngineStatus,
    val fontSize: Int,
    val pos: Int,
    val audioLang: String?,
    val subLang: String?,
)

private data class EngineDisplayState(
    val isReady: Boolean,
    val displayState: VideoDisplayState,
    val geometry: VideoGeometry = VideoGeometry(),
)

internal fun matchesLanguage(trackLang: String?, targetCode: String): Boolean {
    if (trackLang.isNullOrBlank()) return false
    val cleanedTrackLang = trackLang.trim()
    if (cleanedTrackLang.equals(targetCode, ignoreCase = true)) return true
    if (cleanedTrackLang.startsWith("$targetCode-", ignoreCase = true)) return true
    val iso3 = runCatching { java.util.Locale.forLanguageTag(targetCode).isO3Language }.getOrNull()
    if (iso3 != null && cleanedTrackLang.equals(iso3, ignoreCase = true)) return true
    val trackIso2 = runCatching { java.util.Locale.forLanguageTag(cleanedTrackLang).language }.getOrNull()
    if (trackIso2 != null && trackIso2.equals(targetCode, ignoreCase = true)) return true
    return false
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class PlayerViewModel(
    private val appContext: Context,
    coroutineScope: CoroutineScope? = null,
    private val preferencesRepository: UserPreferencesRepository = UserPreferencesRepository(appContext),
) : ViewModel() {

    private val scope = coroutineScope ?: viewModelScope

    private val eventHandler = EngineEventHandler()

    // -- engine is public so PlayScreen can hand it to MpvSurfaceView -------
    // MpvEngine owns pending-file management and coordinates engine/surface readiness.
    // PlayerViewModel delegates playback requests directly to MpvEngine instead of implementing
    // a second pending-file queue.
    // Threading contract: Engine lifecycle, playback, and surface operations must execute on
    // the same designated thread (currently the Android main thread / viewModelScope).
    val engine: MpvEngine = MpvEngine(appContext, eventHandler)

    val trackState: StateFlow<TrackState> = eventHandler.trackState

    private val _explicitFileName = MutableStateFlow<String?>(null)

    // FIX #5: Expose explicit isLoadingNewFile signal
    private val _isLoadingNewFile = MutableStateFlow(false)
    val isLoadingNewFile: StateFlow<Boolean> = _isLoadingNewFile.asStateFlow()

    // Guard flags: set true when the user explicitly selects a track, reset on new file load.
    // Prevents the automatic language-matching collector from overriding a manual selection.
    private var userSelectedSubtitle = false
    private var userSelectedAudio = false

    val uiState: StateFlow<PlayerUiState> = combine(
        _explicitFileName,
        engine.status,
        eventHandler.trackState,
        _isLoadingNewFile,
    ) { explicitName, status, tracks, loadingNewFile ->
        // Precedence rule: An explicitly-set filename (e.g. from loadFile or loadUri)
        // takes precedence over mpv's reported media-title property.
        // mpv's mediaTitle is used as a fallback when explicitName is null or blank.
        val resolvedFileName = if (!explicitName.isNullOrBlank()) explicitName else tracks.mediaTitle

        PlayerUiState(
            isPlaying = tracks.isPaused?.let { !it },
            currentFileName = resolvedFileName,
            engineStatus = status,
            trackState = tracks,
            isLoadingNewFile = loadingNewFile,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = PlayerUiState(),
    )

    // FIX #6: Gate logObserver.logMessage behind BuildConfig.DEBUG
    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            if (BuildConfig.DEBUG) {
                Log.i("mpv-native", "[$prefix] $text".trimEnd())
            }
            AppLogger.logMpv(prefix, level, text)
        }
    }

    val playbackProgress: StateFlow<PlaybackProgress> = eventHandler.playbackProgress

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _videoDisplayState = MutableStateFlow(VideoDisplayState())
    val videoDisplayState: StateFlow<VideoDisplayState> = _videoDisplayState.asStateFlow()

    val currentZoom: StateFlow<Float> = _videoDisplayState
        .map { it.transform.zoom }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, _videoDisplayState.value.transform.zoom)

    val displayMode: StateFlow<VideoDisplayMode> = _videoDisplayState
        .map { it.mode }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, _videoDisplayState.value.mode)

    private val _orientationOverride = MutableStateFlow<OrientationMode?>(null)

    /**
     * Combined state for PlayerActivity's orientation collector.
     * ViewModel does NOT resolve policy here — just exposes the raw ingredients.
     * OrientationController (owned by the Activity) does the resolving.
     */
    val orientationState: StateFlow<OrientationState> = combine(
        preferencesRepository.orientationMode,
        eventHandler.videoGeometry,
        _orientationOverride,
    ) { mode, geometry, override ->
        OrientationState(
            mode = mode,
            videoWidth = geometry.width,
            videoHeight = geometry.height,
            videoRotationDegrees = geometry.rotationDegrees,
            manualOverride = override,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = OrientationState(),
    )

    val subFontSize: StateFlow<Int> = preferencesRepository.subFontSize
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), 55)
    val subPos: StateFlow<Int> = preferencesRepository.subPos
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), 100)
    val preferredAudioLang: StateFlow<String?> = preferencesRepository.preferredAudioLang
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)
    val preferredSubtitleLang: StateFlow<String?> = preferencesRepository.preferredSubtitleLang
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)
    val decoderMode: StateFlow<DecoderMode> = preferencesRepository.decoderMode
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), DecoderMode.AUTO)

    init {
        AppLogger.i(TAG, "PlayerViewModel initialized")
        MPVLib.addLogObserver(logObserver)
        // Centralized collector applying video transform (zoom/pan) and display mode to MPV whenever the engine becomes Ready
        scope.launch {
            combine(engine.status, _videoDisplayState, eventHandler.videoGeometry) { status, displayState, geometry ->
                EngineDisplayState(
                    isReady = status is EngineStatus.Ready,
                    displayState = displayState,
                    geometry = geometry,
                )
            }
                .distinctUntilChanged()
                .filter { it.isReady }
                .collect { state ->
                    engine.setVideoDisplayState(state.displayState)
                }
        }
        // FIX #3: Combine engine.status with committed subtitle and language preferences to re-apply on preference emission when Ready
        scope.launch {
            combine(
                engine.status,
                subFontSize,
                subPos,
                preferredAudioLang,
                preferredSubtitleLang,
            ) { status, fontSize, pos, audioLang, subLang ->
                SubAndLangPrefs(status, fontSize, pos, audioLang, subLang)
            }.collect { (status, fontSize, pos, audioLang, subLang) ->
                if (status is EngineStatus.Ready) {
                    engine.setSubFontSize(fontSize)
                    engine.setSubPos(pos)
                    engine.setAudioLang(audioLang)
                    engine.setSubtitleLang(subLang)
                }
            }
        }
        scope.launch {
            combine(engine.status, preferencesRepository.decoderMode) { status, mode ->
                status to mode
            }.distinctUntilChanged().collect { (status, mode) ->
                if (status is EngineStatus.Ready) {
                    engine.setDecoderMode(mode)
                }
            }
        }
        // alang/slang/forced reselection are hints to mpv and only work when the
        // file's own track metadata carries a matching language tag — no fallback
        // chain needed, let mpv's own selection (or the match in (B)) handle it.
        scope.launch {
            combine(engine.status, preferredAudioLang, eventHandler.trackState) { status, lang, trackState ->
                Triple(status, lang, trackState)
            }
                .distinctUntilChanged { old, new ->
                    old.first == new.first &&
                        old.second == new.second &&
                        old.third.audioTracks == new.third.audioTracks &&
                        old.third.selectedAudioId == new.third.selectedAudioId
                }
                .collect { (status, lang, trackState) ->
                    if (userSelectedAudio) return@collect
                    if (status is EngineStatus.Ready && lang != null) {
                        val matchingTrack = trackState.audioTracks.firstOrNull { track ->
                            matchesLanguage(track.lang, lang)
                        }
                        if (matchingTrack != null && matchingTrack.id != trackState.selectedAudioId) {
                            engine.selectAudioTrack(matchingTrack.id)
                        }
                    }
                }
        }
        scope.launch {
            combine(engine.status, preferredSubtitleLang, eventHandler.trackState) { status, lang, trackState ->
                Triple(status, lang, trackState)
            }
                .distinctUntilChanged { old, new ->
                    old.first == new.first &&
                        old.second == new.second &&
                        old.third.subtitleTracks == new.third.subtitleTracks &&
                        old.third.selectedSubtitleId == new.third.selectedSubtitleId
                }
                .collect { (status, lang, trackState) ->
                    if (userSelectedSubtitle) return@collect
                    if (status is EngineStatus.Ready && lang != null) {
                        val matchingTrack = trackState.subtitleTracks.firstOrNull { track ->
                            matchesLanguage(track.lang, lang)
                        }
                        if (matchingTrack != null && matchingTrack.id != trackState.selectedSubtitleId) {
                            engine.selectSubtitleTrack(matchingTrack.id)
                        }
                    }
                }
        }
        // FIX #5: Clear isLoadingNewFile when first post-load pause or track-list event arrives
        scope.launch {
            eventHandler.trackState.collect { tracks ->
                if (_isLoadingNewFile.value) {
                    if (tracks.isPaused != null || tracks.audioTracks.isNotEmpty() || tracks.subtitleTracks.isNotEmpty()) {
                        _isLoadingNewFile.value = false
                    }
                }
            }
        }
        initEngine()
    }

    // Initializes MpvEngine on viewModelScope (Android main thread), fulfilling the single-thread contract.
    private fun initEngine() {
        scope.launch {
            try {
                val profile = preferencesRepository.playbackProfile.first()
                engine.initialize(
                    configDir = appContext.filesDir.path,
                    cacheDir  = appContext.cacheDir.path,
                    options   = profile.options,
                )
                if (engine.isReady) {
                    Log.i(TAG, "Engine init success with profile: ${profile.name}")
                    AppLogger.i(TAG, "Engine init success with profile: ${profile.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Engine init failed", e)
                AppLogger.e(TAG, "Engine init failed: ${e.message}", e)
            }
        }
    }

    // FIX #7: Handle failure in UriUtils resolution without updating state or calling engine
    fun loadUri(uri: Uri) {
        try {
            val displayName = UriUtils.getDisplayName(appContext, uri)
            val playablePath = UriUtils.resolvePlayablePath(appContext, uri)
            loadFile(playablePath, displayName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve uri: $uri", e)
            AppLogger.e(TAG, "Failed to resolve media URI", e)
        }
    }

    /**
     * Sets file info and delegates playback directly to [MpvEngine.playFile].
     *
     * MpvEngine owns pending-file handling if the engine is not yet Ready or the surface is not yet
     * Attached; PlayerViewModel delegates directly rather than maintaining a second queue.
     * Must be called on the designated main thread.
     */
    // FIX #7: Handle failure in UriUtils resolution without updating state or calling engine
    fun loadFile(path: String, displayName: String? = null) {
        val name = try {
            displayName ?: UriUtils.getDisplayNameFromPath(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve display name", e)
            AppLogger.e(TAG, "Failed to resolve media display name", e)
            return
        }
        Log.i(TAG, "loadFile: name=$name")
        AppLogger.i(TAG, "Loading file: $name")
        _explicitFileName.value = name
        _playbackSpeed.value = 1f
        _videoDisplayState.value = VideoDisplayState()
        _orientationOverride.value = null
        // Reset manual-selection guards so automatic language selection applies to the new file.
        userSelectedSubtitle = false
        userSelectedAudio = false
        // FIX #5: Set isLoadingNewFile true before calling engine.playFile
        _isLoadingNewFile.value = true
        engine.playFile(path)
    }

    fun togglePlayPause() {
        engine.togglePause()
    }

    fun seekTo(positionSec: Double) {
        Log.i(TAG, "seekTo: $positionSec")
        engine.seekTo(positionSec)
    }

    fun seekBy(offsetSec: Double) {
        Log.i(TAG, "seekBy: $offsetSec")
        engine.seekBy(offsetSec)
    }

    fun setSpeed(speed: Double) {
        Log.i(TAG, "setSpeed: $speed")
        _playbackSpeed.value = speed.toFloat()
        engine.setSpeed(speed)
    }

    fun getCurrentSpeed(): Float = _playbackSpeed.value

    fun zoomBy(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val currentTransform = _videoDisplayState.value.transform
        val nextZoom = (currentTransform.zoom * factor).coerceIn(0.5f, 4f)
        _videoDisplayState.update { state ->
            state.copy(
                transform = state.transform.copy(zoom = nextZoom)
            )
        }
    }

    fun toggleDisplayMode() {
        val nextMode = when (_videoDisplayState.value.mode) {
            VideoDisplayMode.ORIGINAL -> VideoDisplayMode.FULL_SCREEN
            VideoDisplayMode.FULL_SCREEN -> VideoDisplayMode.ORIGINAL
        }
        _videoDisplayState.value = VideoDisplayState(
            transform = VideoTransform(),
            mode = nextMode,
        )
    }

    fun selectAudioTrack(id: Int) {
        userSelectedAudio = true
        engine.selectAudioTrack(id)
    }

    fun selectSubtitleTrack(id: Int?) {
        userSelectedSubtitle = true
        engine.selectSubtitleTrack(id)
    }

    fun previewSubFontSize(size: Int) {
        engine.setSubFontSize(size)
    }

    fun commitSubFontSize(size: Int) {
        scope.launch {
            preferencesRepository.setSubFontSize(size)
        }
    }

    fun previewSubPos(pos: Int) {
        engine.setSubPos(pos)
    }

    fun commitSubPos(pos: Int) {
        scope.launch {
            preferencesRepository.setSubPos(pos)
        }
    }

    fun commitAudioLang(code: String?) {
        scope.launch {
            preferencesRepository.setPreferredAudioLang(code)
        }
    }

    fun commitSubtitleLang(code: String?) {
        scope.launch {
            preferencesRepository.setPreferredSubtitleLang(code)
        }
    }

    fun setOrientationMode(mode: OrientationMode) {
        scope.launch {
            preferencesRepository.setOrientationMode(mode)
        }
    }

    fun setDecoderMode(mode: DecoderMode) {
        scope.launch {
            preferencesRepository.setDecoderMode(mode)
        }
    }

    fun toggleOrientationOverride(currentConfigOrientation: () -> Int) {
        if (_orientationOverride.value != null) {
            _orientationOverride.value = null
            return
        }

        _orientationOverride.value =
            if (currentConfigOrientation() ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ) {
                OrientationMode.PORTRAIT
            } else {
                OrientationMode.LANDSCAPE
            }
    }

    // -- Lifecycle -----------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        AppLogger.i(TAG, "PlayerViewModel onCleared: destroying engine")
        MPVLib.removeLogObserver(logObserver)
        engine.destroy()
    }

    // -- Factory -------------------------------------------------------------

    companion object {
        private const val TAG = "PlayerViewModel"

        fun factory(appContext: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerViewModel(appContext.applicationContext) as T
            }
    }
}