package `is`.xyz.mpv

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class PlayerViewModelTest {

    private fun createTestContext(): ContextWrapper {
        val id = testCounter.incrementAndGet()
        val testDir = File(System.getProperty("java.io.tmpdir"), "test_pv_${System.currentTimeMillis()}_$id").apply { mkdirs() }
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
        if (lang != null) {
            map["lang"] = MPVNode.StringNode(lang)
        }
        if (title != null) {
            map["title"] = MPVNode.StringNode(title)
        }
        return MPVNode.MapNode(map)
    }

    @Before
    fun setUp() = runBlocking {
        MPVLib.clearObservers()
        val context = createTestContext()
        val repo = UserPreferencesRepository(context)
        repo.setPreferredAudioLang(null)
        repo.setPreferredSubtitleLang(null)
    }

    @Test
    fun testPlayFileResetsTrackStateOnNewFileLoad() {
        val context = createTestContext()
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = PlayerViewModel(context, coroutineScope = testScope)

        // Simulate loaded tracks and playback state from mpv
        MPVLib.eventProperty("pause", false)
        MPVLib.eventProperty("media-title", "Previous Video")
        assertEquals(true, viewModel.uiState.value.isPlaying)
        assertEquals("Previous Video", viewModel.uiState.value.currentFileName)

        // Switching to a new file via loadFile -> engine.playFile must reset track and pause state
        viewModel.loadFile("/path/to/next_video.mp4")

        val trackState = viewModel.trackState.value
        assertTrue(trackState.audioTracks.isEmpty())
        assertTrue(trackState.subtitleTracks.isEmpty())
        assertNull(trackState.selectedAudioId)
        assertNull(trackState.selectedSubtitleId)
        assertNull(trackState.mediaTitle)
        assertNull(trackState.isPaused)

        // isPlaying must also reset to null (undetermined) for the new file
        assertNull(viewModel.uiState.value.isPlaying)
    }

    @Test
    fun testUiStateIsPlayingBeforeAndAfterPauseEvents() {
        val context = createTestContext()
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = PlayerViewModel(context, coroutineScope = testScope)

        // 1. Before any pause event has been received: uiState.isPlaying is null
        assertNull(viewModel.uiState.value.isPlaying)

        // 2. mpv reports pause = false: uiState.isPlaying is true
        MPVLib.eventProperty("pause", false)
        assertEquals(true, viewModel.uiState.value.isPlaying)

        // 3. mpv reports pause = true: uiState.isPlaying is false
        MPVLib.eventProperty("pause", true)
        assertEquals(false, viewModel.uiState.value.isPlaying)
    }

    @Test
    fun testMatchesLanguage() {
        // 2-letter exact
        assertTrue(matchesLanguage("en", "en"))
        assertTrue(matchesLanguage("ja", "ja"))
        assertTrue(matchesLanguage("es", "es"))

        // 3-letter ISO match
        assertTrue(matchesLanguage("eng", "en"))
        assertTrue(matchesLanguage("jpn", "ja"))
        assertTrue(matchesLanguage("spa", "es"))
        assertTrue(matchesLanguage("deu", "de"))

        // Dialect prefix
        assertTrue(matchesLanguage("en-US", "en"))
        assertTrue(matchesLanguage("es-419", "es"))
        assertTrue(matchesLanguage("pt-BR", "pt"))

        // Case insensitivity
        assertTrue(matchesLanguage("ENG", "en"))
        assertTrue(matchesLanguage("En", "EN"))

        // Mismatches
        assertFalse(matchesLanguage("fra", "es"))
        assertFalse(matchesLanguage("eng", "ja"))

        // Null and blank
        assertFalse(matchesLanguage(null, "en"))
        assertFalse(matchesLanguage("", "en"))
        assertFalse(matchesLanguage("   ", "en"))
    }

    @Test
    fun testSystemDefaultPassesEmptyStringToAlangAndSlang() = runBlocking {
        val context = createTestContext()
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = PlayerViewModel(context, coroutineScope = testScope)

        // Wait for engine to initialize
        assertTrue(viewModel.engine.isReady)

        // With null preference (system default), alang and slang are set to empty string
        assertEquals("", MPVLib.getPropertyString("alang"))
        assertEquals("", MPVLib.getPropertyString("slang"))

        // When language preference is committed, alang/slang receives the code
        viewModel.commitAudioLang("ja")
        viewModel.commitSubtitleLang("en")
        withTimeoutOrNull(2000) {
            while (MPVLib.getPropertyString("alang") != "ja" || MPVLib.getPropertyString("slang") != "en") {
                delay(10)
            }
        }
        assertEquals("ja", MPVLib.getPropertyString("alang"))
        assertEquals("en", MPVLib.getPropertyString("slang"))

        // Clearing back to null (System default) passes empty string again
        viewModel.commitAudioLang(null)
        viewModel.commitSubtitleLang(null)
        withTimeoutOrNull(2000) {
            while (MPVLib.getPropertyString("alang") != "" || MPVLib.getPropertyString("slang") != "") {
                delay(10)
            }
        }
        assertEquals("", MPVLib.getPropertyString("alang"))
        assertEquals("", MPVLib.getPropertyString("slang"))
    }

    @Test
    fun testMidPlaybackLanguageChangeTriggersReselection() = runBlocking {
        val context = createTestContext()
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = PlayerViewModel(context, coroutineScope = testScope)
        assertTrue(viewModel.engine.isReady)

        // Feed track-list into engine event handler
        val trackListNode = MPVNode.ArrayNode(
            arrayOf(
                createTrackMap(id = 1, type = "audio", lang = "eng", title = "English Audio", selected = true),
                createTrackMap(id = 2, type = "audio", lang = "jpn", title = "Japanese Audio", selected = false),
                createTrackMap(id = 3, type = "sub", lang = "eng", title = "English Sub", selected = true),
                createTrackMap(id = 4, type = "sub", lang = "spa", title = "Spanish Sub", selected = false),
            )
        )
        MPVLib.eventProperty("track-list", trackListNode)
        MPVLib.eventProperty("aid", "1")
        MPVLib.eventProperty("sid", "3")

        assertEquals(1, viewModel.trackState.value.selectedAudioId)
        assertEquals(3, viewModel.trackState.value.selectedSubtitleId)

        // Changing audio language mid-playback explicitly selects matching track 2
        viewModel.commitAudioLang("ja")
        withTimeoutOrNull(2000) {
            while (MPVLib.getPropertyString("aid") != "2") {
                delay(10)
            }
        }
        assertEquals("2", MPVLib.getPropertyString("aid"))

        // Changing subtitle language mid-playback explicitly selects matching track 4
        viewModel.commitSubtitleLang("es")
        withTimeoutOrNull(2000) {
            while (MPVLib.getPropertyString("sid") != "4") {
                delay(10)
            }
        }
        assertEquals("4", MPVLib.getPropertyString("sid"))
    }

    @Test
    fun testManualTrackSelectionNotOverriddenByUnrelatedPreferenceEmissions() = runBlocking {
        val context = createTestContext()
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = PlayerViewModel(context, coroutineScope = testScope)
        assertTrue(viewModel.engine.isReady)

        val trackListNode = MPVNode.ArrayNode(
            arrayOf(
                createTrackMap(id = 1, type = "audio", lang = "eng", selected = false),
                createTrackMap(id = 2, type = "audio", lang = "jpn", selected = false),
            )
        )
        MPVLib.eventProperty("track-list", trackListNode)

        // User manually selects track 1
        viewModel.selectAudioTrack(1)
        assertEquals("1", MPVLib.getPropertyString("aid"))

        // User changes unrelated subtitle appearance settings
        viewModel.commitSubFontSize(70)
        viewModel.subFontSize.first { it == 70 }
        viewModel.commitSubPos(85)
        viewModel.subPos.first { it == 85 }

        // Manual audio track selection remains 1, not overridden!
        assertEquals("1", MPVLib.getPropertyString("aid"))
    }

    @Test
    fun testPreferencesRepositoryPersistsAndDefaultsToNull() = runBlocking {
        val context = createTestContext()
        val repo = UserPreferencesRepository(context)

        // Defaults to null (System default)
        assertNull(repo.preferredAudioLang.first())
        assertNull(repo.preferredSubtitleLang.first())

        // Persist code
        repo.setPreferredAudioLang("de")
        repo.setPreferredSubtitleLang("fr")
        assertEquals("de", repo.preferredAudioLang.first())
        assertEquals("fr", repo.preferredSubtitleLang.first())

        // Clear to null (System default)
        repo.setPreferredAudioLang(null)
        repo.setPreferredSubtitleLang(null)
        assertNull(repo.preferredAudioLang.first())
        assertNull(repo.preferredSubtitleLang.first())
    }

    companion object {
        private val testCounter = AtomicInteger(0)
    }
}
