package `is`.xyz.mpv

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DecoderModeTest {

    private val testCounter = AtomicInteger(0)

    private fun createTestContext(): ContextWrapper {
        val id = testCounter.incrementAndGet()
        val testDir = File(System.getProperty("java.io.tmpdir"), "test_dec_${System.currentTimeMillis()}_$id").apply { mkdirs() }
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = testDir
            override fun getCacheDir(): File = testDir
            override fun getDataDir(): File = testDir
        }
    }

    @Before
    fun setUp() = runBlocking {
        MPVLib.clearObservers()
        MPVLib.setOptionStringCalls.clear()
        MPVLib.setPropertyStringCalls.clear()
        val context = createTestContext()
        val repo = UserPreferencesRepository(context)
        repo.setDecoderMode(DecoderMode.AUTO)
    }

    @Test
    fun testDecoderDisplayNames() {
        assertEquals("Auto", DecoderMode.AUTO.displayName)
        assertEquals("HW", DecoderMode.HARDWARE.displayName)
        assertEquals("HW+", DecoderMode.HARDWARE_COPY.displayName)
        assertEquals("SW", DecoderMode.SOFTWARE.displayName)
    }

    @Test
    fun testEachModeMapsToCorrectMpvOption() {
        assertEquals("auto-safe", MpvOptionsConfigurator.hwdecOptionValue(DecoderMode.AUTO))
        assertEquals("mediacodec", MpvOptionsConfigurator.hwdecOptionValue(DecoderMode.HARDWARE))
        assertEquals("mediacodec-copy", MpvOptionsConfigurator.hwdecOptionValue(DecoderMode.HARDWARE_COPY))
        assertEquals("no", MpvOptionsConfigurator.hwdecOptionValue(DecoderMode.SOFTWARE))
    }

    @Test
    fun testAllFourModesSavedAndRestored() = runBlocking {
        val context = createTestContext()
        val repo = UserPreferencesRepository(context)

        // Reset and verify AUTO
        repo.setDecoderMode(DecoderMode.AUTO)
        assertEquals(DecoderMode.AUTO, repo.decoderMode.first { it == DecoderMode.AUTO })

        // Save and restore HARDWARE
        repo.setDecoderMode(DecoderMode.HARDWARE)
        assertEquals(DecoderMode.HARDWARE, repo.decoderMode.first { it == DecoderMode.HARDWARE })

        // Save and restore HARDWARE_COPY (HW+)
        repo.setDecoderMode(DecoderMode.HARDWARE_COPY)
        assertEquals(DecoderMode.HARDWARE_COPY, repo.decoderMode.first { it == DecoderMode.HARDWARE_COPY })

        // Save and restore SOFTWARE
        repo.setDecoderMode(DecoderMode.SOFTWARE)
        assertEquals(DecoderMode.SOFTWARE, repo.decoderMode.first { it == DecoderMode.SOFTWARE })

        // Save and restore AUTO
        repo.setDecoderMode(DecoderMode.AUTO)
        assertEquals(DecoderMode.AUTO, repo.decoderMode.first { it == DecoderMode.AUTO })
    }

    @Test
    fun testMpvOptionsConfiguratorConfiguresHwdecCorrectly() {
        val context = createTestContext()
        val configurator = MpvOptionsConfigurator(context)

        for (mode in DecoderMode.entries) {
            MPVLib.setOptionStringCalls.clear()
            configurator.configure(MpvOptions(hardwareDecoding = mode))
            val expectedOption = MpvOptionsConfigurator.hwdecOptionValue(mode)
            assertTrue(
                "Configurator must set hwdec=$expectedOption for $mode",
                MPVLib.setOptionStringCalls.contains("hwdec" to expectedOption)
            )
        }
    }

    @Test
    fun testEngineSetDecoderModeAppliesRuntimeProperty() {
        val context = createTestContext()
        val eventHandler = EngineEventHandler()
        val engine = MpvEngine(context, eventHandler)

        engine.initialize(
            configDir = "/tmp/config",
            cacheDir = "/tmp/cache",
            options = MpvOptions(hardwareDecoding = DecoderMode.AUTO),
        )

        assertEquals(EngineStatus.Ready, engine.status.value)

        for (mode in DecoderMode.entries) {
            engine.setDecoderMode(mode)
            val expectedValue = MpvOptionsConfigurator.hwdecOptionValue(mode)
            assertEquals(
                expectedValue,
                MPVLib.setPropertiesString["hwdec"]
            )
        }

        engine.destroy()
    }

    private suspend fun waitUntil(timeoutMs: Long = 5000, condition: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition not met within ${timeoutMs}ms")
            }
            kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun testSingleSourceOfTruthBetweenScreensAndPlayerViewModel() = runBlocking {
        val context = createTestContext()
        val repo = UserPreferencesRepository(context)

        // 1. User selects HW+ in main settings
        repo.setDecoderMode(DecoderMode.HARDWARE_COPY)

        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = PlayerViewModel(context, coroutineScope = testScope, preferencesRepository = repo)

        // Keep active subscriber on decoderMode StateFlow (as Compose UI does via collectAsState)
        val subscriptionJob = testScope.launch { viewModel.decoderMode.collect {} }

        try {
            waitUntil { viewModel.engine.status.value is EngineStatus.Ready }
            waitUntil { viewModel.decoderMode.value == DecoderMode.HARDWARE_COPY }
            waitUntil { MPVLib.setPropertiesString["hwdec"] == "mediacodec-copy" }
            assertEquals(DecoderMode.HARDWARE_COPY, viewModel.decoderMode.value)
            assertEquals("mediacodec-copy", MPVLib.setPropertiesString["hwdec"])

            // 2. User changes to HW in player menu (MoreOptionsSheet)
            viewModel.setDecoderMode(DecoderMode.HARDWARE)

            // Single source of truth: repository and viewModel both update to HARDWARE
            waitUntil { repo.decoderMode.first() == DecoderMode.HARDWARE }
            waitUntil { viewModel.decoderMode.value == DecoderMode.HARDWARE }
            waitUntil { MPVLib.setPropertiesString["hwdec"] == "mediacodec" }
            // Engine runtime property was updated on the fly
            assertEquals("mediacodec", MPVLib.setPropertiesString["hwdec"])

            // 3. User changes to SW in player menu
            viewModel.setDecoderMode(DecoderMode.SOFTWARE)
            waitUntil { repo.decoderMode.first() == DecoderMode.SOFTWARE }
            waitUntil { viewModel.decoderMode.value == DecoderMode.SOFTWARE }
            waitUntil { MPVLib.setPropertiesString["hwdec"] == "no" }
            assertEquals("no", MPVLib.setPropertiesString["hwdec"])
        } finally {
            subscriptionJob.cancel()
            viewModel.engine.destroy()
        }
    }
}
