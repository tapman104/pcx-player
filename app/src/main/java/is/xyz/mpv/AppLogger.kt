package `is`.xyz.mpv

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

enum class LogSeverity {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val severity: LogSeverity,
    val tag: String,
    val message: String,
)

object AppLogger {
    private const val MAX_CAPACITY = 1000
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024L // 2MB
    private const val LOG_FILE_NAME = "app.log"
    private const val OLD_LOG_FILE_NAME = "app.log.old"

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(MAX_CAPACITY)
    private var nextId = 0L

    @Volatile
    private var logDir: File? = null
    private var logWriter: BufferedWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val diskExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppLogger-DiskWriter").apply {
            isDaemon = true
        }
    }

    // Redaction patterns derived from call sites & threat analysis:
    // 1. Basic-auth credentials in URLs (e.g. scheme://user:password@host)
    private val BASIC_AUTH_REGEX = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\s@]+@""")
    // 2. Authorization headers (Bearer, Basic, token values)
    private val AUTH_HEADER_REGEX = Regex("""(?i)\b(authorization:\s*)(?:bearer\s+|basic\s+|token\s+)?[^\r\n,;]+""")
    // 3. Cookie headers
    private val COOKIE_HEADER_REGEX = Regex("""(?i)\b(cookie:\s*)[^\r\n]+""")
    // 4. Standalone Bearer tokens
    private val BEARER_TOKEN_REGEX = Regex("""(?i)\b(bearer\s+)[A-Za-z0-9_\-\.~+/]+=*""")
    // 5. Query string credentials & tokens (IPTV, trackers, signed CDNs)
    private val QUERY_PARAM_REGEX = Regex("""(?i)([?&](?:token|auth|key|apikey|api_key|pass|password|secret|signature|sig|access_token|hash|passkey|user|username|session|ticket|jwt)=)[^&\s]+""")
    // 6. Android storage UUIDs (e.g. /storage/1234-ABCD/)
    private val STORAGE_UUID_REGEX = Regex("""(?i)/storage/[0-9a-f]{4}-[0-9a-f]{4}/""")
    // 7. Unix / macOS user paths (e.g. /home/username/ or /Users/username/)
    private val USER_PATH_REGEX = Regex("""/(home|Users)/[^/\s]+(/|\b)""")
    // 8. Windows user paths (e.g. C:\Users\username\)
    private val WIN_USER_PATH_REGEX = Regex("""(?i)([a-z]:\\users\\)[^\\]+""")
    // 9. Email addresses
    private val EMAIL_REGEX = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")

    fun init(context: Context) {
        val baseDir = context.applicationContext?.filesDir ?: context.filesDir
        initInternal(File(baseDir, "logs"))
    }

    internal fun initInternal(dir: File) {
        synchronized(lock) {
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            } catch (_: Exception) {}
            logDir = dir
        }
    }

    fun redact(message: String): String {
        if (message.isEmpty()) return message

        var result = message
        if (result.contains('@')) {
            if (result.contains("://")) {
                result = BASIC_AUTH_REGEX.replace(result, "$1[REDACTED]@")
            }
            result = EMAIL_REGEX.replace(result, "[REDACTED]")
        }
        if (result.contains(':') || result.contains("bearer", ignoreCase = true)) {
            if (result.contains("authoriz", ignoreCase = true)) {
                result = AUTH_HEADER_REGEX.replace(result, "$1[REDACTED]")
            }
            if (result.contains("cookie", ignoreCase = true)) {
                result = COOKIE_HEADER_REGEX.replace(result, "$1[REDACTED]")
            }
            if (result.contains("bearer", ignoreCase = true)) {
                result = BEARER_TOKEN_REGEX.replace(result, "$1[REDACTED]")
            }
        }
        if (result.contains('?') || result.contains('&')) {
            result = QUERY_PARAM_REGEX.replace(result, "$1[REDACTED]")
        }
        if (result.contains('/')) {
            if (result.contains("/storage/", ignoreCase = true)) {
                result = STORAGE_UUID_REGEX.replace(result, "/storage/[REDACTED]/")
            }
            if (result.contains("/home/") || result.contains("/Users/")) {
                result = USER_PATH_REGEX.replace(result, "/$1/[REDACTED]$2")
            }
        }
        if (result.contains('\\')) {
            if (result.contains("\\users\\", ignoreCase = true)) {
                result = WIN_USER_PATH_REGEX.replace(result, "$1[REDACTED]")
            }
        }
        return result
    }

    fun log(severity: LogSeverity, tag: String, message: String) {
        val sanitized = redact(message)
        val timestamp = System.currentTimeMillis()
        synchronized(lock) {
            if (entries.size >= MAX_CAPACITY) {
                entries.removeFirst()
            }
            entries.addLast(
                LogEntry(
                    id = nextId++,
                    timestamp = timestamp,
                    severity = severity,
                    tag = tag,
                    message = sanitized
                )
            )
        }
        try {
            diskExecutor.execute {
                writeToDisk(timestamp, severity, tag, sanitized)
            }
        } catch (_: RejectedExecutionException) {
            // Executor shut down
        }
    }

    private fun writeToDisk(timestamp: Long, severity: LogSeverity, tag: String, message: String) {
        val dir = logDir ?: return
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val currentFile = File(dir, LOG_FILE_NAME)
            val oldFile = File(dir, OLD_LOG_FILE_NAME)

            // Rotation: cap file at 2MB. On exceeding, rename current -> app.log.old (delete any existing .old first), start fresh app.log.
            if (currentFile.exists() && currentFile.length() >= MAX_FILE_SIZE) {
                try {
                    logWriter?.close()
                } catch (_: Exception) {}
                logWriter = null

                if (oldFile.exists()) {
                    oldFile.delete()
                }
                currentFile.renameTo(oldFile)
            }

            if (logWriter == null) {
                logWriter = FileOutputStream(currentFile, true).bufferedWriter()
            }

            val timestampStr = dateFormat.format(Date(timestamp))
            logWriter?.let { writer ->
                writer.write("[$timestampStr] [${severity.name}] [$tag] $message")
                writer.newLine()
                writer.flush()
            }
        } catch (_: Exception) {
            // Keep silent per constraints
        }
    }

    fun logMpv(prefix: String, level: Int, text: String) {
        if (level >= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_V) return

        val severity = when (level) {
            MPVLib.MpvLogLevel.MPV_LOG_LEVEL_FATAL,
            MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> LogSeverity.ERROR
            MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN -> LogSeverity.WARN
            MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO -> LogSeverity.INFO
            // NONE (sentinel for "logging disabled") cannot occur in practice; falling through to DEBUG is intentional/harmless
            else -> LogSeverity.DEBUG
        }

        log(severity, "mpv:$prefix", text.trimEnd())
    }

    fun d(tag: String, message: String) {
        log(LogSeverity.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        log(LogSeverity.INFO, tag, message)
    }

    fun w(tag: String, message: String, tr: Throwable? = null) {
        val msg = if (tr != null) "$message\n${android.util.Log.getStackTraceString(tr)}".trimEnd() else message
        log(LogSeverity.WARN, tag, msg)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        val msg = if (tr != null) "$message\n${android.util.Log.getStackTraceString(tr)}".trimEnd() else message
        log(LogSeverity.ERROR, tag, msg)
    }

    fun getLogs(): List<LogEntry> = synchronized(lock) {
        entries.toList()
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
        try {
            diskExecutor.submit {
                try {
                    logWriter?.close()
                } catch (_: Exception) {}
                logWriter = null

                val dir = logDir
                if (dir != null) {
                    try {
                        val currentFile = File(dir, LOG_FILE_NAME)
                        val oldFile = File(dir, OLD_LOG_FILE_NAME)
                        if (currentFile.exists()) currentFile.delete()
                        if (oldFile.exists()) oldFile.delete()
                    } catch (_: Exception) {}
                }
            }.get()
        } catch (_: Exception) {}
    }

    fun flush() {
        try {
            diskExecutor.submit {
                try {
                    logWriter?.flush()
                } catch (_: Exception) {}
            }.get()
        } catch (_: Exception) {}
    }

    fun formatLogsForExport(): String {
        return try {
            diskExecutor.submit(Callable {
                val dir = logDir
                if (dir != null) {
                    try {
                        logWriter?.flush()
                        val oldFile = File(dir, OLD_LOG_FILE_NAME)
                        val currentFile = File(dir, LOG_FILE_NAME)
                        if (oldFile.exists() || currentFile.exists()) {
                            val sb = StringBuilder()
                            if (oldFile.exists()) {
                                sb.append(oldFile.readText())
                            }
                            if (currentFile.exists()) {
                                sb.append(currentFile.readText())
                            }
                            return@Callable sb.toString()
                        }
                    } catch (_: Exception) {}
                }

                formatEntriesInMemory()
            }).get()
        } catch (_: Exception) {
            formatEntriesInMemory()
        }
    }

    private fun formatEntriesInMemory(): String = synchronized(lock) {
        buildString {
            for (entry in entries) {
                append('[')
                append(dateFormat.format(Date(entry.timestamp)))
                append("] [")
                append(entry.severity.name)
                append("] [")
                append(entry.tag)
                append("] ")
                appendLine(entry.message)
            }
        }
    }
}
