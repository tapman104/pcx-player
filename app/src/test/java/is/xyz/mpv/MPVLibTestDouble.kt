package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface

/**
 * JVM test double for [MPVLib].
 * Omits native System.loadLibrary calls so JVM unit tests run without native dependencies.
 */
object MPVLib {

    var simulateInitFailure: Boolean = false
    var simulatePostCreateFailure: Boolean = false
    var simulateDestroyFailure: Boolean = false
    var simulateDetachSurfaceFailure: Boolean = false
    var simulateAttachSurfaceFailure: Boolean = false
    var destroyCallCount: Int = 0
    var createCallCount: Int = 0
    var detachSurfaceCallCount: Int = 0
    var isCreated: Boolean = false
    var onDestroy: (() -> Unit)? = null

    fun create(appctx: Context?) {
        createCallCount++
        if (simulateInitFailure) {
            throw RuntimeException("Simulated MPVLib.create failure")
        }
        isCreated = true
    }

    fun init() {
        if (simulateInitFailure || simulatePostCreateFailure) {
            throw RuntimeException("Simulated MPVLib.init failure")
        }
    }

    fun destroy() {
        destroyCallCount++
        if (!isCreated) {
            throw IllegalStateException("destroy() called with no native instance owned")
        }
        if (simulateDestroyFailure) {
            throw RuntimeException("Simulated MPVLib.destroy failure")
        }
        isCreated = false
        onDestroy?.invoke()
    }
    val attachedSurfaces = mutableListOf<Surface>()
    val loadedFiles = mutableListOf<String>()

    fun attachSurface(surface: Surface) {
        if (simulateAttachSurfaceFailure) {
            throw RuntimeException("Simulated MPVLib.attachSurface failure")
        }
        synchronized(attachedSurfaces) {
            attachedSurfaces.add(surface)
        }
    }
    fun detachSurface() {
        detachSurfaceCallCount++
        if (simulateDetachSurfaceFailure) {
            throw RuntimeException("Simulated MPVLib.detachSurface failure")
        }
    }

    val executedCommands = mutableListOf<List<String>>()

    fun command(cmd: Array<out String>) {
        synchronized(executedCommands) {
            executedCommands.add(cmd.toList())
        }
        if (cmd.isNotEmpty() && cmd[0] == "loadfile" && cmd.size > 1) {
            synchronized(loadedFiles) {
                loadedFiles.add(cmd[1])
            }
        }
    }
    fun commandNode(cmd: Array<String>): MPVNode? = null

    fun setOptionString(name: String, value: String): Int = 0

    fun grabThumbnail(dimension: Int): Bitmap? = null
    fun grabThumbnailFast(file: String, pos: Double, dimension: Int, accurate: Boolean): Bitmap? = null
    fun setThumbnailJavaVM(appctx: Context) {}
    fun clearThumbnailCache() {}

    fun getPropertyInt(property: String): Int? = null
    fun setPropertyInt(property: String, value: Int) {}
    fun getPropertyDouble(property: String): Double? = null
    fun setPropertyDouble(property: String, value: Double) {}
    fun getPropertyBoolean(property: String): Boolean? = false
    fun setPropertyBoolean(property: String, value: Boolean) {}
    val setPropertiesString = mutableMapOf<String, String>()
    val setPropertyStringCalls = mutableListOf<Pair<String, String>>()

    fun getPropertyString(property: String): String? = synchronized(setPropertiesString) { setPropertiesString[property] }
    fun setPropertyString(property: String, value: String) {
        synchronized(setPropertyStringCalls) {
            setPropertyStringCalls.add(property to value)
            setPropertiesString[property] = value
        }
    }
    fun getPropertyNode(property: String): MPVNode? = null
    fun setPropertyNode(property: String, node: MPVNode) {}

    val observedProperties = mutableListOf<Pair<String, Int>>()

    fun observeProperty(property: String, format: Int) {
        synchronized(observedProperties) {
            observedProperties.add(property to format)
        }
    }

    private val observers = mutableListOf<EventObserver>()

    val registeredObservers: List<EventObserver>
        get() = synchronized(observers) { observers.toList() }

    val registeredLogObservers: List<LogObserver>
        get() = synchronized(log_observers) { log_observers.toList() }

    fun clearObservers() {
        synchronized(observers) {
            observers.clear()
        }
        synchronized(log_observers) {
            log_observers.clear()
        }
        synchronized(observedProperties) {
            observedProperties.clear()
        }
        synchronized(attachedSurfaces) {
            attachedSurfaces.clear()
        }
        synchronized(loadedFiles) {
            loadedFiles.clear()
        }
        synchronized(executedCommands) {
            executedCommands.clear()
        }
        synchronized(setPropertyStringCalls) {
            setPropertyStringCalls.clear()
            setPropertiesString.clear()
        }
        simulateInitFailure = false
        simulatePostCreateFailure = false
        simulateDestroyFailure = false
        simulateDetachSurfaceFailure = false
        simulateAttachSurfaceFailure = false
        destroyCallCount = 0
        createCallCount = 0
        detachSurfaceCallCount = 0
        isCreated = false
        onDestroy = null
    }

    @JvmStatic
    fun addObserver(o: EventObserver) {
        synchronized(observers) {
            observers.add(o)
        }
    }

    @JvmStatic
    fun removeObserver(o: EventObserver) {
        synchronized(observers) {
            observers.remove(o)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Long) {
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Boolean) {
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Double) {
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: String) {
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: MPVNode) {
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String) {
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property)
        }
    }

    @JvmStatic
    fun event(eventId: Int, data: MPVNode) {
        synchronized(observers) {
            for (o in observers)
                o.event(eventId, data)
        }
    }

    @JvmStatic
    fun event(eventId: Int) {
        synchronized(observers) {
            for (o in observers)
                o.event(eventId)
        }
    }

    private val log_observers = mutableListOf<LogObserver>()

    @JvmStatic
    fun addLogObserver(o: LogObserver) {
        synchronized(log_observers) {
            log_observers.add(o)
        }
    }

    @JvmStatic
    fun removeLogObserver(o: LogObserver) {
        synchronized(log_observers) {
            log_observers.remove(o)
        }
    }

    @JvmStatic
    fun logMessage(prefix: String, level: Int, text: String) {
        synchronized(log_observers) {
            for (o in log_observers)
                o.logMessage(prefix, level, text)
        }
    }

    interface EventObserver {
        fun eventProperty(property: String) {}
        fun eventProperty(property: String, value: Long) {}
        fun eventProperty(property: String, value: Boolean) {}
        fun eventProperty(property: String, value: String) {}
        fun eventProperty(property: String, value: MPVNode) {}
        fun eventProperty(property: String, value: Double) {}
        fun event(eventId: Int) {}
        fun event(eventId: Int, data: MPVNode) {
            event(eventId)
        }
    }

    interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    object MpvFormat {
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_OSD_STRING = 2
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
        const val MPV_FORMAT_NODE = 6
        const val MPV_FORMAT_NODE_ARRAY = 7
        const val MPV_FORMAT_NODE_MAP = 8
        const val MPV_FORMAT_BYTE_ARRAY = 9
    }

    object MpvEvent {
        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_LOG_MESSAGE = 2
        const val MPV_EVENT_GET_PROPERTY_REPLY = 3
        const val MPV_EVENT_SET_PROPERTY_REPLY = 4
        const val MPV_EVENT_COMMAND_REPLY = 5
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_TRACKS_CHANGED = 9
        const val MPV_EVENT_TRACK_SWITCHED = 10
        const val MPV_EVENT_IDLE = 11
        const val MPV_EVENT_PAUSE = 12
        const val MPV_EVENT_UNPAUSE = 13
        const val MPV_EVENT_TICK = 14
        const val MPV_EVENT_SCRIPT_INPUT_DISPATCH = 15
        const val MPV_EVENT_CLIENT_MESSAGE = 16
        const val MPV_EVENT_VIDEO_RECONFIG = 17
        const val MPV_EVENT_AUDIO_RECONFIG = 18
        const val MPV_EVENT_METADATA_UPDATE = 19
        const val MPV_EVENT_SEEK = 20
        const val MPV_EVENT_PLAYBACK_RESTART = 21
        const val MPV_EVENT_PROPERTY_CHANGE = 22
        const val MPV_EVENT_CHAPTER_CHANGE = 23
        const val MPV_EVENT_QUEUE_OVERFLOW = 24
        const val MPV_EVENT_HOOK = 25
    }

    object MpvLogLevel {
        const val MPV_LOG_LEVEL_NONE = 0
        const val MPV_LOG_LEVEL_FATAL = 10
        const val MPV_LOG_LEVEL_ERROR = 20
        const val MPV_LOG_LEVEL_WARN = 30
        const val MPV_LOG_LEVEL_INFO = 40
        const val MPV_LOG_LEVEL_V = 50
        const val MPV_LOG_LEVEL_DEBUG = 60
        const val MPV_LOG_LEVEL_TRACE = 70
    }
}
