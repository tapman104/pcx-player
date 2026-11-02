package `is`.xyz.mpv

import android.util.Log
import com.potato.player.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Snapshot of video geometry, as last reported by mpv.
 * All zero/default until the first file has loaded and reported dimensions.
 */
data class VideoGeometry(
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
)

data class PlaybackProgress(
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
)

enum class TrackType {
    Audio,
    Subtitle
}

data class Track(
    val id: Int,
    val type: TrackType,
    val lang: String? = null,
    val title: String? = null,
    val isSelected: Boolean = false,
)

data class TrackState(
    val audioTracks: List<Track> = emptyList(),
    val subtitleTracks: List<Track> = emptyList(),
    val selectedAudioId: Int? = null,
    val selectedSubtitleId: Int? = null,
    val isPaused: Boolean? = null,
    val mediaTitle: String? = null,
)

/**
 * Handles raw MPV property-change events for audio and subtitle tracks ("track-list", "aid", "sid"),
 * playback pause and media-title events ("pause", "media-title"), video geometry ("width", "height",
 * "video-rotate"), and playback progress ("time-pos", "duration"), translating them into typed,
 * observable StateFlows.
 *
 * Precedence note on mediaTitle:
 * EngineEventHandler acts solely as an event translation layer for MPV native events and does not
 * track user-chosen filenames or enforce UI display precedence. The precedence rule ("an explicitly-set
 * filename takes priority over mpv's reported media-title property") is preserved and enforced in
 * PlayerViewModel when combining UI-provided file names with this state.
 */
class EngineEventHandler : MPVLib.EventObserver {

    /** Called whenever mpv reports a change to width, height, or rotation. */
    var onVideoGeometryChanged: ((VideoGeometry) -> Unit)? = null

    /** Called whenever mpv reports a change to position or duration. */
    var onPlaybackProgressChanged: ((positionSec: Double, durationSec: Double) -> Unit)? = null

    private val _trackState = MutableStateFlow(TrackState())
    val trackState: StateFlow<TrackState> = _trackState.asStateFlow()

    private val _videoGeometry = MutableStateFlow(VideoGeometry())
    val videoGeometry: StateFlow<VideoGeometry> = _videoGeometry.asStateFlow()
    val geometry: StateFlow<VideoGeometry> get() = videoGeometry

    private val _playbackProgress = MutableStateFlow(PlaybackProgress())
    val playbackProgress: StateFlow<PlaybackProgress> = _playbackProgress.asStateFlow()
    val progress: StateFlow<PlaybackProgress> get() = playbackProgress

    var currentGeometry: VideoGeometry = VideoGeometry()
        private set

    var currentPositionSec: Double = 0.0
        private set

    var currentDurationSec: Double = 0.0
        private set

    override fun eventProperty(property: String, value: MPVNode) {
        if (property == "track-list") {
            handleTrackListNode(value)
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") {
            Log.d(TAG, "Playback pause state updated from mpv: isPaused=$value")
            _trackState.update { it.copy(isPaused = value) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "aid" -> handleAid(value)
            "sid" -> handleSid(value)
            "media-title" -> {
                _trackState.update { it.copy(mediaTitle = value) }
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        if (onPropertyLong(property, value)) return
        when (property) {
            "aid" -> handleAid(value.toString())
            "sid" -> handleSid(value.toString())
        }
    }

    override fun eventProperty(property: String, value: Double) {
        onPropertyDouble(property, value)
    }

    /**
     * Feed a geometry-relevant property update in.
     *
     * Returns true if the property was consumed (was a geometry property).
     */
    fun onPropertyLong(property: String, value: Long): Boolean {
        val next = when (property) {
            "width" -> currentGeometry.copy(width = value.toInt())
            "height" -> currentGeometry.copy(height = value.toInt())
            "video-rotate" -> currentGeometry.copy(rotationDegrees = value.toInt())
            else -> return false
        }
        currentGeometry = next
        _videoGeometry.value = next
        onVideoGeometryChanged?.invoke(next)
        return true
    }

    /**
     * Feed a progress-relevant property update in.
     *
     * Returns true if the property was consumed.
     */
    fun onPropertyDouble(property: String, value: Double): Boolean {
        when (property) {
            "time-pos" -> currentPositionSec = value
            "duration" -> currentDurationSec = value
            else -> return false
        }
        val next = PlaybackProgress(currentPositionSec, currentDurationSec)
        _playbackProgress.value = next
        onPlaybackProgressChanged?.invoke(currentPositionSec, currentDurationSec)
        return true
    }

    /** Reset geometry when a new file starts loading, so stale dims aren't briefly reused. */
    fun clearGeometry() {
        val reset = VideoGeometry()
        currentGeometry = reset
        _videoGeometry.value = reset
        onVideoGeometryChanged?.invoke(reset)
    }

    /** Reset progress when a new file starts loading. */
    fun clearProgress() {
        currentPositionSec = 0.0
        currentDurationSec = 0.0
        val reset = PlaybackProgress(0.0, 0.0)
        _playbackProgress.value = reset
        onPlaybackProgressChanged?.invoke(0.0, 0.0)
    }

    fun reset() {
        Log.d(TAG, "Resetting track, geometry, and playback state")
        _trackState.value = TrackState()
        currentGeometry = VideoGeometry()
        currentPositionSec = 0.0
        currentDurationSec = 0.0
        _videoGeometry.value = VideoGeometry()
        _playbackProgress.value = PlaybackProgress()
    }

    fun handleTrackListNode(node: MPVNode) {
        val rawTracks = parseTrackList(node)
        val audio = rawTracks.filter { it.type == TrackType.Audio }
        val subs = rawTracks.filter { it.type == TrackType.Subtitle }

        // FIX #6: Gate per-track Log.i loop behind BuildConfig.DEBUG
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Parsed ${audio.size} audio tracks, ${subs.size} subtitle tracks from track-list node")
            for (track in audio) {
                Log.i(TAG, "  Audio track: id=${track.id}, lang=${track.lang}, title=${track.title}, selected=${track.isSelected}")
            }
            for (track in subs) {
                Log.i(TAG, "  Subtitle track: id=${track.id}, lang=${track.lang}, title=${track.title}, selected=${track.isSelected}")
            }
        }

        _trackState.update { current ->
            val resolvedAudioId = if (current.selectedAudioId != null && audio.any { it.id == current.selectedAudioId }) {
                current.selectedAudioId
            } else {
                audio.firstOrNull { it.isSelected }?.id
            }

            val resolvedSubId = if (current.selectedSubtitleId != null && subs.any { it.id == current.selectedSubtitleId }) {
                current.selectedSubtitleId
            } else {
                subs.firstOrNull { it.isSelected }?.id
            }

            val finalAudio = audio.map { track ->
                track.copy(isSelected = (resolvedAudioId != null && track.id == resolvedAudioId))
            }
            val finalSubs = subs.map { track ->
                track.copy(isSelected = (resolvedSubId != null && track.id == resolvedSubId))
            }

            current.copy(
                audioTracks = finalAudio,
                subtitleTracks = finalSubs,
                selectedAudioId = resolvedAudioId,
                selectedSubtitleId = resolvedSubId,
            )
        }
    }

    private fun handleAid(value: String) {
        val audioId = value.toIntOrNull() // "no" or invalid becomes null
        Log.i(TAG, "Audio track changed (aid): raw='$value', resolvedId=$audioId")
        _trackState.update { current ->
            val updatedAudio = current.audioTracks.map { track ->
                track.copy(isSelected = (audioId != null && track.id == audioId))
            }
            current.copy(
                audioTracks = updatedAudio,
                selectedAudioId = audioId,
            )
        }
    }

    private fun handleSid(value: String) {
        val subId = value.toIntOrNull() // "no" or invalid becomes null
        Log.i(TAG, "Subtitle track changed (sid): raw='$value', resolvedId=$subId")
        _trackState.update { current ->
            val updatedSubs = current.subtitleTracks.map { track ->
                track.copy(isSelected = (subId != null && track.id == subId))
            }
            current.copy(
                subtitleTracks = updatedSubs,
                selectedSubtitleId = subId,
            )
        }
    }

    companion object {
        private const val TAG = "EngineEventHandler"

        /**
         * Parses the raw "track-list" [MPVNode] into a list of [Track].
         * Filters to audio and subtitle tracks; video or unknown types are ignored.
         */
        fun parseTrackList(node: MPVNode): List<Track> {
            val items: List<MPVNode> = node.asArray()?.toList()
                ?: (0 until node.size()).mapNotNull { node.get(it) }

            return items.mapNotNull { item ->
                val typeStr = item.get("type")?.asString()
                val type = when (typeStr) {
                    "audio" -> TrackType.Audio
                    "sub" -> TrackType.Subtitle
                    else -> return@mapNotNull null
                }

                val id = item.get("id")?.asInt()?.toInt()
                    ?: item.get("id")?.asString()?.toIntOrNull()
                    ?: return@mapNotNull null

                val lang = item.get("lang")?.asString()?.takeIf { it.isNotBlank() }
                val title = item.get("title")?.asString()?.takeIf { it.isNotBlank() }
                val isSelected = item.get("selected")?.asBoolean()
                    ?: (item.get("selected")?.asString() == "yes")
                    ?: false

                Track(
                    id = id,
                    type = type,
                    lang = lang,
                    title = title,
                    isSelected = isSelected,
                )
            }
        }
    }
}
