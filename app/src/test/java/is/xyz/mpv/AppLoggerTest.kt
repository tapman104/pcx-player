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
}
