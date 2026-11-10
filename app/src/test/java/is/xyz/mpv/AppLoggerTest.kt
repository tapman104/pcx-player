package `is`.xyz.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppLoggerTest {

    @Before
    fun setUp() {
        AppLogger.clear()
    }

    @Test
    fun testLogMpvFiltersDebugVerboseTraceAndKeepsInfoWarnError() {
        // Verbose, debug, and trace logs should be skipped
        AppLogger.logMpv("vo/gpu", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_V, "verbose log")
        AppLogger.logMpv("vo/gpu", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_DEBUG, "debug log")
        AppLogger.logMpv("vo/gpu", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_TRACE, "trace log")

        assertTrue("Verbose, debug, and trace logs should not be stored", AppLogger.getLogs().isEmpty())

        // Info, warn, error, and fatal logs should be stored
        AppLogger.logMpv("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO, "info log")
        AppLogger.logMpv("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN, "warn log")
        AppLogger.logMpv("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "error log")
        AppLogger.logMpv("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_FATAL, "fatal log")

        val logs = AppLogger.getLogs()
        assertEquals(4, logs.size)
        assertEquals(LogSeverity.INFO, logs[0].severity)
        assertEquals("mpv:cplayer", logs[0].tag)
        assertEquals("info log", logs[0].message)

        assertEquals(LogSeverity.WARN, logs[1].severity)
        assertEquals("mpv:cplayer", logs[1].tag)
        assertEquals("warn log", logs[1].message)

        assertEquals(LogSeverity.ERROR, logs[2].severity)
        assertEquals("mpv:cplayer", logs[2].tag)
        assertEquals("error log", logs[2].message)

        assertEquals(LogSeverity.ERROR, logs[3].severity)
        assertEquals("mpv:cplayer", logs[3].tag)
        assertEquals("fatal log", logs[3].message)
    }

    @Test
    fun testRedactionBasicAuthInUrls() {
        val input = "Opening http://alice:supersecret@myhost.org:8080/stream.m3u8"
        val expected = "Opening http://[REDACTED]@myhost.org:8080/stream.m3u8"
        assertEquals(expected, AppLogger.redact(input))
    }

    @Test
    fun testRedactionAuthHeadersAndBearerTokens() {
        val authHeader = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.test"
        assertEquals("Authorization: [REDACTED]", AppLogger.redact(authHeader))

        val cookieHeader = "Cookie: session_id=123456789; secure"
        assertEquals("Cookie: [REDACTED]", AppLogger.redact(cookieHeader))

        val standaloneBearer = "Connecting with Bearer abc-123_xyz token"
        assertEquals("Connecting with Bearer [REDACTED] token", AppLogger.redact(standaloneBearer))
    }

    @Test
    fun testRedactionQueryStringCredentials() {
        val input = "https://stream.iptv.com/live.m3u8?username=user1&password=pass2&token=sec123&passkey=pk987&res=1080"
        val expected = "https://stream.iptv.com/live.m3u8?username=[REDACTED]&password=[REDACTED]&token=[REDACTED]&passkey=[REDACTED]&res=1080"
        assertEquals(expected, AppLogger.redact(input))
    }

    @Test
    fun testRedactionFilePathsAndDeviceUsernames() {
        val storagePath = "Reading /storage/ABCD-1234/Movies/film.mkv"
        assertEquals("Reading /storage/[REDACTED]/Movies/film.mkv", AppLogger.redact(storagePath))

        val linuxPath = "File at /home/john_doe/videos/video.mp4"
        assertEquals("File at /home/[REDACTED]/videos/video.mp4", AppLogger.redact(linuxPath))

        val macPath = "File at /Users/alice/Downloads/clip.mp4"
        assertEquals("File at /Users/[REDACTED]/Downloads/clip.mp4", AppLogger.redact(macPath))

        val winPath = """File at C:\Users\Bob\Videos\movie.mp4"""
        assertEquals("""File at C:\Users\[REDACTED]\Videos\movie.mp4""", AppLogger.redact(winPath))
    }

    @Test
    fun testRedactionEmailAddresses() {
        val input = "Report issues to user.support+test@example.com"
        val expected = "Report issues to [REDACTED]"
        assertEquals(expected, AppLogger.redact(input))
    }

    @Test
    fun testLogMpvAppliesRedaction() {
        AppLogger.logMpv(
            "cplayer",
            MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO,
            "Playing http://user:pass@iptv.server.net/stream?token=secret123"
        )
        val logs = AppLogger.getLogs()
        assertEquals(1, logs.size)
        assertEquals("Playing http://[REDACTED]@iptv.server.net/stream?token=[REDACTED]", logs[0].message)
    }

    @Test
    fun testDiskPersistenceAndClear() {
        val tempDir = Files.createTempDirectory("app_logger_test").toFile()
        try {
            AppLogger.initInternal(tempDir)
            AppLogger.i("TestTag", "Disk message 1")
            AppLogger.w("TestTag", "Disk message 2 with token: http://user:pass@host/live")
            AppLogger.flush()

            val appLogFile = File(tempDir, "app.log")
            assertTrue("app.log should exist on disk", appLogFile.exists())

            val export = AppLogger.formatLogsForExport()
            assertTrue(export.contains("Disk message 1"))
            assertTrue(export.contains("http://[REDACTED]@host/live"))

            AppLogger.clear()
            assertFalse("app.log should be deleted after clear()", appLogFile.exists())
            assertTrue(AppLogger.getLogs().isEmpty())
            assertTrue(AppLogger.formatLogsForExport().isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDiskRotation() {
        val tempDir = Files.createTempDirectory("app_logger_rotation_test").toFile()
        try {
            AppLogger.initInternal(tempDir)
            val currentFile = File(tempDir, "app.log")
            val oldFile = File(tempDir, "app.log.old")

            // Write 2MB into currentFile to trigger cleanup on next write
            val dummyBytes = ByteArray(2 * 1024 * 1024 + 10)
            currentFile.writeBytes(dummyBytes)

            // Logging another message should trigger size-based cleanup of currentFile
            AppLogger.i("RotationTag", "New message after 2MB rotation")
            AppLogger.flush()

            assertFalse("Old log file should not exist under single-file storage", oldFile.exists())
            assertTrue("Current log file should exist after cleanup", currentFile.exists())
            assertTrue("Current log file should not exceed 2MB", currentFile.length() <= 2 * 1024 * 1024)

            val export = AppLogger.formatLogsForExport()
            assertTrue("Export should include message from cleaned up logs", export.contains("New message after 2MB rotation"))

            AppLogger.clear()
            assertFalse(currentFile.exists())
            assertFalse(oldFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDiskWritesAreAsynchronousAndNonBlocking() {
        val tempDir = Files.createTempDirectory("app_logger_async_test").toFile()
        try {
            AppLogger.initInternal(tempDir)

            // Calling log() must return immediately
            val startTime = System.nanoTime()
            for (i in 1..100) {
                AppLogger.i("AsyncTest", "Async line $i")
            }
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

            // 100 in-memory writes without blocking on disk should take < 50ms
            assertTrue("log() calls should complete without blocking on disk, took ${elapsedMs}ms", elapsedMs < 100)

            // Flush to ensure disk writer finishes
            AppLogger.flush()
            val appLogFile = File(tempDir, "app.log")
            assertTrue(appLogFile.exists())
            val content = appLogFile.readText()
            assertTrue(content.contains("Async line 1"))
            assertTrue(content.contains("Async line 100"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testHighFrequencyPlaybackLoggingPerformance() {
        val tempDir = Files.createTempDirectory("app_logger_perf_test").toFile()
        try {
            AppLogger.initInternal(tempDir)

            // Simulate high frequency mpv logs (e.g. 5000 lines during playback)
            val startTime = System.nanoTime()
            for (i in 1..5000) {
                when (i % 5) {
                    0 -> AppLogger.logMpv("vo/gpu", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO, "flip pts: $i fps=60")
                    1 -> AppLogger.logMpv("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN, "audio/video desync $i ms")
                    2 -> AppLogger.logMpv("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "decode error at frame $i")
                    3 -> AppLogger.logMpv("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_V, "verbose drop $i") // dropped
                    4 -> AppLogger.logMpv("vo/gpu", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_DEBUG, "debug stats $i") // dropped
                }
            }
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

            // 5000 calls to logMpv() should take minimal CPU time (well under 200ms, < 40µs/call)
            assertTrue("High frequency logging should be non-blocking and fast, took ${elapsedMs}ms", elapsedMs < 500)

            // Flush to disk
            AppLogger.flush()
            val export = AppLogger.formatLogsForExport()
            assertTrue(export.isNotEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLoadPreviousSessionLogsFromDisk() {
        val tempDir = Files.createTempDirectory("app_logger_prev_test").toFile()
        try {
            AppLogger.initInternal(tempDir)
            AppLogger.i("PrevTag", "Session 1 message 1")
            AppLogger.w("PrevTag", "Session 1 message 2")
            AppLogger.flush()

            // Simulate app restart / new session
            AppLogger.clear()
            // Re-create the disk log files to simulate previous run persisted on disk
            val logFile = File(tempDir, "app.log")
            logFile.writeText(
                "[2026-11-06 10:00:00.000] [INFO] [PrevTag] Previous session log 1\n" +
                "[2026-11-06 10:00:01.000] [WARN] [PrevTag] Previous session log 2\n"
            )
            AppLogger.initInternal(tempDir)

            val previousLogs = AppLogger.loadPreviousSessionLogs()
            assertEquals(2, previousLogs.size)
            assertEquals("Previous session log 1", previousLogs[0].message)
            assertEquals(LogSeverity.INFO, previousLogs[0].severity)
            assertEquals("PrevTag", previousLogs[0].tag)
            assertEquals(-2L, previousLogs[0].id)

            assertEquals("Previous session log 2", previousLogs[1].message)
            assertEquals(LogSeverity.WARN, previousLogs[1].severity)
            assertEquals("PrevTag", previousLogs[1].tag)
            assertEquals(-1L, previousLogs[1].id)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLoadPreviousSessionLogsDeduplicationWithCurrentSession() {
        val tempDir = Files.createTempDirectory("app_logger_dedup_test").toFile()
        try {
            val logFile = File(tempDir, "app.log")
            tempDir.mkdirs()
            logFile.writeText(
                "[2026-11-06 10:00:00.000] [INFO] [PrevTag] Older log from prev session\n"
            )
            AppLogger.initInternal(tempDir)

            // Current session logs
            AppLogger.i("CurrentTag", "Current session log")
            AppLogger.flush()

            // Memory has SESSION_START and current session log
            val currentLogs = AppLogger.getLogs()
            assertEquals(2, currentLogs.size)
            assertTrue(currentLogs[0].message.startsWith("SESSION_START "))
            assertEquals("Current session log", currentLogs[1].message)

            // Previous session logs should only contain the older log, not the current session log
            val previousLogs = AppLogger.loadPreviousSessionLogs()
            assertEquals(1, previousLogs.size)
            assertEquals("Older log from prev session", previousLogs[0].message)
            assertEquals(-1L, previousLogs[0].id)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLoadPreviousSessionLogsCoincidentalDuplicateDoesNotDropSubsequentLogs() {
        val tempDir = Files.createTempDirectory("app_logger_coincidental_test").toFile()
        try {
            tempDir.mkdirs()
            val currentFile = File(tempDir, "app.log")

            currentFile.writeText(
                "[2026-11-05 10:00:00.000] [INFO] [SharedTag] Previous line before duplicate\n" +
                "[2026-11-05 10:00:01.000] [INFO] [SharedTag] Coincidental duplicate line\n" +
                "[2026-11-06 09:00:00.000] [WARN] [SharedTag] Genuine previous session line after duplicate\n"
            )

            // Start current session
            AppLogger.initInternal(tempDir)
            // Current session logs the coincidental duplicate line again
            AppLogger.i("SharedTag", "Coincidental duplicate line")
            AppLogger.i("SharedTag", "Current session unique line")
            AppLogger.flush()

            val previousLogs = AppLogger.loadPreviousSessionLogs()
            assertEquals(3, previousLogs.size)
            assertEquals("Previous line before duplicate", previousLogs[0].message)
            assertEquals(-3L, previousLogs[0].id)
            assertEquals("Coincidental duplicate line", previousLogs[1].message)
            assertEquals(-2L, previousLogs[1].id)
            assertEquals("Genuine previous session line after duplicate", previousLogs[2].message)
            assertEquals(-1L, previousLogs[2].id)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLoadPreviousSessionLogsTwoConsecutiveAppStarts() {
        val tempDir = Files.createTempDirectory("app_logger_consecutive_test").toFile()
        try {
            // First app start
            AppLogger.initInternal(tempDir)
            AppLogger.i("AppTag", "Identical message across sessions")
            AppLogger.w("AppTag", "Session 1 distinct warning")
            AppLogger.flush()

            // Second app start in the same test
            AppLogger.initInternal(tempDir)
            AppLogger.i("AppTag", "Identical message across sessions")
            AppLogger.i("AppTag", "Session 2 distinct info")
            AppLogger.flush()

            val previousLogs = AppLogger.loadPreviousSessionLogs()
            // Previous session must contain Session 1's entries:
            // 1. SESSION_START <session1Id>
            // 2. Identical message across sessions
            // 3. Session 1 distinct warning
            assertEquals(3, previousLogs.size)
            assertTrue("First entry must be session 1's SESSION_START", previousLogs[0].message.startsWith("SESSION_START "))
            assertEquals(-3L, previousLogs[0].id)
            assertEquals("Identical message across sessions", previousLogs[1].message)
            assertEquals(-2L, previousLogs[1].id)
            assertEquals("Session 1 distinct warning", previousLogs[2].message)
            assertEquals(-1L, previousLogs[2].id)

            // Current session logs in memory:
            // 1. SESSION_START <session2Id>
            // 2. Identical message across sessions
            // 3. Session 2 distinct info
            val currentLogs = AppLogger.getLogs()
            assertEquals(3, currentLogs.size)
            assertTrue("First current log must be session 2's SESSION_START", currentLogs[0].message.startsWith("SESSION_START "))
            assertEquals(0L, currentLogs[0].id)
            assertEquals("Identical message across sessions", currentLogs[1].message)
            assertEquals(1L, currentLogs[1].id)
            assertEquals("Session 2 distinct info", currentLogs[2].message)
            assertEquals(2L, currentLogs[2].id)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSingleFileCleanupWhenExceeding2MB() {
        val tempDir = Files.createTempDirectory("app_logger_cleanup_detail_test").toFile()
        try {
            AppLogger.initInternal(tempDir)
            val currentFile = File(tempDir, "app.log")

            val linePrefix = "[2026-11-05 08:00:00.000] [INFO] [OldTag] "
            val padding = "A".repeat(500)
            val line = "$linePrefix$padding\n"
            val numLines = (2 * 1024 * 1024 / line.length) + 20
            val sb = StringBuilder()
            for (i in 1..numLines) {
                sb.append("[2026-11-05 08:00:00.000] [INFO] [OldTag] Line $i $padding\n")
            }
            currentFile.writeText(sb.toString())
            assertTrue("File should initially exceed 2MB", currentFile.length() > 2 * 1024 * 1024)

            // Logging another message should trigger cleanup:
            // 1. Read newest 2MB
            // 2. Skip first incomplete line
            // 3. Rewrite app.log
            // 4. Append new entry
            AppLogger.i("NewTag", "Fresh message after cleanup")
            AppLogger.flush()

            assertTrue(currentFile.exists())
            assertTrue("File should not exceed 2MB after cleanup", currentFile.length() <= 2 * 1024 * 1024)
            val content = currentFile.readText()
            assertTrue(content.contains("Fresh message after cleanup"))
            // Check that the file begins cleanly with a complete line header '['
            assertTrue("File should start with a complete line", content.startsWith("["))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLoadPreviousSessionLogsMultiline() {
        val tempDir = Files.createTempDirectory("app_logger_multiline_test").toFile()
        try {
            tempDir.mkdirs()
            val currentFile = File(tempDir, "app.log")
            currentFile.writeText(
                "[2026-11-06 09:00:00.000] [ERROR] [CrashTag] Exception occurred\n" +
                "java.lang.RuntimeException: Test crash\n" +
                "\tat com.example.Test.run(Test.kt:10)\n"
            )

            AppLogger.initInternal(tempDir)

            val previousLogs = AppLogger.loadPreviousSessionLogs()
            assertEquals(1, previousLogs.size)
            assertTrue(previousLogs[0].message.contains("Exception occurred"))
            assertTrue(previousLogs[0].message.contains("java.lang.RuntimeException: Test crash"))
            assertTrue(previousLogs[0].message.contains("at com.example.Test.run(Test.kt:10)"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSingleLogEntryLargerThan2MB() {
        val tempDir = Files.createTempDirectory("app_logger_huge_entry_test").toFile()
        try {
            AppLogger.initInternal(tempDir)
            val currentFile = File(tempDir, "app.log")

            // Log an entry larger than 2MB
            val hugeMessage = "A".repeat(2 * 1024 * 1024 + 100)
            AppLogger.i("HugeTag", hugeMessage)
            AppLogger.flush()

            assertTrue(currentFile.exists())
            assertTrue("File size should not exceed 2MB even with huge entry", currentFile.length() <= 2 * 1024 * 1024)

            // Logging another message works properly
            AppLogger.i("AfterHugeTag", "Subsequent message")
            AppLogger.flush()

            assertTrue(currentFile.exists())
            assertTrue("File size should remain <= 2MB", currentFile.length() <= 2 * 1024 * 1024)
            val text = currentFile.readText()
            assertTrue(text.contains("Subsequent message"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testUtf8CharactersAtRetainedFileBoundary() {
        val tempDir = Files.createTempDirectory("app_logger_utf8_test").toFile()
        try {
            AppLogger.initInternal(tempDir)
            val currentFile = File(tempDir, "app.log")

            // Construct lines containing 3-byte and 4-byte UTF-8 characters
            val linePrefix = "[2026-11-06 10:00:00.000] [INFO] [UtfTag] "
            val emojiString = "🎉🔥🚀✨你好世界Русскийтекст"
            val lineContent = linePrefix + emojiString.repeat(20) + "\n"
            val lineByteSize = lineContent.toByteArray(Charsets.UTF_8).size
            val totalLines = (2 * 1024 * 1024 / lineByteSize) + 50
            val sb = StringBuilder()
            for (i in 1..totalLines) {
                sb.append(lineContent)
            }
            currentFile.writeText(sb.toString(), Charsets.UTF_8)
            assertTrue("Initial file should exceed 2MB", currentFile.length() > 2 * 1024 * 1024)

            AppLogger.i("UtfTag", "Fresh message after UTF-8 cleanup 🎉")
            AppLogger.flush()

            assertTrue(currentFile.exists())
            assertTrue("File size should not exceed 2MB", currentFile.length() <= 2 * 1024 * 1024)
            val content = currentFile.readText(Charsets.UTF_8)
            assertTrue("Content should contain the new message", content.contains("Fresh message after UTF-8 cleanup 🎉"))
            assertTrue("First retained line should be complete and start with '['", content.startsWith("["))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLogPreviewReadsLatest4KBAndSkipsIncompleteFirstLine() {
        val tempDir = Files.createTempDirectory("app_logger_preview_test").toFile()
        try {
            AppLogger.initInternal(tempDir)
            AppLogger.flush()
            val currentFile = File(tempDir, "app.log")

            // Write 50 lines of ~200 bytes each = ~10 KB of logs
            val sb = StringBuilder()
            for (i in 1..50) {
                val padding = "x".repeat(150)
                sb.append("[2026-11-10 10:00:00.000] [INFO] [PreviewTag] Line $i $padding\n")
            }
            currentFile.writeText(sb.toString())

            val preview = AppLogger.loadLogPreview(4096L)
            assertTrue("Preview should not be empty", preview.isNotEmpty())
            assertTrue("Preview should contain latest entries", preview.any { it.message.contains("Line 50") })
            assertFalse("Preview should not contain earliest entries", preview.any { it.message.contains("Line 1 ") })
            val previewTagEntries = preview.filter { it.tag == "PreviewTag" }
            assertTrue("Should contain PreviewTag entries", previewTagEntries.isNotEmpty())
            for (entry in previewTagEntries) {
                assertEquals("PreviewTag", entry.tag)
                assertEquals(LogSeverity.INFO, entry.severity)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
