package `is`.xyz.mpv

import android.content.Context
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of video geometry, as last reported by mpv.
 * All zero/default until the first file has loaded and reported dimensions.
 */
data class VideoGeometry(
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
)

/**
 * Explicit lifecycle status for the native mpv engine.
 */
sealed interface EngineStatus {
    data object NotStarted : EngineStatus
    data object Starting : EngineStatus
    data object Ready : EngineStatus
    data object Destroying : EngineStatus
    data class Failed(val reason: String) : EngineStatus
    data class DestroyFailed(val reason: String, val retried: Boolean = false) : EngineStatus
}

open class MpvEngine(
    private val appContext: Context,
    private val eventHandler: EngineEventHandler,
) : MPVLib.EventObserver {

    /** Called whenever mpv reports a change to width, height, or rotation. */
    var onVideoGeometryChanged: ((VideoGeometry) -> Unit)? = null

    /** Called whenever mpv reports a change to position or duration. */
    var onPlaybackProgressChanged: ((positionSec: Double, durationSec: Double) -> Unit)? = null

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.NotStarted)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    val isReady: Boolean get() = _status.value is EngineStatus.Ready

    private val lock = Any()
    private var currentGeometry = VideoGeometry()
    private var currentPositionSec: Double = 0.0
    private var currentDurationSec: Double = 0.0

    private var isSurfaceAttached: Boolean = false
    private var pendingSurface: Surface? = null
    private var pendingSurfaceSize: Pair<Int, Int>? = null
    private var filePath: String? = null
    private var voInUse: String = "gpu"

    /**
     * Initialize libmpv.
     *
     * Call this once before the view is shown.
     */
    fun initialize(configDir: String, cacheDir: String) {
        var nativeCreated = false
        var initException: Throwable? = null

        synchronized(lock) {
            if (_status.value is EngineStatus.DestroyFailed) {
                Log.e(TAG, "initialize called when engine is in DestroyFailed status")
                return
            }
            if (_status.value is EngineStatus.Ready || _status.value is EngineStatus.Starting || _status.value is EngineStatus.Destroying) {
                Log.w(TAG, "initialize called when engine is already ${_status.value}")
                return
            }
            _status.value = EngineStatus.Starting

            try {
                MPVLib.addObserver(this)
                MPVLib.addObserver(eventHandler)

                MpvLifecycleGuard.create(this, appContext.applicationContext)
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
                MPVLib.setOptionString("idle", "once")

                observeProperties()
                observeGeometryProperties()
                observeProgressProperties()
                observeTrackProperties()

                _status.value = EngineStatus.Ready
                Log.i(TAG, "libmpv initialized successfully")
                flushPendingLocked()
            } catch (e: Throwable) {
                onVideoGeometryChanged = null
                onPlaybackProgressChanged = null
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
        }

        val ex = initException ?: return

        if (nativeCreated) {
            performDestruction(
                wasRetrying = false,
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

    private fun flushPendingLocked() {
        pendingSurfaceSize?.let { (w, h) ->
            MPVLib.setPropertyString("android-surface-size", "${w}x$h")
            pendingSurfaceSize = null
        }

        val surfaceToAttach = pendingSurface
        if (surfaceToAttach != null) {
            pendingSurface = null
            attachSurfaceLocked(surfaceToAttach)
        } else if (isSurfaceAttached && filePath != null) {
            val path = filePath as String
            filePath = null
            loadFileLocked(path)
        }
    }

    private fun attachSurfaceLocked(surface: Surface) {
        Log.w(TAG, "attaching surface")
        isSurfaceAttached = true
        MPVLib.attachSurface(surface)
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setPropertyString("force-window", "yes")

        // We disable video output when the context disappears, enable it back unconditionally before any file load
        MPVLib.setPropertyString("vo", voInUse)

        if (filePath != null) {
            val path = filePath as String
            filePath = null
            loadFileLocked(path)
        }
    }

    private fun loadFileLocked(path: String) {
        MPVLib.command(arrayOf("loadfile", path))
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    private fun detachSurfaceLocked() {
        isSurfaceAttached = false
        try {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("force-window", "no")
            MPVLib.detachSurface()
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching surface", e)
        }
    }

    override fun eventProperty(property: String, value: Long) {
        onPropertyLong(property, value)
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
        val updated = synchronized(lock) {
            val next = when (property) {
                "width" -> currentGeometry.copy(width = value.toInt())
                "height" -> currentGeometry.copy(height = value.toInt())
                "video-rotate" -> currentGeometry.copy(rotationDegrees = value.toInt())
                else -> return false
            }
            currentGeometry = next
            next
        }
        onVideoGeometryChanged?.invoke(updated)
        return true
    }

    /**
     * Feed a progress-relevant property update in.
     *
     * Returns true if the property was consumed.
     */
    fun onPropertyDouble(property: String, value: Double): Boolean {
        val (pos, dur) = synchronized(lock) {
            when (property) {
                "time-pos" -> currentPositionSec = value
                "duration" -> currentDurationSec = value
                else -> return false
            }
            Pair(currentPositionSec, currentDurationSec)
        }
        onPlaybackProgressChanged?.invoke(pos, dur)
        return true
    }

    /** Reset geometry when a new file starts loading, so stale dims aren't briefly reused. */
    fun clearGeometry() {
        val reset = VideoGeometry()
        synchronized(lock) {
            currentGeometry = reset
        }
        onVideoGeometryChanged?.invoke(reset)
    }

    /** Reset progress when a new file starts loading. */
    fun clearProgress() {
        synchronized(lock) {
            currentPositionSec = 0.0
            currentDurationSec = 0.0
        }
        onPlaybackProgressChanged?.invoke(0.0, 0.0)
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        synchronized(lock) {
            val currentStatus = _status.value
            if (currentStatus is EngineStatus.DestroyFailed) {
                Log.w(TAG, "Native instance remains owned; use retryDestroy()")
                return
            }
            if (currentStatus is EngineStatus.NotStarted || currentStatus is EngineStatus.Failed) {
                return
            }
            if (currentStatus is EngineStatus.Destroying) {
                return
            }
            onVideoGeometryChanged = null
            onPlaybackProgressChanged = null
            pendingSurface = null
            filePath = null
            pendingSurfaceSize = null
            if (isSurfaceAttached && isReady) {
                detachSurfaceLocked()
            } else {
                isSurfaceAttached = false
            }
            _status.value = EngineStatus.Destroying
        }

        performDestruction(wasRetrying = false)
    }

    /**
     * Retries destroying libmpv after an initial destroy failure.
     * Only callable once when [status] is [EngineStatus.DestroyFailed] with retried = false.
     */
    fun retryDestroy() {
        val wasRetrying = synchronized(lock) {
            val currentStatus = _status.value
            if (currentStatus !is EngineStatus.DestroyFailed) {
                Log.e(TAG, "retryDestroy called when engine status is not DestroyFailed (status is $currentStatus)")
                return
            }
            if (currentStatus.retried) {
                Log.e(TAG, "retryDestroy already attempted once for this engine; permanent failure")
                return
            }
            _status.value = EngineStatus.Destroying
            true
        }

        performDestruction(wasRetrying = wasRetrying)
    }

    /**
     * Shared destruction sequence used by both [destroy] and [retryDestroy].
     *
     * 1. Unregisters observers under the state lock.
     * 2. Releases the engine lock before calling native destruction.
     * 3. Calls [MpvLifecycleGuard.destroy] outside the engine lock to prevent deadlock
     *    with the native event thread (which may be in a property callback holding no lock,
     *    but would block if we held MpvEngine.lock during pthread_join).
     * 4. Handles the native destruction result.
     */
    private fun performDestruction(
        wasRetrying: Boolean = false,
        successStatus: EngineStatus = EngineStatus.NotStarted,
    ): Throwable? {
        synchronized(lock) {
            MPVLib.removeObserver(this)
            MPVLib.removeObserver(eventHandler)
        }

        val destroyError = try {
            MpvLifecycleGuard.destroy(this)
            null
        } catch (e: Throwable) {
            val reason = e.message ?: "Unknown destruction error"
            Log.e(TAG, "Error destroying MPVLib: $reason", e)
            e
        }

        synchronized(lock) {
            if (destroyError == null) {
                eventHandler.reset()
                _status.value = successStatus
            } else {
                val reason = destroyError.message ?: "Unknown destruction error"
                _status.value = EngineStatus.DestroyFailed(reason, retried = wasRetrying)
            }
        }
        return destroyError
    }

    protected open fun initOptions() {}
    protected open fun postInitOptions() {}

    protected open fun observeProperties() {
        MPVLib.observeProperty("media-title", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    /**
     * Set the file to be played. If the engine is ready and the surface is attached,
     * it loads immediately; otherwise it is held as pending until ready and attached.
     */
    fun playFile(filePath: String) {
        synchronized(lock) {
            if (_status.value is EngineStatus.Destroying) {
                Log.w(TAG, "playFile called when engine is Destroying; ignoring")
                return
            }
            currentGeometry = VideoGeometry()
            currentPositionSec = 0.0
            currentDurationSec = 0.0
        }
        onVideoGeometryChanged?.invoke(VideoGeometry())
        onPlaybackProgressChanged?.invoke(0.0, 0.0)
        eventHandler.reset()
        synchronized(lock) {
            if (_status.value is EngineStatus.Destroying) {
                return
            }
            if (isReady && isSurfaceAttached) {
                this.filePath = null
                MPVLib.setPropertyString("vo", voInUse)
                loadFileLocked(filePath)
            } else {
                if (this.filePath != null) {
                    Log.w(TAG, "Pending file '${this.filePath}' discarded before load; overwriting with '$filePath'")
                }
                this.filePath = filePath
            }
        }
    }

    private inline fun whenReady(action: () -> Unit) {
        synchronized(lock) {
            if (isReady) action()
        }
    }

    private inline fun <T> withReady(action: () -> T): T? {
        synchronized(lock) {
            if (!isReady) return null
            return action()
        }
    }

    /**
     * Toggle play/pause state. Guarded by [isReady].
     */
    fun togglePause() = whenReady {
        val paused = MPVLib.getPropertyBoolean("pause") ?: return@whenReady
        MPVLib.setPropertyBoolean("pause", !paused)
    }

    /**
     * Seek to an absolute position in seconds. Guarded by [isReady].
     */
    fun seekTo(positionSec: Double) = whenReady {
        MPVLib.command(arrayOf("seek", positionSec.toString(), "absolute"))
    }

    /**
     * Seek by a relative offset in seconds (negative seeks backward).
     * mpv resolves the offset against its own current position — no round
     * trip through a cached position value. Guarded by [isReady].
     */
    fun seekBy(offsetSec: Double) = whenReady {
        MPVLib.command(arrayOf("seek", offsetSec.toString(), "relative"))
    }

    /**
     * Set playback speed as a multiplier (1.0 = normal). Guarded by [isReady].
     */
    fun setSpeed(speed: Double) = whenReady {
        MPVLib.setPropertyDouble("speed", speed)
    }

    /**
     * Apply an incremental zoom factor from a pinch gesture (e.g. 1.05 per
     * frame). Converts the multiplicative gesture delta into mpv's
     * logarithmic video-zoom property and writes the accumulated value back.
     * Guarded by [isReady].
     */
    fun zoomBy(factor: Float) = whenReady {
        if (!factor.isFinite() || factor <= 0f) return@whenReady
        val currentZoom = MPVLib.getPropertyDouble("video-zoom") ?: 0.0
        val delta = kotlin.math.ln(factor.toDouble()) / kotlin.math.ln(2.0)
        val next = currentZoom + delta
        MPVLib.setPropertyDouble("video-zoom", next)
    }

    /**
     * Fetch the current track list node from mpv. Guarded by [isReady].
     */
    fun getTrackList(): MPVNode? = withReady {
        MPVLib.getPropertyNode("track-list")
    }

    /**
     * Select audio track by ID. Guarded by [isReady].
     */
    fun selectAudioTrack(trackId: Int) = whenReady {
        MPVLib.setPropertyString("aid", if (trackId <= 0) "no" else trackId.toString())
    }

    /**
     * Select subtitle track by ID, or null to turn subtitles off. Guarded by [isReady].
     */
    fun selectSubtitleTrack(trackId: Int?) = whenReady {
        MPVLib.setPropertyString("sid", if (trackId == null || trackId <= 0) "no" else trackId.toString())
    }

    fun setSubFontSize(size: Int) = whenReady {
        MPVLib.setPropertyString("sub-font-size", size.toString())
    }

    fun setSubPos(pos: Int) = whenReady {
        MPVLib.setPropertyString("sub-pos", pos.toString())
    }

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(vo: String) {
        synchronized(lock) {
            voInUse = vo
            if (isReady) {
                MPVLib.setOptionString("vo", vo)
                MPVLib.setPropertyString("vo", vo)
            }
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        synchronized(lock) {
            if (isReady) {
                MPVLib.setPropertyString("android-surface-size", "${width}x$height")
            } else {
                pendingSurfaceSize = Pair(width, height)
            }
        }
    }

    fun onSurfaceCreated(surface: Surface) {
        synchronized(lock) {
            if (!isReady) {
                Log.i(TAG, "Surface created before engine is Ready; caching as pending")
                pendingSurface = surface
                return
            }
            attachSurfaceLocked(surface)
        }
    }

    fun onSurfaceDestroyed() {
        Log.w(TAG, "detaching surface")
        synchronized(lock) {
            pendingSurface = null
            if (!isSurfaceAttached) return
            if (isReady) {
                detachSurfaceLocked()
            } else {
                isSurfaceAttached = false
            }
        }
    }

    companion object {
        private const val TAG = "mpv"
    }
}

