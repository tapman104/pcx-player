package `is`.xyz.mpv

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
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
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024L // 2MB
    private const val LOG_FILE_NAME = "app.log"
    private const val FLUSH_INTERVAL_WRITES = 50
    private const val MAX_PENDING_DISK_WRITES = 128

    private val lock = Any()

    private data class DiskWriteEntry(
        val timestamp: Long,
        val severity: LogSeverity,
        val tag: String,
        val message: String,
    )
    private val pendingDiskEntries = ArrayDeque<DiskWriteEntry>(MAX_PENDING_DISK_WRITES)
    private var isWriteScheduled = false
    private var droppedEntriesCount = 0
    private var writesSinceFlush = 0

    @Volatile
    private var logDir: File? = null
    private var logWriter: BufferedWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile
    internal var sessionStartTime = System.currentTimeMillis()

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

    private const val SESSION_START_PREFIX = "SESSION_START "

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
            sessionStartTime = System.currentTimeMillis()
            pendingDiskEntries.clear()
            droppedEntriesCount = 0
        }
        try {
            diskExecutor.submit {
                try {
                    logWriter?.close()
                } catch (_: Exception) {}
                logWriter = null
                writesSinceFlush = 0
            }.get()
        } catch (_: Exception) {}

        val sessionId = UUID.randomUUID().toString()
        log(LogSeverity.DEBUG, "AppLogger", "SESSION_START $sessionId")
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
        var scheduleWrite = false
        synchronized(lock) {
            val isFull = pendingDiskEntries.size >= MAX_PENDING_DISK_WRITES
            val isError = severity == LogSeverity.ERROR

            if (isFull) {
                if (!isError) {
                    droppedEntriesCount++
                    return
                } else {
                    val oldestNonErrorIndex = pendingDiskEntries.indexOfFirst { it.severity != LogSeverity.ERROR }
                    if (oldestNonErrorIndex != -1) {
                        pendingDiskEntries.removeAt(oldestNonErrorIndex)
                    } else {
                        pendingDiskEntries.removeFirst()
                    }
                    droppedEntriesCount++
                    pendingDiskEntries.addLast(DiskWriteEntry(timestamp, severity, tag, sanitized))
                }
            } else {
                pendingDiskEntries.addLast(DiskWriteEntry(timestamp, severity, tag, sanitized))
            }

            if (!isWriteScheduled) {
                isWriteScheduled = true
                scheduleWrite = true
            }
        }

        if (scheduleWrite) {
            try {
                diskExecutor.execute {
                    drainPendingWrites()
                }
            } catch (_: RejectedExecutionException) {
                synchronized(lock) {
                    isWriteScheduled = false
                }
            }
        }
    }

    private fun drainPendingWrites() {
        while (true) {
            val entry = synchronized(lock) {
                if (pendingDiskEntries.isEmpty()) {
                    isWriteScheduled = false
                    return
                }
                pendingDiskEntries.removeFirst()
            }
            writeToDisk(entry.timestamp, entry.severity, entry.tag, entry.message)
        }
    }

    private fun cleanupLogFileIfNeeded(currentFile: File) {
        if (!currentFile.exists() || currentFile.length() <= MAX_FILE_SIZE) {
            return
        }

        try {
            logWriter?.flush()
            logWriter?.close()
        } catch (_: Exception) {}
        logWriter = null
        writesSinceFlush = 0

        val fileLength = currentFile.length()
        if (fileLength <= MAX_FILE_SIZE) {
            return
        }

        val bytesToRead = MAX_FILE_SIZE.toInt()
        val startOffset = fileLength - MAX_FILE_SIZE
        val buffer = ByteArray(bytesToRead)

        try {
            RandomAccessFile(currentFile, "r").use { raf ->
                raf.seek(startOffset)
                raf.readFully(buffer)
            }

            var firstNewlineIndex = -1
            for (i in buffer.indices) {
                if (buffer[i] == '\n'.code.toByte()) {
                    firstNewlineIndex = i
                    break
                }
            }

            val retainedBytes = if (firstNewlineIndex != -1 && firstNewlineIndex + 1 < buffer.size) {
                buffer.copyOfRange(firstNewlineIndex + 1, buffer.size)
            } else {
                ByteArray(0)
            }

            val parentDir = currentFile.parentFile ?: return
            val tempFile = File(parentDir, "$LOG_FILE_NAME.tmp")
            try {
                FileOutputStream(tempFile).use { it.write(retainedBytes) }
                try {
                    Files.move(
                        tempFile.toPath(),
                        currentFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Throwable) {
                    // Fallback: keep currentFile untouched if move fails
                }
            } catch (_: Exception) {
                // If rewrite fails, keep currentFile intact
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        } catch (_: Exception) {
            // Keep silent per constraints
        }
    }

    private fun getOrCreateLogDir(): File? {
        val dir = logDir
        if (dir != null) return dir
        return synchronized(lock) {
            logDir ?: try {
                val fallback = File(System.getProperty("java.io.tmpdir"), "mpv_logs").apply { mkdirs() }
                logDir = fallback
                fallback
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun writeToDisk(timestamp: Long, severity: LogSeverity, tag: String, message: String) {
        val dir = getOrCreateLogDir() ?: return
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val currentFile = File(dir, LOG_FILE_NAME)

            if (logWriter == null) {
                if (currentFile.exists() && currentFile.length() > MAX_FILE_SIZE) {
                    cleanupLogFileIfNeeded(currentFile)
                }
                logWriter = FileOutputStream(currentFile, true).bufferedWriter()
            }

            val timestampStr = dateFormat.format(Date(timestamp))
            val writer = logWriter ?: return
            writer.write("[$timestampStr] [${severity.name}] [$tag] $message")
            writer.newLine()
            writesSinceFlush++

            if (writesSinceFlush >= FLUSH_INTERVAL_WRITES) {
                writer.flush()
                writesSinceFlush = 0
                if (currentFile.length() > MAX_FILE_SIZE) {
                    cleanupLogFileIfNeeded(currentFile)
                }
            } else if (severity == LogSeverity.ERROR) {
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

    fun getDroppedEntryCount(): Int = synchronized(lock) { droppedEntriesCount }
    fun getDroppedEntries(): Int = getDroppedEntryCount()

    fun clear() {
        try {
            diskExecutor.submit {
                val preservedErrors = synchronized(lock) {
                    val pendingErrors = pendingDiskEntries.filter { it.severity == LogSeverity.ERROR }
                    pendingDiskEntries.clear()
                    sessionStartTime = System.currentTimeMillis()
                    pendingErrors
                }

                try {
                    logWriter?.close()
                } catch (_: Exception) {}
                logWriter = null
                writesSinceFlush = 0

                val dir = logDir
                if (dir != null) {
                    try {
                        val currentFile = File(dir, LOG_FILE_NAME)
                        if (currentFile.exists()) currentFile.delete()
                        val tempFile = File(dir, "$LOG_FILE_NAME.tmp")
                        if (tempFile.exists()) tempFile.delete()
                    } catch (_: Exception) {}
                }

                if (preservedErrors.isNotEmpty()) {
                    synchronized(lock) {
                        for (i in preservedErrors.indices.reversed()) {
                            pendingDiskEntries.addFirst(preservedErrors[i])
                        }
                    }
                    drainPendingWrites()
                    try {
                        logWriter?.flush()
                    } catch (_: Exception) {}
                }
            }.get()
        } catch (_: Exception) {}
    }

    private val LOG_LINE_REGEX = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\] \[([A-Z]+)\] \[([^\]]*)\] (.*)$""")

    internal fun parseLogLines(lines: List<String>): List<LogEntry> {
        val result = mutableListOf<LogEntry>()
        val parserDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        var currentTimestamp = 0L
        var currentSeverity = LogSeverity.INFO
        var currentTag = ""
        var currentMessage = StringBuilder()
        var hasEntry = false

        fun flushCurrent() {
            if (hasEntry) {
                result.add(
                    LogEntry(
                        id = 0L,
                        timestamp = currentTimestamp,
                        severity = currentSeverity,
                        tag = currentTag,
                        message = currentMessage.toString()
                    )
                )
                currentMessage = StringBuilder()
                hasEntry = false
            }
        }

        for (line in lines) {
            val match = LOG_LINE_REGEX.matchEntire(line)
            if (match != null) {
                flushCurrent()
                val timeStr = match.groupValues[1]
                val sevStr = match.groupValues[2]
                val tagStr = match.groupValues[3]
                val msgStr = match.groupValues[4]

                val parsedTime = try {
                    parserDateFormat.parse(timeStr)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
                val parsedSeverity = try {
                    LogSeverity.valueOf(sevStr)
                } catch (_: Exception) {
                    LogSeverity.INFO
                }

                currentTimestamp = parsedTime
                currentSeverity = parsedSeverity
                currentTag = tagStr
                currentMessage.append(msgStr)
                hasEntry = true
            } else if (hasEntry) {
                currentMessage.append('\n').append(line)
            }
        }
        flushCurrent()
        return result
    }

    fun loadLogPreview(maxBytes: Long = 4096L): List<LogEntry> {
        val dir = getOrCreateLogDir() ?: return emptyList()
        return try {
            diskExecutor.submit(Callable {
                try {
                    drainPendingWrites()
                    logWriter?.flush()
                    val currentFile = File(dir, LOG_FILE_NAME)
                    if (!currentFile.exists() || currentFile.length() == 0L) {
                        return@Callable emptyList<LogEntry>()
                    }
                    readLogPreviewFromFile(currentFile, maxBytes)
                } catch (_: Exception) {
                    emptyList()
                }
            }).get()
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun readLogPreviewFromFile(file: File, maxBytes: Long = 4096L): List<LogEntry> {
        val fileLength = file.length()
        if (fileLength == 0L) return emptyList()

        val bytesToRead = if (fileLength > maxBytes) maxBytes.toInt() else fileLength.toInt()
        val startOffset = if (fileLength > maxBytes) fileLength - maxBytes else 0L
        val buffer = ByteArray(bytesToRead)

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startOffset)
            raf.readFully(buffer)
        }

        val (retainedOffset, retainedLength) = if (startOffset > 0L) {
            var firstNewlineIndex = -1
            for (i in buffer.indices) {
                if (buffer[i] == '\n'.code.toByte()) {
                    firstNewlineIndex = i
                    break
                }
            }
            if (firstNewlineIndex != -1 && firstNewlineIndex + 1 < buffer.size) {
                Pair(firstNewlineIndex + 1, buffer.size - (firstNewlineIndex + 1))
            } else {
                Pair(0, 0)
            }
        } else {
            Pair(0, buffer.size)
        }

        if (retainedLength == 0) return emptyList()

        val lines = ByteArrayInputStream(buffer, retainedOffset, retainedLength)
            .bufferedReader(Charsets.UTF_8)
            .readLines()

        val parsed = parseLogLines(lines)
        return parsed.mapIndexed { index, entry ->
            entry.copy(id = index.toLong())
        }
    }

    fun getLogs(): List<LogEntry> {
        val dir = getOrCreateLogDir() ?: return emptyList()
        return try {
            diskExecutor.submit(Callable {
                try {
                    drainPendingWrites()
                    logWriter?.flush()
                    val currentFile = File(dir, LOG_FILE_NAME)
                    if (!currentFile.exists() || currentFile.length() == 0L) {
                        return@Callable emptyList<LogEntry>()
                    }
                    val lines = currentFile.readLines()
                    if (lines.isEmpty()) {
                        return@Callable emptyList<LogEntry>()
                    }
                    val parsed = parseLogLines(lines)
                    val currentSessionIndex = parsed.indexOfLast {
                        it.message.startsWith(SESSION_START_PREFIX)
                    }
                    val currentEntries = if (currentSessionIndex >= 0) {
                        parsed.subList(currentSessionIndex, parsed.size)
                    } else {
                        parsed
                    }
                    currentEntries.mapIndexed { index, entry ->
                        entry.copy(id = index.toLong())
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }).get()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadPreviousSessionLogs(): List<LogEntry> {
        val dir = logDir ?: return emptyList()
        return try {
            diskExecutor.submit(Callable {
                try {
                    drainPendingWrites()
                    logWriter?.flush()
                    val currentFile = File(dir, LOG_FILE_NAME)
                    if (!currentFile.exists()) {
                        return@Callable emptyList<LogEntry>()
                    }

                    val lines = currentFile.readLines()
                    if (lines.isEmpty()) {
                        return@Callable emptyList<LogEntry>()
                    }

                    val parsed = parseLogLines(lines)
                    val currentSessionIndex = parsed.indexOfLast {
                        it.message.startsWith(SESSION_START_PREFIX)
                    }
                    val previousEntries = if (currentSessionIndex >= 0) {
                        parsed.subList(0, currentSessionIndex)
                    } else {
                        parsed
                    }

                    val count = previousEntries.size
                    previousEntries.mapIndexed { index, entry ->
                        entry.copy(id = -(count - index).toLong())
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }).get()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun flush() {
        try {
            diskExecutor.submit {
                try {
                    drainPendingWrites()
                    logWriter?.flush()
                    writesSinceFlush = 0
                    val dir = logDir
                    if (dir != null) {
                        val currentFile = File(dir, LOG_FILE_NAME)
                        if (currentFile.exists() && currentFile.length() > MAX_FILE_SIZE) {
                            cleanupLogFileIfNeeded(currentFile)
                        }
                    }
                } catch (_: Exception) {}
            }.get()
        } catch (_: Exception) {}
    }

    fun formatLogsForExport(): String {
        return try {
            diskExecutor.submit(Callable {
                drainPendingWrites()
                val dir = logDir
                if (dir != null) {
                    try {
                        logWriter?.flush()
                        val currentFile = File(dir, LOG_FILE_NAME)
                        if (currentFile.exists()) {
                            return@Callable currentFile.readText()
                        }
                    } catch (_: Exception) {}
                }
                ""
            }).get()
        } catch (_: Exception) {
            ""
        }
    }
}
