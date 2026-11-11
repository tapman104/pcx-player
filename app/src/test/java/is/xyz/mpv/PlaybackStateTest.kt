package `is`.xyz.mpv

import android.content.Context
import android.content.ContextWrapper
import `is`.xyz.mpv.feature.room.PlaybackStateDao
import `is`.xyz.mpv.feature.room.PlaybackStateEntity
import `is`.xyz.mpv.feature.room.PlaybackStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class PlaybackStateTest {

    private class FakePlaybackStateDao : PlaybackStateDao {
        private val store = mutableMapOf<String, PlaybackStateEntity>()
        var upsertCount: Int = 0
            private set

        override suspend fun getById(videoPath: String): PlaybackStateEntity? = store[videoPath]

        override suspend fun upsert(state: PlaybackStateEntity) {
            upsertCount++
            store[state.videoPath] = state
        }

        fun seed(state: PlaybackStateEntity) {
            store[state.videoPath] = state
        }
    }

    private fun createTestContext(): ContextWrapper {
        val id = testCounter.incrementAndGet()
        val testDir = File(
            System.getProperty("java.io.tmpdir"),
            "test_pb_state_${System.currentTimeMillis()}_$id",
        ).apply { mkdirs() }
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = testDir
            override fun getCacheDir(): File = testDir
            override fun getDataDir(): File = testDir
        }
    }

    private fun createTrackMap(
        id: Long,
        type: String,
        lang: String? = null,
        title: String? = null,
        selected: Boolean = false,
    ): MPVNode.MapNode {
        val map = mutableMapOf<String, MPVNode>(
            "id" to MPVNode.IntNode(id),
            "type" to MPVNode.StringNode(type),
            "selected" to MPVNode.BooleanNode(selected),
        )
        if (lang != null) map["lang"] = MPVNode.StringNode(lang)
        if (title != null) map["title"] = MPVNode.StringNode(title)
        return MPVNode.MapNode(map)
    }

    private suspend fun waitUntil(timeoutMs: Long = 2000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) {
                delay(10)
            }
        }
    }

    @Before
    fun setUp() {
        MPVLib.clearObservers()
        AppLogger.clear()
    }

    @Test
    fun testRepositoryPreservesOtherFieldsOnPartialUpdates() = runBlocking {
        val context = createTestContext()
        val fakeDao = FakePlaybackStateDao()
        val repo = PlaybackStateRepository(context, fakeDao, Dispatchers.Unconfined)
        val path = "/storage/emulated/0/Movies/sample.mkv"

        assertNull(repo.getPlaybackState(path))

        repo.updatePosition(path, 142.5)
        repo.updateAudioTrack(path, 2)
        repo.updateSubtitleTrack(path, 4)

        val saved = repo.getPlaybackState(path)
        assertNotNull(saved)
        assertEquals(path, saved?.videoPath)
        assertEquals(142.5, saved?.positionSec ?: 0.0, 0.001)
        assertEquals(2, saved?.audioTrackId)
        assertEquals(4, saved?.subtitleTrackId)
        assertTrue((saved?.lastUpdated ?: 0L) > 0L)

        // Updating position later must not clear track overrides
        repo.updatePosition(path, 200.0)
        val afterPosUpdate = repo.getPlaybackState(path)
        assertEquals(200.0, afterPosUpdate?.positionSec ?: 0.0, 0.001)
        assertEquals(2, afterPosUpdate?.audioTrackId)
        assertEquals(4, afterPosUpdate?.subtitleTrackId)

        // Clearing subtitle override to null preserves position and audio track
        repo.updateSubtitleTrack(path, null)
        val afterSubClear = repo.getPlaybackState(path)
        assertEquals(200.0, afterSubClear?.positionSec ?: 0.0, 0.001)
        assertEquals(2, afterSubClear?.audioTrackId)
        assertNull(afterSubClear?.subtitleTrackId)
    }

    @Test
    fun testRestoreSeeksToSavedPositionAndAppliesTrackOverridesOverGlobalDefaults() = runBlocking {
        val context = createTestContext()
        val prefsRepo = UserPreferencesRepository(context)
        prefsRepo.setPreferredAudioLang("en")
        prefsRepo.setPreferredSubtitleLang("es")

        val path = "/storage/emulated/0/Movies/anime.mkv"
        val fakeDao = FakePlaybackStateDao().apply {
            seed(
                PlaybackStateEntity(
                    videoPath = path,
                    positionSec = 125.5,
                    audioTrackId = 2,
                    subtitleTrackId = 4,
                    lastUpdated = 1_000L,
                )
            )
        }
        val playbackRepo = PlaybackStateRepository(context, fakeDao, Dispatchers.Unconfined)
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = PlayerViewModel(
            appContext = context,
            coroutineScope = testScope,
            preferencesRepository = prefsRepo,
            playbackStateRepository = playbackRepo,
        )

        try {
            withTimeout(2000) { viewModel.engine.status.first { it is EngineStatus.Ready } }

            viewModel.loadFile(path, "anime.mkv")

            // Saved position seek is issued on Ready
            waitUntil {
                MPVLib.executedCommands.any { it == listOf("seek", "125.5", "absolute") }
            }

            // Emit track-list where global preferences ("en" -> 1, "es" -> 3) also have matches
            val trackListNode = MPVNode.ArrayNode(
                arrayOf(
                    createTrackMap(id = 1, type = "audio", lang = "eng", selected = true),
                    createTrackMap(id = 2, type = "audio", lang = "jpn", selected = false),
                    createTrackMap(id = 3, type = "sub", lang = "spa", selected = true),
                    createTrackMap(id = 4, type = "sub", lang = "fre", selected = false),
                )
            )
            MPVLib.eventProperty("track-list", trackListNode)

            // Per-video Room override must win over global preferredAudioLang/preferredSubtitleLang
            waitUntil {
                MPVLib.getPropertyString("aid") == "2" && MPVLib.getPropertyString("sid") == "4"
            }
            assertEquals("2", MPVLib.getPropertyString("aid"))
            assertEquals("4", MPVLib.getPropertyString("sid"))

            // Subsequent global preference emission must not override restored per-video selection
            viewModel.commitAudioLang("en")
            viewModel.commitSubtitleLang("es")
            delay(50)
            assertEquals("2", MPVLib.getPropertyString("aid"))
            assertEquals("4", MPVLib.getPropertyString("sid"))
        } finally {
            viewModel.engine.destroy()
        }
    }

    @Test
    fun testGlobalPreferredLanguageUsedWhenPerVideoOverridesAreNull() = runBlocking {
        val context = createTestContext()
        val prefsRepo = UserPreferencesRepository(context)
        prefsRepo.setPreferredAudioLang("ja")
        prefsRepo.setPreferredSubtitleLang("es")

        val path = "/storage/emulated/0/Movies/global_default.mkv"
        val fakeDao = FakePlaybackStateDao().apply {
            seed(
                PlaybackStateEntity(
                    videoPath = path,
                    positionSec = 42.0,
                    audioTrackId = null,
                    subtitleTrackId = null,
                    lastUpdated = 1_000L,
                )
            )
        }
        val playbackRepo = PlaybackStateRepository(context, fakeDao, Dispatchers.Unconfined)
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = PlayerViewModel(
            appContext = context,
            coroutineScope = testScope,
            preferencesRepository = prefsRepo,
            playbackStateRepository = playbackRepo,
        )

        try {
            withTimeout(2000) { viewModel.engine.status.first { it is EngineStatus.Ready } }
            viewModel.loadFile(path)

            waitUntil {
                MPVLib.executedCommands.any { it == listOf("seek", "42", "absolute") }
            }

            val trackListNode = MPVNode.ArrayNode(
                arrayOf(
                    createTrackMap(id = 1, type = "audio", lang = "eng", selected = true),
                    createTrackMap(id = 2, type = "audio", lang = "jpn", selected = false),
                    createTrackMap(id = 3, type = "sub", lang = "eng", selected = true),
                    createTrackMap(id = 4, type = "sub", lang = "spa", selected = false),
                )
            )
            MPVLib.eventProperty("track-list", trackListNode)

            waitUntil {
                MPVLib.getPropertyString("aid") == "2" && MPVLib.getPropertyString("sid") == "4"
            }
            assertEquals("2", MPVLib.getPropertyString("aid"))
            assertEquals("4", MPVLib.getPropertyString("sid"))
        } finally {
            viewModel.engine.destroy()
        }
    }

    @Test
    fun testPeriodicPositionSaveIsThrottledAndManualTrackSelectionPersists() = runBlocking {
        val context = createTestContext()
        val prefsRepo = UserPreferencesRepository(context)
        val fakeDao = FakePlaybackStateDao()
        val playbackRepo = PlaybackStateRepository(context, fakeDao, Dispatchers.Unconfined)
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        var fakeClockMs = 0L
        val viewModel = PlayerViewModel(
            appContext = context,
            coroutineScope = testScope,
            preferencesRepository = prefsRepo,
            playbackStateRepository = playbackRepo,
        ).apply {
            nowMsProvider = { fakeClockMs }
        }

        try {
            withTimeout(2000) { viewModel.engine.status.first { it is EngineStatus.Ready } }
            val path = "/storage/emulated/0/Movies/throttle_test.mp4"
            viewModel.loadFile(path)

            assertEquals(0, fakeDao.upsertCount)

            // Within 5s window -> throttled, no DB write
            fakeClockMs = 1_000L
            MPVLib.eventProperty("time-pos", 1.0)
            fakeClockMs = 4_999L
            MPVLib.eventProperty("time-pos", 4.9)
            assertEquals(0, fakeDao.upsertCount)

            // Reaches 5s interval -> persisted once
            fakeClockMs = 5_000L
            MPVLib.eventProperty("time-pos", 5.0)
            waitUntil { fakeDao.upsertCount == 1 }
            assertEquals(1, fakeDao.upsertCount)
            assertEquals(5.0, fakeDao.getById(path)?.positionSec ?: 0.0, 0.001)

            // Subsequent tick within next 5s -> throttled
            fakeClockMs = 7_500L
            MPVLib.eventProperty("time-pos", 7.5)
            delay(20)
            assertEquals(1, fakeDao.upsertCount)

            // Next 5s interval -> persisted
            fakeClockMs = 10_000L
            MPVLib.eventProperty("time-pos", 10.0)
            waitUntil { fakeDao.upsertCount == 2 }
            assertEquals(2, fakeDao.upsertCount)
            assertEquals(10.0, fakeDao.getById(path)?.positionSec ?: 0.0, 0.001)

            // Manual track selection persists to Room while keeping positionSec intact
            viewModel.selectAudioTrack(3)
            viewModel.selectSubtitleTrack(6)
            waitUntil {
                fakeDao.getById(path)?.audioTrackId == 3 && fakeDao.getById(path)?.subtitleTrackId == 6
            }
            val saved = fakeDao.getById(path)
            assertEquals(10.0, saved?.positionSec ?: 0.0, 0.001)
            assertEquals(3, saved?.audioTrackId)
            assertEquals(6, saved?.subtitleTrackId)

            // Selecting subtitle Off (null) clears subtitleTrackId override
            viewModel.selectSubtitleTrack(null)
            waitUntil {
                fakeDao.getById(path)?.subtitleTrackId == null
            }
            assertNull(fakeDao.getById(path)?.subtitleTrackId)
            assertEquals(3, fakeDao.getById(path)?.audioTrackId)
        } finally {
            viewModel.engine.destroy()
        }
    }

    companion object {
        private val testCounter = AtomicInteger(0)
    }
}

