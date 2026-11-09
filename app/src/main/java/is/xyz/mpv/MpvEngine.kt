package `is`.xyz.mpv

import android.content.Context
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.state.VideoDisplayMode
import `is`.xyz.mpv.state.VideoDisplayState
import `is`.xyz.mpv.state.VideoTransform
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit lifecycle status for the native mpv engine.
 */
sealed interface EngineStatus {
    data object NotStarted : EngineStatus
    data object Starting : EngineStatus
    data object Ready : EngineStatus
    data object Destroying : EngineStatus
    data class Failed(val reason: String) : EngineStatus
    data class DestroyFailed(val reason: String) : EngineStatus
}

/**
 * Explicit state of surface attachment to libmpv.
 */
sealed interface SurfaceAttachmentState {
    data object Detached : SurfaceAttachmentState
    data object Attached : SurfaceAttachmentState
    data class DetachFailed(val exception: Throwable) : SurfaceAttachmentState
}

/**
 * Core controller wrapping libmpv, managing engine lifecycle, surface attachment, and playback.
 *
 * Architectural Design:
 * - Pending-file ownership: MpvEngine owns pending-file management. If playback is requested via
 *   [playFile] before the engine is Ready or before the rendering surface is Attached, the path is
 *   held as pending and loaded once both conditions are satisfied.
 * - Threading contract: All engine lifecycle operations ([initialize], [destroy]), playback commands
 *   ([playFile], seek, etc.), and surface lifecycle callbacks ([onSurfaceCreated], [onSurfaceChanged],
 *   [onSurfaceDestroyed]) must execute on the same designated thread (currently the Android main thread).
 *   MpvEngine state is not synchronized for concurrent multi-threaded access, and the pending-file
 *   mechanism does not make cross-thread calls safe.
 */
open class MpvEngine(
    private val appContext: Context,
    private val eventHandler: EngineEventHandler,
) : MPVLib.EventObserver {

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.NotStarted)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    val isReady: Boolean get() = _status.value is EngineStatus.Ready

    private var surfaceAttachmentState: SurfaceAttachmentState = SurfaceAttachmentState.Detached

    val surfaceAttachment: SurfaceAttachmentState
        get() = surfaceAttachmentState

    val isSurfaceAttached: Boolean
        get() = surfaceAttachmentState is SurfaceAttachmentState.Attached

    private var pendingSurface: Surface? = null
    private var pendingSurfaceSize: Pair<Int, Int>? = null
    // Holds the pending file path when playFile() is called before the engine is Ready and surface is Attached.
    private var pendingFilePath: String? = null
    private var voInUse: String = "gpu"
    private val optionsConfigurator = MpvOptionsConfigurator(appContext)


    /**
     * Initialize libmpv.
     *
     * Call this once before the view is shown. Must be called from the designated main thread.
     * Transitions engine status to [EngineStatus.Ready] on success and calls [flushPending] to attach
     * any pending surface and load any pending file once prerequisites are satisfied.
     */
    fun initialize(configDir: String, cacheDir: String) {
        var nativeCreated = false
        var initException: Throwable? = null

        val currentStatus = _status.value
        if (currentStatus is EngineStatus.DestroyFailed) {
            Log.e(TAG, "initialize called when engine is in DestroyFailed status")
            return
        }
        if (currentStatus is EngineStatus.Ready || currentStatus is EngineStatus.Starting || currentStatus is EngineStatus.Destroying) {
            Log.w(TAG, "initialize called when engine is already $currentStatus")
            return
        }
        if (!_status.compareAndSet(currentStatus, EngineStatus.Starting)) {
            return
        }

        try {
            MPVLib.addObserver(this)
            MPVLib.addObserver(eventHandler)

            MPVLib.create(appContext.applicationContext)
            nativeCreated = true

            MPVLib.setOptionString("config", "yes")
            MPVLib.setOptionString("config-dir", configDir)
            for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir")) {
                MPVLib.setOptionString(opt, cacheDir)
            }
            initOptions()

            MPVLib.init()

            postInitOptions()
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.setOptionString("keep-open", "yes")

            observeProperties()
            observeGeometryProperties()
            observeProgressProperties()
            observeTrackProperties()

            _status.value = EngineStatus.Ready
            Log.i(TAG, "libmpv initialized successfully")
            // Engine is now Ready; apply pending surface configuration and load any pending file.
            flushPending()
        } catch (e: Throwable) {
            pendingSurface = null
            pendingFilePath = null
            pendingSurfaceSize = null
            initException = e
            Log.e(TAG, "libmpv initialization failed: ${e.message}", e)
            MPVLib.removeObserver(this)
            MPVLib.removeObserver(eventHandler)
            _status.value = if (nativeCreated) {
                EngineStatus.Destroying
            } else {
                EngineStatus.Failed(e.message ?: "Unknown initialization error")
            }
        }

        val ex = initException ?: return

        if (nativeCreated) {
            performDestruction(
                successStatus = EngineStatus.Failed(ex.message ?: "Unknown initialization error")
            )
        }
        throw ex
    }

    private fun observeGeometryProperties() {
        MPVLib.observeProperty("width", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("height", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("video-rotate", MPVLib.MpvFormat.MPV_FORMAT_INT64)
    }

    private fun observeProgressProperties() {
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    private fun observeTrackProperties() {
        MPVLib.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NODE)
        MPVLib.observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        MPVLib.observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    /**
     * Flushes queued surface size, attaches any pending surface (which loads any pending file),
     * or loads the pending file directly if the surface was already attached.
     */
    private fun flushPending() {
        pendingSurfaceSize?.let { (w, h) ->
            MPVLib.setPropertyString("android-surface-size", "${w}x$h")
            pendingSurfaceSize = null
        }

        val surfaceToAttach = pendingSurface
        if (surfaceToAttach != null) {
            pendingSurface = null
            attachSurface(surfaceToAttach)
        } else if (surfaceAttachmentState is SurfaceAttachmentState.Attached && pendingFilePath != null) {
            val path = pendingFilePath as String
            pendingFilePath = null
            loadFile(path)
        }
    }

    /**
     * Attaches the native rendering surface to libmpv.
     *
     * Sets surface attachment state to [SurfaceAttachmentState.Attached]. If a pending file was
     * awaiting surface attachment while the engine is Ready, it is loaded immediately.
     */
    private fun attachSurface(surface: Surface) {
        Log.w(TAG, "attaching surface")
        MPVLib.attachSurface(surface)
        surfaceAttachmentState = SurfaceAttachmentState.Attached
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setPropertyString("force-window", "yes")

        // We disable video output when the context disappears, enable it back unconditionally before any file load
        MPVLib.setPropertyString("vo", voInUse)

        if (pendingFilePath != null) {
            val path = pendingFilePath as String
            pendingFilePath = null
            loadFile(path)
        }
    }

    private fun loadFile(path: String) {
        MPVLib.command(arrayOf("loadfile", path))
        MPVLib.getPropertyBoolean("pause")?.let { eventHandler.syncPauseState(it) }
    }

    private fun detachSurface() {
        try {
            MPVLib.setPropertyString("pause", "yes")
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("force-window", "no")
            MPVLib.detachSurface()
            surfaceAttachmentState = SurfaceAttachmentState.Detached
        } catch (e: Exception) {
            surfaceAttachmentState = SurfaceAttachmentState.DetachFailed(e)
            Log.e(TAG, "Error detaching surface: native detach failed; state set to DetachFailed", e)
        }
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed. Can be called again after a failed
     * destruction to retry it.
     *
     * Must be called from the same thread as initialize(), playFile(), onSurfaceCreated/Changed/Destroyed
     * (the Android main thread). The compareAndSet on _status serializes concurrent destroy() calls against
     * each other, but MpvEngine's other mutable state is not synchronized against concurrent access from a
     * different thread.
     */
    fun destroy() {
        val currentStatus = _status.value
        if (currentStatus is EngineStatus.NotStarted || currentStatus is EngineStatus.Failed) {
            return
        }
        if (currentStatus is EngineStatus.Destroying) {
            return
        }
        if (!_status.compareAndSet(currentStatus, EngineStatus.Destroying)) {
            return
        }
        pendingSurface = null
        pendingFilePath = null
        pendingSurfaceSize = null
        if (surfaceAttachmentState is SurfaceAttachmentState.Attached && currentStatus is EngineStatus.Ready) {
            detachSurface()
        } else if (surfaceAttachmentState !is SurfaceAttachmentState.DetachFailed) {
            surfaceAttachmentState = SurfaceAttachmentState.Detached
        }

        performDestruction()
    }

    /**
     * Shared destruction sequence.
     *
     * 1. Unregisters observers.
     * 2. Calls [MPVLib.destroy].
     * 3. Handles the native destruction result.
     *
     * Must be called from the same thread as initialize(), playFile(), onSurfaceCreated/Changed/Destroyed
     * (the Android main thread). The compareAndSet on _status serializes concurrent destroy() calls against
     * each other, but MpvEngine's other mutable state is not synchronized against concurrent access from a
     * different thread.
     */
    private fun performDestruction(
        successStatus: EngineStatus = EngineStatus.NotStarted,
    ) {
        MPVLib.removeObserver(this)
        MPVLib.removeObserver(eventHandler)

        try {
            MPVLib.destroy()
            eventHandler.reset()
            surfaceAttachmentState = SurfaceAttachmentState.Detached
            _status.value = successStatus
        } catch (e: Throwable) {
            val reason = e.message ?: "Unknown destruction error"
            Log.e(TAG, "Error destroying MPVLib: $reason", e)
            _status.value = EngineStatus.DestroyFailed(reason)
        }
    }

    protected open fun initOptions() {
        voInUse = optionsConfigurator.configure(MpvOptions())
    }

    protected open fun postInitOptions() {
        optionsConfigurator.configurePostInit()
    }

    protected open fun observeProperties() {
        MPVLib.observeProperty("media-title", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    /**
     * Set the file to be played.
     *
     * MpvEngine owns pending-file management: if the engine is Ready and the rendering surface is
     * Attached, the file loads immediately. Otherwise, it is stored as a pending file and loaded
     * when both engine readiness and surface attachment conditions are satisfied.
     *
     * Threading contract: Must be called from the designated main thread. The pending-file
     * mechanism handles lifecycle and surface readiness ordering; it does not make cross-thread
     * access safe.
     */
    fun playFile(filePath: String) {
        if (_status.value is EngineStatus.Destroying) {
            Log.w(TAG, "playFile called when engine is Destroying; ignoring")
            return
        }
        eventHandler.reset()
        if (isReady && surfaceAttachmentState is SurfaceAttachmentState.Attached) {
            pendingFilePath = null
            MPVLib.setPropertyString("vo", voInUse)
            loadFile(filePath)
        } else {
            if (pendingFilePath != null) {
                Log.w(TAG, "Pending file '$pendingFilePath' discarded before load; overwriting with '$filePath'")
            }
            pendingFilePath = filePath
        }
    }

    /**
     * Toggle play/pause state. Guarded by [isReady].
     */
    fun togglePause() {
        if (!isReady) return
        val paused = MPVLib.getPropertyBoolean("pause") ?: return
        MPVLib.setPropertyBoolean("pause", !paused)
    }

    /**
     * Seek to an absolute position in seconds. Guarded by [isReady].
     */
    fun seekTo(positionSec: Double) {
        if (!isReady || !positionSec.isFinite()) return
        MPVLib.command(arrayOf("seek", formatSeekArg(positionSec), "absolute"))
    }

    /**
     * Seek by a relative offset in seconds (negative seeks backward).
     * mpv resolves the offset against its own current position — no round
     * trip through a cached position value. Guarded by [isReady].
     */
    fun seekBy(offsetSec: Double) {
        if (!isReady || !offsetSec.isFinite()) return
        MPVLib.command(arrayOf("seek", formatSeekArg(offsetSec), "relative"))
    }

    private fun formatSeekArg(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /**
     * Set playback speed as a multiplier (1.0 = normal). Guarded by [isReady].
     */
    fun setSpeed(speed: Double) {
        if (!isReady) return
        MPVLib.setPropertyDouble("speed", speed)
    }

    /**
     * Apply video transform (zoom and pan) and display mode (panscan).
     * Converts linear zoom to mpv's logarithmic video-zoom property (log2).
     * Guarded by [isReady].
     */
    fun setVideoDisplayState(state: VideoDisplayState) {
        if (!isReady) return
        val mpvZoom = if (state.transform.zoom > 0f) {
            kotlin.math.log2(state.transform.zoom.toDouble())
        } else {
            0.0
        }
        val panscan = when (state.mode) {
            VideoDisplayMode.ORIGINAL -> 0.0
            VideoDisplayMode.FULL_SCREEN -> 1.0
        }
        MPVLib.setPropertyDouble("video-zoom", mpvZoom)
        MPVLib.setPropertyDouble("video-pan-x", state.transform.panX)
        MPVLib.setPropertyDouble("video-pan-y", state.transform.panY)
        MPVLib.setPropertyDouble("panscan", panscan)
    }

    /**
     * Apply absolute video transform (zoom and pan).
     * Converts linear zoom to mpv's logarithmic video-zoom property (log2).
     * Guarded by [isReady].
     */
    fun setVideoTransform(transform: VideoTransform) {
        if (!isReady) return
        val mpvZoom = if (transform.zoom > 0f) {
            kotlin.math.log2(transform.zoom.toDouble())
        } else {
            0.0
        }
        MPVLib.setPropertyDouble("video-zoom", mpvZoom)
        MPVLib.setPropertyDouble("video-pan-x", transform.panX)
        MPVLib.setPropertyDouble("video-pan-y", transform.panY)
    }

    /**
     * Fetch the current track list node from mpv. Guarded by [isReady].
     */
    fun getTrackList(): MPVNode? {
        if (!isReady) return null
        return MPVLib.getPropertyNode("track-list")
    }

    /**
     * Select audio track by ID. Guarded by [isReady].
     */
    fun selectAudioTrack(trackId: Int) {
        if (!isReady) return
        MPVLib.setPropertyString("aid", if (trackId <= 0) "no" else trackId.toString())
    }

    /**
     * Select subtitle track by ID, or null to turn subtitles off. Guarded by [isReady].
     */
    fun selectSubtitleTrack(trackId: Int?) {
        if (!isReady) return
        MPVLib.setPropertyString("sid", if (trackId == null || trackId <= 0) "no" else trackId.toString())
    }

    fun setSubFontSize(size: Int) {
        if (!isReady) return
        MPVLib.setPropertyString("sub-font-size", size.toString())
    }

    fun setSubPos(pos: Int) {
        if (!isReady) return
        MPVLib.setPropertyString("sub-pos", pos.toString())
    }

    fun setAudioLang(code: String?) {
        if (!isReady) return
        MPVLib.setPropertyString("alang", code.orEmpty())
    }

    fun setSubtitleLang(code: String?) {
        if (!isReady) return
        MPVLib.setPropertyString("slang", code.orEmpty())
    }

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(vo: String) {
        voInUse = vo
        if (isReady) {
            MPVLib.setPropertyString("vo", vo)
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        if (isReady) {
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        } else {
            pendingSurfaceSize = Pair(width, height)
        }
    }

    /**
     * Callback when the rendering surface is created.
     *
     * If the engine is not yet Ready, holds the surface as pending until [initialize] calls [flushPending].
     * Once Ready, attaches the surface immediately, satisfying the surface condition and loading any
     * pending file. Must be called on the designated main thread.
     */
    fun onSurfaceCreated(surface: Surface) {
        if (!isReady) {
            Log.i(TAG, "Surface created before engine is Ready; caching as pending")
            pendingSurface = surface
            return
        }
        attachSurface(surface)
    }

    fun onSurfaceDestroyed() {
        Log.w(TAG, "detaching surface")
        pendingSurface = null
        if (surfaceAttachmentState !is SurfaceAttachmentState.Attached) return
        if (isReady) {
            detachSurface()
        } else {
            surfaceAttachmentState = SurfaceAttachmentState.Detached
        }
    }

    companion object {
        private const val TAG = "mpv"
    }
}
