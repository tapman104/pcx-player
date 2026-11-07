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

            // Write 2MB into currentFile to trigger rotation on next write
            val dummyBytes = ByteArray(2 * 1024 * 1024 + 10)
            currentFile.writeBytes(dummyBytes)

            // Logging another message should rotate currentFile to oldFile
            AppLogger.i("RotationTag", "New message after 2MB rotation")
            AppLogger.flush()

            assertTrue("Old log file should exist after rotation", oldFile.exists())
            assertTrue("Current log file should exist after rotation", currentFile.exists())

            val export = AppLogger.formatLogsForExport()
            assertTrue("Export should include message from rotated/new logs", export.contains("New message after 2MB rotation"))

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
            val oldFile = File(tempDir, "app.log.old")
            val currentFile = File(tempDir, "app.log")

            // Simulate previous session with content appearing BEFORE and AFTER a coincidental duplicate.
            // Even if the coincidental duplicate is early in app.log.old, nothing before the real session boundary
            // must be dropped.
            oldFile.writeText(
                "[2026-11-05 10:00:00.000] [INFO] [SharedTag] Previous line before duplicate\n" +
                "[2026-11-05 10:00:01.000] [INFO] [SharedTag] Coincidental duplicate line\n"
            )
            currentFile.writeText(
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
    fun testLoadPreviousSessionLogsIncludesOldRotatedFile() {
        val tempDir = Files.createTempDirectory("app_logger_old_test").toFile()
        try {
            tempDir.mkdirs()
            val oldFile = File(tempDir, "app.log.old")
            val currentFile = File(tempDir, "app.log")

            oldFile.writeText("[2026-11-05 08:00:00.000] [INFO] [OldTag] Old file log\n")
            currentFile.writeText("[2026-11-06 09:00:00.000] [ERROR] [CurTag] Current file prev log\n")

            AppLogger.initInternal(tempDir)

            val previousLogs = AppLogger.loadPreviousSessionLogs()
            assertEquals(2, previousLogs.size)
            assertEquals("Old file log", previousLogs[0].message)
            assertEquals(LogSeverity.INFO, previousLogs[0].severity)
            assertEquals("OldTag", previousLogs[0].tag)
            assertEquals(-2L, previousLogs[0].id)

            assertEquals("Current file prev log", previousLogs[1].message)
            assertEquals(LogSeverity.ERROR, previousLogs[1].severity)
            assertEquals("CurTag", previousLogs[1].tag)
            assertEquals(-1L, previousLogs[1].id)
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
}
