package `is`.xyz.mpv

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MpvBuiltInProfilesTest {

    private fun createTestContext(): ContextWrapper {
        val id = testCounter.incrementAndGet()
        val testDir = File(System.getProperty("java.io.tmpdir"), "test_prof_${System.currentTimeMillis()}_$id").apply { mkdirs() }
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = testDir
            override fun getCacheDir(): File = testDir
            override fun getDataDir(): File = testDir
        }
    }

    @Before
    fun setUp() {
        MPVLib.clearObservers()
    }

    @Test
    fun testDefaultProfileResolvesWhenNullOrEmpty() {
        val nullResult = MpvBuiltInProfiles.fromId(null)
        assertEquals(MpvBuiltInProfiles.DEFAULT, nullResult)
        assertEquals("default", nullResult.id)
        assertEquals(MpvOptions(), nullResult.options)

        val emptyResult = MpvBuiltInProfiles.fromId("")
        assertEquals(MpvBuiltInProfiles.DEFAULT, emptyResult)
    }

    @Test
    fun testInvalidStoredIdFallsBackSafelyToDefault() {
        val invalidResult = MpvBuiltInProfiles.fromId("unknown_custom_id_123")
        assertEquals(MpvBuiltInProfiles.DEFAULT, invalidResult)
        assertEquals("default", invalidResult.id)
        assertEquals(MpvOptions(), invalidResult.options)
    }

    @Test
    fun testAllBuiltInProfilesExistWithDistinctIdsAndOptions() {
        val profiles = MpvBuiltInProfiles.ALL
        assertEquals(4, profiles.size)

        val ids = profiles.map { it.id }
        assertEquals(4, ids.distinct().size)

        assertEquals(MpvBuiltInProfiles.DEFAULT, MpvBuiltInProfiles.fromId("default"))
        assertEquals(MpvBuiltInProfiles.MOBILE_PERFORMANCE, MpvBuiltInProfiles.fromId("mobile_performance"))
        assertEquals(MpvBuiltInProfiles.BALANCED, MpvBuiltInProfiles.fromId("balanced"))
        assertEquals(MpvBuiltInProfiles.HIGH_QUALITY, MpvBuiltInProfiles.fromId("high_quality"))

        // Default profile wraps MpvOptions() exactly
        assertEquals(MpvOptions(), MpvBuiltInProfiles.DEFAULT.options)
        assertEquals(DecoderMode.AUTO, MpvBuiltInProfiles.DEFAULT.options.hardwareDecoding)

        // Mobile performance enables fast decode and direct hardware decoding
        assertTrue(MpvBuiltInProfiles.MOBILE_PERFORMANCE.options.fastDecode)
        assertFalse(MpvBuiltInProfiles.MOBILE_PERFORMANCE.options.gpuNext)
        assertEquals(DecoderMode.HARDWARE, MpvBuiltInProfiles.MOBILE_PERFORMANCE.options.hardwareDecoding)

        // Balanced sets spline36 scaler and safe auto hardware decoding
        assertEquals("spline36", MpvBuiltInProfiles.BALANCED.options.videoScale)
        assertEquals(DecoderMode.AUTO, MpvBuiltInProfiles.BALANCED.options.hardwareDecoding)

        // High Quality maintains stable GPU renderer, enables ewa_lanczos, GPU debanding, and HW+ copy mode
        assertFalse(MpvBuiltInProfiles.HIGH_QUALITY.options.gpuNext)
        assertEquals("ewa_lanczos", MpvBuiltInProfiles.HIGH_QUALITY.options.videoScale)
        assertEquals(Debanding.GPU, MpvBuiltInProfiles.HIGH_QUALITY.options.debanding)
        assertEquals(DecoderMode.HARDWARE_COPY, MpvBuiltInProfiles.HIGH_QUALITY.options.hardwareDecoding)
    }

    @Test
    fun testUserPreferencesRepositoryDefaultAndPersistence() = runBlocking {
        val context = createTestContext()
        val repo = UserPreferencesRepository(context)

        // 1. Initial / default read
        assertEquals("default", repo.playbackProfileId.first())
        assertEquals(MpvBuiltInProfiles.DEFAULT, repo.playbackProfile.first())

        // 2. Persist valid profile
        repo.setPlaybackProfileId("high_quality")
        assertEquals("high_quality", repo.playbackProfileId.first())
        assertEquals(MpvBuiltInProfiles.HIGH_QUALITY, repo.playbackProfile.first())

        // 3. Persist mobile performance
        repo.setPlaybackProfileId("mobile_performance")
        assertEquals("mobile_performance", repo.playbackProfileId.first())
        assertEquals(MpvBuiltInProfiles.MOBILE_PERFORMANCE, repo.playbackProfile.first())

        // 4. Stored invalid ID falls back safely to Default
        repo.setPlaybackProfileId("corrupted_profile_value")
        assertEquals("default", repo.playbackProfileId.first())
        assertEquals(MpvBuiltInProfiles.DEFAULT, repo.playbackProfile.first())
    }

    @Test
    fun testProfileOptionsReachConfiguratorAndAreAppliedBeforeMpvLibInit() {
        val context = createTestContext()
        val eventHandler = EngineEventHandler()
        val engine = MpvEngine(context, eventHandler)

        val profile = MpvBuiltInProfiles.HIGH_QUALITY
        engine.initialize(
            configDir = "/tmp/config",
            cacheDir = "/tmp/cache",
            options = profile.options,
        )

        assertEquals(EngineStatus.Ready, engine.status.value)
        assertTrue("MPVLib.init() must have been called", MPVLib.isInitialized)

        val optionCalls = MPVLib.setOptionStringCalls.toList()
        assertTrue("Options must have been configured before init", MPVLib.optionsBeforeInitCount > 0)

        // High Quality options
        assertTrue("vo=gpu must be set", optionCalls.contains("vo" to "gpu"))
        assertTrue("scale=ewa_lanczos must be set", optionCalls.contains("scale" to "ewa_lanczos"))
        assertTrue("deband=yes must be set", optionCalls.contains("deband" to "yes"))
        assertTrue("hwdec=mediacodec-copy must be set", optionCalls.contains("hwdec" to "mediacodec-copy"))

        // Hardcoded options remain unconditionally present
        assertTrue("profile=fast must be unconditionally set", optionCalls.contains("profile" to "fast"))
        assertTrue("gpu-context=android must be set", optionCalls.contains("gpu-context" to "android"))
        assertTrue("opengl-es=yes must be set", optionCalls.contains("opengl-es" to "yes"))

        engine.destroy()
    }

    @Test
    fun testMobilePerformanceOptionsReachesMpv() {
        val context = createTestContext()
        val eventHandler = EngineEventHandler()
        val engine = MpvEngine(context, eventHandler)

        val profile = MpvBuiltInProfiles.MOBILE_PERFORMANCE
        engine.initialize(
            configDir = "/tmp/config",
            cacheDir = "/tmp/cache",
            options = profile.options,
        )

        assertEquals(EngineStatus.Ready, engine.status.value)
        val optionCalls = MPVLib.setOptionStringCalls.toList()

        assertTrue("vo=gpu must be set", optionCalls.contains("vo" to "gpu"))
        assertTrue("vd-lavc-fast=yes must be set", optionCalls.contains("vd-lavc-fast" to "yes"))
        assertTrue("vd-lavc-skiploopfilter=nonkey must be set", optionCalls.contains("vd-lavc-skiploopfilter" to "nonkey"))
        assertTrue("hwdec=mediacodec must be set", optionCalls.contains("hwdec" to "mediacodec"))

        engine.destroy()
    }

    @Test
    fun testBalancedOptionsReachesMpv() {
        val context = createTestContext()
        val eventHandler = EngineEventHandler()
        val engine = MpvEngine(context, eventHandler)

        val profile = MpvBuiltInProfiles.BALANCED
        engine.initialize(
            configDir = "/tmp/config",
            cacheDir = "/tmp/cache",
            options = profile.options,
        )

        assertEquals(EngineStatus.Ready, engine.status.value)
        val optionCalls = MPVLib.setOptionStringCalls.toList()

        assertTrue("vo=gpu must be set", optionCalls.contains("vo" to "gpu"))
        assertTrue("scale=spline36 must be set", optionCalls.contains("scale" to "spline36"))
        assertTrue("hwdec=auto-safe must be set", optionCalls.contains("hwdec" to "auto-safe"))

        engine.destroy()
    }

    @Test
    fun testPlayerViewModelInitializesWithActiveProfileOptions() = runBlocking {
        val context = createTestContext()
        val repo = UserPreferencesRepository(context)
        repo.setPlaybackProfileId("high_quality")

        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = PlayerViewModel(context, coroutineScope = testScope)

        withTimeout(2000) {
            viewModel.engine.status.first { it is EngineStatus.Ready }
        }

        assertTrue("Engine must be ready", viewModel.engine.isReady)
        assertTrue("MPVLib.init must have been called", MPVLib.isInitialized)

        val optionCalls = MPVLib.setOptionStringCalls.toList()
        assertTrue("vo=gpu must have been set from selected profile", optionCalls.contains("vo" to "gpu"))
        assertTrue("scale=ewa_lanczos must have been set from selected profile", optionCalls.contains("scale" to "ewa_lanczos"))

        viewModel.engine.destroy()
    }

    @Test
    fun testPlayerViewModelDoesNotLogSuccessWhenInitFails() = runBlocking {
        val context = createTestContext()
        MPVLib.simulateInitFailure = true
        AppLogger.clear()

        try {
            val testScope = CoroutineScope(Dispatchers.Unconfined)
            val viewModel = PlayerViewModel(context, coroutineScope = testScope)

            withTimeout(2000) {
                viewModel.engine.status.first { it is EngineStatus.Failed }
            }
            withTimeout(2000) {
                while (AppLogger.getLogs().none { it.message.contains("Engine init failed") }) {
                    kotlinx.coroutines.delay(10)
                }
            }

            assertFalse("Engine must not be ready", viewModel.engine.isReady)
            val successLogs = AppLogger.getLogs().filter { it.message.contains("Engine init success") }
            assertTrue("Must not log init success when engine is not ready", successLogs.isEmpty())
            val errorLogs = AppLogger.getLogs().filter { it.message.contains("Engine init failed") }
            assertTrue("Must log init failure", errorLogs.isNotEmpty())
        } finally {
            MPVLib.simulateInitFailure = false
        }
    }

    @Test
    fun testEngineLifecycleAndPlaybackFunctionalityPreserved() {
        val context = createTestContext()
        val eventHandler = EngineEventHandler()
        val engine = MpvEngine(context, eventHandler)

        engine.initialize(
            configDir = "/tmp/config",
            cacheDir = "/tmp/cache",
            options = MpvBuiltInProfiles.BALANCED.options,
        )
        assertEquals(EngineStatus.Ready, engine.status.value)

        // Attach surface
        val surface = android.view.Surface(android.graphics.SurfaceTexture(0))
        engine.onSurfaceCreated(surface)
        assertTrue(engine.isSurfaceAttached)

        // Play file
        engine.playFile("/path/to/movie.mkv")
        assertTrue(MPVLib.loadedFiles.contains("/path/to/movie.mkv"))

        // Seek
        engine.seekTo(42.0)
        assertEquals(listOf("seek", "42", "absolute"), MPVLib.executedCommands.last())

        // Destroy
        engine.destroy()
        assertEquals(EngineStatus.NotStarted, engine.status.value)
    }

    companion object {
        private val testCounter = AtomicInteger(0)
    }
}
