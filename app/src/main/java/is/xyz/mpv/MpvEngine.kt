package `is`.xyz.mpv

import android.content.Context
import android.util.Log
import android.view.Surface
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
    private var filePath: String? = null
    private var voInUse: String = "gpu"
    private val optionsConfigurator = MpvOptionsConfigurator(appContext)

    /**
     * Initialize libmpv.
     *
     * Call this once before the view is shown.
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
            flushPending()
        } catch (e: Throwable) {
            eventHandler.onVideoGeometryChanged = null
            eventHandler.onPlaybackProgressChanged = null
            pendingSurface = null
            filePath = null
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

    private fun flushPending() {
        pendingSurfaceSize?.let { (w, h) ->
            MPVLib.setPropertyString("android-surface-size", "${w}x$h")
            pendingSurfaceSize = null
        }

        val surfaceToAttach = pendingSurface
        if (surfaceToAttach != null) {
            pendingSurface = null
            attachSurface(surfaceToAttach)
        } else if (surfaceAttachmentState is SurfaceAttachmentState.Attached && filePath != null) {
            val path = filePath as String
            filePath = null
            loadFile(path)
        }
    }

    private fun attachSurface(surface: Surface) {
        Log.w(TAG, "attaching surface")
        surfaceAttachmentState = SurfaceAttachmentState.Attached
        MPVLib.attachSurface(surface)
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setPropertyString("force-window", "yes")

        // We disable video output when the context disappears, enable it back unconditionally before any file load
        MPVLib.setPropertyString("vo", voInUse)

        if (filePath != null) {
            val path = filePath as String
            filePath = null
            loadFile(path)
        }
    }

    private fun loadFile(path: String) {
        MPVLib.command(arrayOf("loadfile", path))
        MPVLib.getPropertyBoolean("pause")?.let { eventHandler.syncPauseState(it) }
    }

    private fun detachSurface() {
        try {
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
        eventHandler.onVideoGeometryChanged = null
        eventHandler.onPlaybackProgressChanged = null
        pendingSurface = null
        filePath = null
        pendingSurfaceSize = null
        if (surfaceAttachmentState is SurfaceAttachmentState.Attached && isReady) {
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
    ): Throwable? {
        MPVLib.removeObserver(this)
        MPVLib.removeObserver(eventHandler)

        val destroyError = try {
            MPVLib.destroy()
            null
        } catch (e: Throwable) {
            val reason = e.message ?: "Unknown destruction error"
            Log.e(TAG, "Error destroying MPVLib: $reason", e)
            e
        }

        if (destroyError == null) {
            eventHandler.reset()
            _status.value = successStatus
        } else {
            val reason = destroyError.message ?: "Unknown destruction error"
            _status.value = EngineStatus.DestroyFailed(reason)
        }
        return destroyError
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
     * Set the file to be played. If the engine is ready and the surface is attached,
     * it loads immediately; otherwise it is held as pending until ready and attached.
     */
    fun playFile(filePath: String) {
        if (_status.value is EngineStatus.Destroying) {
            Log.w(TAG, "playFile called when engine is Destroying; ignoring")
            return
        }
        eventHandler.clearGeometry()
        eventHandler.clearProgress()
        eventHandler.reset()
        if (_status.value is EngineStatus.Destroying) {
            return
        }
        if (isReady && surfaceAttachmentState is SurfaceAttachmentState.Attached) {
            this.filePath = null
            MPVLib.setPropertyString("vo", voInUse)
            loadFile(filePath)
        } else {
            if (this.filePath != null) {
                Log.w(TAG, "Pending file '${this.filePath}' discarded before load; overwriting with '$filePath'")
            }
            this.filePath = filePath
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
        if (!isReady) return
        MPVLib.command(arrayOf("seek", positionSec.toString(), "absolute"))
    }

    /**
     * Seek by a relative offset in seconds (negative seeks backward).
     * mpv resolves the offset against its own current position — no round
     * trip through a cached position value. Guarded by [isReady].
     */
    fun seekBy(offsetSec: Double) {
        if (!isReady) return
        MPVLib.command(arrayOf("seek", offsetSec.toString(), "relative"))
    }

    /**
     * Set playback speed as a multiplier (1.0 = normal). Guarded by [isReady].
     */
    fun setSpeed(speed: Double) {
        if (!isReady) return
        MPVLib.setPropertyDouble("speed", speed)
    }

    /**
     * Apply an incremental zoom factor from a pinch gesture (e.g. 1.05 per
     * frame). Converts the multiplicative gesture delta into mpv's
     * logarithmic video-zoom property and writes the accumulated value back.
     * Guarded by [isReady].
     */
    fun zoomBy(factor: Float) {
        if (!isReady) return
        if (!factor.isFinite() || factor <= 0f) return
        val currentZoom = MPVLib.getPropertyDouble("video-zoom") ?: 0.0
        val delta = kotlin.math.ln(factor.toDouble()) / kotlin.math.ln(2.0)
        val next = currentZoom + delta
        MPVLib.setPropertyDouble("video-zoom", next)
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
            MPVLib.setOptionString("vo", vo)
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
