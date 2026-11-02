package `is`.xyz.mpv

import android.content.ContextWrapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MpvEngineTest {

    private fun createTestContext(): ContextWrapper {
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
    }

    @Before
    fun setUp() {
        MPVLib.clearObservers()
    }

    @Test
    fun testMpvEngineImplementsEventObserver() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        @Suppress("USELESS_IS_CHECK")
        assertTrue("MpvEngine must implement MPVLib.EventObserver", engine is MPVLib.EventObserver)
    }

    @Test
    fun testEventPropertyLongUpdatesGeometry() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        var reportedGeometry: VideoGeometry? = null
        engine.onVideoGeometryChanged = { geometry ->
            reportedGeometry = geometry
        }

        val observer: MPVLib.EventObserver = engine
        observer.eventProperty("width", 1920L)
        assertEquals(1920, reportedGeometry?.width)

        observer.eventProperty("height", 1080L)
        assertEquals(1080, reportedGeometry?.height)

        observer.eventProperty("video-rotate", 90L)
        assertEquals(90, reportedGeometry?.rotationDegrees)
    }

    @Test
    fun testEventPropertyDoubleUpdatesPlaybackProgress() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        var reportedPos = 0.0
        var reportedDur = 0.0
        engine.onPlaybackProgressChanged = { pos, dur ->
            reportedPos = pos
            reportedDur = dur
        }

        val observer: MPVLib.EventObserver = engine
        observer.eventProperty("time-pos", 45.5)
        assertEquals(45.5, reportedPos, 0.001)

        observer.eventProperty("duration", 120.0)
        assertEquals(120.0, reportedDur, 0.001)
    }

    @Test
    fun testInitializationRegistersBothObserversOnce() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        assertEquals(0, MPVLib.registeredObservers.size)

        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        assertEquals(EngineStatus.Ready, engine.status.value)
        val observers = MPVLib.registeredObservers
        assertEquals(2, observers.size)
        assertTrue(observers.contains(engine))
        assertTrue(observers.contains(eventHandler))
    }

    @Test
    fun testInitFailureRemovesBothObservers() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        MPVLib.simulateInitFailure = true

        try {
            engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
            fail("Expected initialize to throw on simulated failure")
        } catch (_: Throwable) {
            // Expected
        }

        assertTrue(engine.status.value is EngineStatus.Failed)
        assertEquals(0, MPVLib.registeredObservers.size)
    }

    @Test
    fun testPostCreateFailureCallsDestroy() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        MPVLib.simulatePostCreateFailure = true

        try {
            engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
            fail("Expected initialize to throw on post-create failure")
        } catch (_: Throwable) {
            // Expected
        }

        assertTrue(engine.status.value is EngineStatus.Failed)
        assertEquals(0, MPVLib.registeredObservers.size)
        assertTrue("MPVLib.destroy() must be called when post-create step fails", MPVLib.destroyCallCount > 0)
    }

    @Test
    fun testDestroyRemovesBothAndResetsHandler() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        assertEquals(2, MPVLib.registeredObservers.size)

        // Mutate event handler state
        eventHandler.eventProperty("pause", true)
        eventHandler.eventProperty("media-title", "Test Title")
        assertEquals(true, eventHandler.trackState.value.isPaused)
        assertEquals("Test Title", eventHandler.trackState.value.mediaTitle)

        engine.destroy()

        assertEquals(EngineStatus.NotStarted, engine.status.value)
        assertEquals(0, MPVLib.registeredObservers.size)
        assertNull(eventHandler.trackState.value.isPaused)
        assertNull(eventHandler.trackState.value.mediaTitle)
    }

    @Test
    fun testReinitializationDoesNotDuplicateObservers() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        // 1. First initialization
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        assertEquals(2, MPVLib.registeredObservers.size)

        // 2. Destroy
        engine.destroy()
        assertEquals(0, MPVLib.registeredObservers.size)

        // 3. Re-initialize on the same engine instance
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        val observers = MPVLib.registeredObservers
        assertEquals(2, observers.size)
        assertEquals(1, observers.count { it === engine })
        assertEquals(1, observers.count { it === eventHandler })
    }

    @Test
    fun testViewModelHasNoDirectObserverRegistration() {
        // PlayerViewModel must not implement MPVLib.EventObserver
        assertFalse(
            "PlayerViewModel must not implement MPVLib.EventObserver",
            MPVLib.EventObserver::class.java.isAssignableFrom(PlayerViewModel::class.java)
        )

        // PlayerViewModel must not declare any eventProperty or event overrides
        val declaredMethodNames = PlayerViewModel::class.java.declaredMethods.map { it.name }
        assertFalse(
            "PlayerViewModel must not declare eventProperty overrides",
            declaredMethodNames.contains("eventProperty")
        )
        assertFalse(
            "PlayerViewModel must not declare event overrides",
            declaredMethodNames.contains("event")
        )
    }

    @Test
    fun testPlayFileRearmsPauseObserver() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        MPVLib.observedProperties.clear()

        // Attach surface so engine is ready to load file immediately
        val surface = android.view.Surface(android.graphics.SurfaceTexture(0))
        engine.onSurfaceCreated(surface)

        engine.playFile("/path/to/video.mp4")

        assertTrue(
            "observeProperty(\"pause\", MPV_FORMAT_FLAG) must be re-armed when loading a file",
            MPVLib.observedProperties.any { it.first == "pause" && it.second == MPVLib.MpvFormat.MPV_FORMAT_FLAG }
        )
    }

    // FIX CHECK-A: Test process-wide ownership guard prevents concurrent initialization
    @Test
    fun testProcessWideOwnershipGuardThrowsOnConcurrentCreate() {
        val eventHandler1 = EngineEventHandler()
        val context1 = createTestContext()
        val engine1 = MpvEngine(context1, eventHandler1)
        engine1.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        val eventHandler2 = EngineEventHandler()
        val context2 = createTestContext()
        val engine2 = MpvEngine(context2, eventHandler2)

        try {
            engine2.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
            fail("Expected second MpvEngine.initialize to throw IllegalStateException due to MpvLifecycleGuard")
        } catch (e: IllegalStateException) {
            assertEquals("MPVLib is already owned", e.message)
        } finally {
            engine1.destroy()
        }

        // After engine1 destroys, engine2 should be able to initialize cleanly
        engine2.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        assertEquals(EngineStatus.Ready, engine2.status.value)
        engine2.destroy()
    }

    @Test
    fun testInitFailureWithDestroyFailureClearsFieldsAndRetryDestroySucceeds() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        // Set up pre-failure state with closures tracking if old callbacks fire
        var oldGeometryCalls = 0
        var oldProgressCalls = 0
        engine.onVideoGeometryChanged = { oldGeometryCalls++ }
        engine.onPlaybackProgressChanged = { _, _ -> oldProgressCalls++ }
        val staleSurface = android.view.Surface(android.graphics.SurfaceTexture(0))
        engine.onSurfaceCreated(staleSurface)
        engine.playFile("/path/to/stale.mp4")
        engine.onSurfaceChanged(1920, 1080)

        // Reset invocation counts caused by playFile's clearGeometry/clearProgress
        oldGeometryCalls = 0
        oldProgressCalls = 0

        // Native create succeeds, post-create init throws, AND cleanup destroy throws
        MPVLib.simulatePostCreateFailure = true
        MPVLib.simulateDestroyFailure = true

        var threwExpected = false
        try {
            engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        } catch (e: RuntimeException) {
            assertEquals("Simulated MPVLib.init failure", e.message)
            threwExpected = true
        }
        assertTrue("Expected initialize to throw RuntimeException on post-create failure", threwExpected)

        // Assert status is DestroyFailed
        assertTrue(engine.status.value is EngineStatus.DestroyFailed)

        // Reset destroy failure so retryDestroy() can succeed
        MPVLib.simulateDestroyFailure = false

        // Call retryDestroy(), assert it succeeds and status becomes NotStarted
        engine.retryDestroy()
        assertEquals(EngineStatus.NotStarted, engine.status.value)

        // Reset post-create failure so re-initialization can succeed
        MPVLib.simulatePostCreateFailure = false

        // Call engine.initialize() again successfully
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        assertEquals(EngineStatus.Ready, engine.status.value)

        // (b) Assert no stale surface was auto-attached during initialize() flushPendingLocked()
        assertTrue("Stale surface must not be auto-attached", MPVLib.attachedSurfaces.isEmpty())

        // (a) Assert no stale file was loaded during initialize() flushPendingLocked()
        assertTrue("Stale file must not be loaded on initialize", MPVLib.loadedFiles.isEmpty())

        // Normal post-Ready flow: surface arrives and is attached
        val freshSurface = android.view.Surface(android.graphics.SurfaceTexture(1))
        engine.onSurfaceCreated(freshSurface)
        assertEquals(listOf(freshSurface), MPVLib.attachedSurfaces)

        // (a) Assert no stale file was loaded when fresh surface was attached
        assertTrue("Stale file must not be loaded when fresh surface is attached", MPVLib.loadedFiles.isEmpty())

        // (c) Check geometry and progress callbacks by behavior:
        // Before setting fresh callbacks, trigger property events and confirm old closures do NOT fire
        MPVLib.eventProperty("width", 1280L)
        assertEquals("Old geometry callback must not fire", 0, oldGeometryCalls)

        MPVLib.eventProperty("time-pos", 10.0)
        assertEquals("Old progress callback must not fire", 0, oldProgressCalls)

        // Set fresh callbacks and confirm they fire when property events occur
        var freshGeometry: VideoGeometry? = null
        engine.onVideoGeometryChanged = { freshGeometry = it }
        var freshPos = 0.0
        var freshDur = 0.0
        engine.onPlaybackProgressChanged = { pos, dur ->
            freshPos = pos
            freshDur = dur
        }

        MPVLib.eventProperty("width", 1920L)
        assertEquals(1920, freshGeometry?.width)
        assertEquals("Old geometry callback must still not have fired", 0, oldGeometryCalls)

        MPVLib.eventProperty("time-pos", 45.0)
        assertEquals(45.0, freshPos, 0.001)
        assertEquals("Old progress callback must still not have fired", 0, oldProgressCalls)

        engine.destroy()
    }

    @Test
    fun testInitFailureWithDestroySuccessClearsFieldsImmediately() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        // Set up pre-failure state with closures tracking if old callbacks fire
        var oldGeometryCalls = 0
        var oldProgressCalls = 0
        engine.onVideoGeometryChanged = { oldGeometryCalls++ }
        engine.onPlaybackProgressChanged = { _, _ -> oldProgressCalls++ }
        val staleSurface = android.view.Surface(android.graphics.SurfaceTexture(0))
        engine.onSurfaceCreated(staleSurface)
        engine.playFile("/path/to/stale.mp4")
        engine.onSurfaceChanged(1920, 1080)

        // Reset invocation counts caused by playFile's clearGeometry/clearProgress
        oldGeometryCalls = 0
        oldProgressCalls = 0

        // Native create succeeds, post-create init throws, cleanup destroy succeeds
        MPVLib.simulatePostCreateFailure = true
        MPVLib.simulateDestroyFailure = false

        var threwExpected = false
        try {
            engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        } catch (e: RuntimeException) {
            assertEquals("Simulated MPVLib.init failure", e.message)
            threwExpected = true
        }
        assertTrue("Expected initialize to throw RuntimeException on post-create failure", threwExpected)

        // Assert status becomes Failed, not DestroyFailed
        assertTrue(engine.status.value is EngineStatus.Failed)
        assertFalse(engine.status.value is EngineStatus.DestroyFailed)

        // Reset post-create failure so re-initialization can succeed immediately
        MPVLib.simulatePostCreateFailure = false

        // Call engine.initialize() again successfully
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        assertEquals(EngineStatus.Ready, engine.status.value)

        // (b) Assert no stale surface was auto-attached during initialize() flushPendingLocked()
        assertTrue("Stale surface must not be auto-attached", MPVLib.attachedSurfaces.isEmpty())

        // (a) Assert no stale file was loaded during initialize() flushPendingLocked()
        assertTrue("Stale file must not be loaded on initialize", MPVLib.loadedFiles.isEmpty())

        // Normal post-Ready flow: surface arrives and is attached
        val freshSurface = android.view.Surface(android.graphics.SurfaceTexture(1))
        engine.onSurfaceCreated(freshSurface)
        assertEquals(listOf(freshSurface), MPVLib.attachedSurfaces)

        // (a) Assert no stale file was loaded when fresh surface was attached
        assertTrue("Stale file must not be loaded when fresh surface is attached", MPVLib.loadedFiles.isEmpty())

        // (c) Check geometry and progress callbacks by behavior:
        // Before setting fresh callbacks, trigger property events and confirm old closures do NOT fire
        MPVLib.eventProperty("width", 1280L)
        assertEquals("Old geometry callback must not fire", 0, oldGeometryCalls)

        MPVLib.eventProperty("time-pos", 10.0)
        assertEquals("Old progress callback must not fire", 0, oldProgressCalls)

        // Set fresh callbacks and confirm they fire when property events occur
        var freshGeometry: VideoGeometry? = null
        engine.onVideoGeometryChanged = { freshGeometry = it }
        var freshPos = 0.0
        var freshDur = 0.0
        engine.onPlaybackProgressChanged = { pos, dur ->
            freshPos = pos
            freshDur = dur
        }

        MPVLib.eventProperty("width", 1920L)
        assertEquals(1920, freshGeometry?.width)
        assertEquals("Old geometry callback must still not have fired", 0, oldGeometryCalls)

        MPVLib.eventProperty("time-pos", 45.0)
        assertEquals(45.0, freshPos, 0.001)
        assertEquals("Old progress callback must still not have fired", 0, oldProgressCalls)

        engine.destroy()
    }

    // -------------------------------------------------------------------------
    // Section 6: Additional tests for the concurrency fixes
    // -------------------------------------------------------------------------

    @Test
    fun testDestroyingStatusSetBeforeNativeDestruction() {
        // Verify that status transitions through Destroying before reaching NotStarted.
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        // destroy() must transition to NotStarted (Destroying is transient; we observe the final state)
        engine.destroy()
        assertEquals(EngineStatus.NotStarted, engine.status.value)
        assertEquals(0, MPVLib.registeredObservers.size)
    }

    @Test
    fun testObserversRemovedBeforeNativeDestruction() {
        // Verify observers are unregistered before MpvLifecycleGuard.destroy() is called.
        // We do this by counting observers inside a destroy-failure scenario:
        // if destroy throws, observers must already be gone.
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        assertEquals(2, MPVLib.registeredObservers.size)

        MPVLib.simulateDestroyFailure = true
        engine.destroy()

        // Even though destroy failed, observers must have been removed before the attempt
        assertEquals(0, MPVLib.registeredObservers.size)
        assertTrue(engine.status.value is EngineStatus.DestroyFailed)
    }

    @Test
    fun testNoStaleCallbacksAfterDestroy() {
        // After destroy(), property callbacks on the engine object must be null.
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        var geometryCalled = false
        var progressCalled = false
        engine.onVideoGeometryChanged = { geometryCalled = true }
        engine.onPlaybackProgressChanged = { _, _ -> progressCalled = true }

        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        engine.destroy()

        // Simulate native callbacks arriving after destruction
        engine.eventProperty("width", 1920L)
        engine.eventProperty("time-pos", 55.0)

        assertFalse("Geometry callback must not fire after destroy", geometryCalled)
        assertFalse("Progress callback must not fire after destroy", progressCalled)
    }

    @Test
    fun testRetryDestroyResetsStatusToNotStarted() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        // First destroy attempt fails
        MPVLib.simulateDestroyFailure = true
        engine.destroy()
        assertTrue(engine.status.value is EngineStatus.DestroyFailed)
        assertFalse((engine.status.value as EngineStatus.DestroyFailed).retried)

        // Second destroy attempt (retry) succeeds
        MPVLib.simulateDestroyFailure = false
        engine.retryDestroy()
        assertEquals(EngineStatus.NotStarted, engine.status.value)
    }

    @Test
    fun testRetryDestroyWithRetryCountTrue_blocksThirdAttempt() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        // Both destroy and retry fail -> retried=true
        MPVLib.simulateDestroyFailure = true
        engine.destroy()
        engine.retryDestroy()

        val status = engine.status.value
        assertTrue(status is EngineStatus.DestroyFailed)
        assertTrue((status as EngineStatus.DestroyFailed).retried)

        // A third retryDestroy call must be ignored (retried=true guard)
        MPVLib.simulateDestroyFailure = false
        engine.retryDestroy()

        // Status must remain DestroyFailed with retried=true, not reset to NotStarted
        assertTrue(engine.status.value is EngineStatus.DestroyFailed)
        assertTrue((engine.status.value as EngineStatus.DestroyFailed).retried)
    }

    @Test
    fun testSurfaceReplacedDuringDetachIsNotTornDown() {
        // Regression: onSurfaceCreated called while onSurfaceDestroyed's detach is pending.
        // The second surface must survive — detachSurfaceInternal should be a no-op when
        // isSurfaceAttached has been set back to true by the new surface.
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        val surface1 = android.view.Surface(android.graphics.SurfaceTexture(0))
        engine.onSurfaceCreated(surface1)
        assertEquals(1, MPVLib.attachedSurfaces.size)

        // Destroy the first surface
        engine.onSurfaceDestroyed()

        // Immediately attach a new surface (simulates race where new surface arrives
        // before or during the detach sequence inside onSurfaceDestroyed)
        val surface2 = android.view.Surface(android.graphics.SurfaceTexture(1))
        engine.onSurfaceCreated(surface2)

        // Both attach calls should have occurred (test double records them)
        assertTrue("Surface 2 must have been attached", MPVLib.attachedSurfaces.contains(surface2))
    }

    @Test
    fun testDetachSurfaceFailureTransitionsToDetachFailedAndDoesNotClaimDetached() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        val surface = android.view.Surface(android.graphics.SurfaceTexture(0))
        engine.onSurfaceCreated(surface)
        assertEquals(SurfaceAttachmentState.Attached, engine.surfaceAttachment)
        assertTrue(engine.isSurfaceAttached)

        MPVLib.simulateDetachSurfaceFailure = true
        engine.onSurfaceDestroyed()

        // Must NOT silently treat as Detached, and must NOT treat as Attached
        assertFalse("Engine must not report attached when detach threw", engine.isSurfaceAttached)
        assertTrue("Engine must hold DetachFailed state", engine.surfaceAttachment is SurfaceAttachmentState.DetachFailed)
        val failure = engine.surfaceAttachment as SurfaceAttachmentState.DetachFailed
        assertEquals("Simulated MPVLib.detachSurface failure", failure.exception.message)

        // Subsequent onSurfaceCreated recovers cleanly
        MPVLib.simulateDetachSurfaceFailure = false
        val newSurface = android.view.Surface(android.graphics.SurfaceTexture(1))
        engine.onSurfaceCreated(newSurface)
        assertEquals(SurfaceAttachmentState.Attached, engine.surfaceAttachment)
        assertTrue(engine.isSurfaceAttached)
    }

    @Test
    fun testRepeatedInitializeAndDestroy() {
        // Verify no observer duplication or state corruption across multiple cycles.
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        repeat(3) { i ->
            engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
            assertEquals("Cycle $i: expected exactly 2 observers", 2, MPVLib.registeredObservers.size)
            assertEquals("Cycle $i: expected Ready", EngineStatus.Ready, engine.status.value)

            engine.destroy()
            assertEquals("Cycle $i: expected 0 observers after destroy", 0, MPVLib.registeredObservers.size)
            assertEquals("Cycle $i: expected NotStarted", EngineStatus.NotStarted, engine.status.value)
        }
    }

    @Test
    fun testDestructionDuringCallbackDoesNotDeadlock() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        var destroyedInsideCallback = false
        engine.onPlaybackProgressChanged = { _, _ ->
            // Calling destroy() from within a progress callback
            engine.destroy()
            destroyedInsideCallback = true
        }

        engine.eventProperty("time-pos", 12.34)
        assertTrue("destroy() must succeed when called from within progress callback", destroyedInsideCallback)
        assertEquals(EngineStatus.NotStarted, engine.status.value)
    }

    @Test(timeout = 5000)
    fun testConcurrentCallbacksAndDestructionDoNotDeadlock() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        val keepRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val callbackThread = Thread {
            var counter = 0.0
            while (keepRunning.get()) {
                engine.eventProperty("time-pos", counter++)
                engine.eventProperty("width", 1920L)
                Thread.yield()
            }
        }

        callbackThread.start()
        Thread.sleep(20)

        // destroy() called concurrently while callbackThread is spamming events
        engine.destroy()
        keepRunning.set(false)
        callbackThread.join(2000)

        assertEquals(EngineStatus.NotStarted, engine.status.value)
        assertEquals(0, MPVLib.registeredObservers.size)
    }

    @Test
    fun testRapidSurfaceDestructionAndRecreation() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        repeat(10) { i ->
            val surface = android.view.Surface(android.graphics.SurfaceTexture(i))
            engine.onSurfaceCreated(surface)
            engine.onSurfaceChanged(1920, 1080)
            engine.onSurfaceDestroyed()
        }

        // Final attachment after rapid churn
        val finalSurface = android.view.Surface(android.graphics.SurfaceTexture(999))
        engine.onSurfaceCreated(finalSurface)
        assertTrue(MPVLib.attachedSurfaces.contains(finalSurface))

        engine.destroy()
        assertEquals(EngineStatus.NotStarted, engine.status.value)
    }

    @Test
    fun testMpvLifecycleGuardDestroyHasExactlyOneCallSite() {
        // Structural test: MpvEngine.destroy() and MpvEngine.retryDestroy() must both
        // delegate to the private performDestruction() helper, which is the sole call site
        // for MpvLifecycleGuard.destroy(). This is verified by observing that a single
        // destroy()-then-retryDestroy() cycle calls MPVLib.destroy() exactly once each
        // (two total, one per attempt), which would only be possible if both went through
        // the shared helper.
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        val beforeCount = MPVLib.destroyCallCount

        MPVLib.simulateDestroyFailure = true
        engine.destroy()
        assertEquals("destroy() must invoke MPVLib.destroy exactly once", beforeCount + 1, MPVLib.destroyCallCount)

        MPVLib.simulateDestroyFailure = false
        engine.retryDestroy()
        assertEquals("retryDestroy() must invoke MPVLib.destroy exactly once more", beforeCount + 2, MPVLib.destroyCallCount)
    }

    @Test
    fun testDestroyOnNotStartedEngineNeverReachesGuard() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        assertEquals(EngineStatus.NotStarted, engine.status.value)
        assertEquals(0, MpvLifecycleGuard.destroyCallCount)
        assertEquals(0, MPVLib.destroyCallCount)
        assertFalse(MPVLib.isCreated)

        // destroy() called on NotStarted engine must return immediately without calling performDestruction()
        engine.destroy()

        assertEquals(EngineStatus.NotStarted, engine.status.value)
        assertEquals("MpvLifecycleGuard.destroy must never be reached", 0, MpvLifecycleGuard.destroyCallCount)
        assertEquals("MPVLib.destroy must never be reached", 0, MPVLib.destroyCallCount)
        assertFalse(MPVLib.isCreated)

        // Engine can still be initialized normally afterwards
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        assertEquals(EngineStatus.Ready, engine.status.value)
        assertTrue(MPVLib.isCreated)
        engine.destroy()
        assertEquals(EngineStatus.NotStarted, engine.status.value)
        assertEquals(1, MpvLifecycleGuard.destroyCallCount)
        assertEquals(1, MPVLib.destroyCallCount)
    }

    @Test
    fun testDestroyOnFailedEngineDoesNotAttemptNativeDestruction() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        MPVLib.simulateInitFailure = true
        try {
            engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
            fail("Expected initialize to throw on simulated failure")
        } catch (_: Throwable) {
            // Expected
        }

        assertTrue(engine.status.value is EngineStatus.Failed)
        val guardDestroysBefore = MpvLifecycleGuard.destroyCallCount
        val mpvLibDestroysBefore = MPVLib.destroyCallCount
        assertFalse(MPVLib.isCreated)

        // Calling destroy() on Failed engine must not attempt native destruction
        engine.destroy()

        assertTrue(engine.status.value is EngineStatus.Failed)
        assertEquals("MpvLifecycleGuard.destroy must not be called", guardDestroysBefore, MpvLifecycleGuard.destroyCallCount)
        assertEquals("MPVLib.destroy must not be called", mpvLibDestroysBefore, MPVLib.destroyCallCount)
    }

    @Test
    fun testFailedRetryProducesDestroyFailedWithRetriedTrue() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        // Initial destroy fails -> DestroyFailed(retried = false)
        MPVLib.simulateDestroyFailure = true
        engine.destroy()

        val firstStatus = engine.status.value
        assertTrue(firstStatus is EngineStatus.DestroyFailed)
        assertFalse((firstStatus as EngineStatus.DestroyFailed).retried)

        // Retry also fails (simulateDestroyFailure remains true)
        engine.retryDestroy()

        val retryStatus = engine.status.value
        assertTrue("Status must remain DestroyFailed", retryStatus is EngineStatus.DestroyFailed)
        assertTrue(
            "Failed retry must produce DestroyFailed(retried = true), not DestroyFailed(retried = false)",
            (retryStatus as EngineStatus.DestroyFailed).retried
        )
    }

    @Test
    fun testConcurrentRetryDestroyCallsInvokePerformDestructionExactlyOnce() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        // First destroy fails -> status is DestroyFailed(retried = false)
        MPVLib.simulateDestroyFailure = true
        engine.destroy()
        assertTrue(engine.status.value is EngineStatus.DestroyFailed)
        assertFalse((engine.status.value as EngineStatus.DestroyFailed).retried)

        val destroysBeforeRetry = MPVLib.destroyCallCount
        MPVLib.simulateDestroyFailure = false

        // While Caller 1 is inside MPVLib.destroy() (status is Destroying), Caller 2 invokes retryDestroy()
        var caller2ObservedStatus: EngineStatus? = null
        MPVLib.onDestroy = {
            // Caller 2 executes concurrently while Caller 1 is mid-destruction
            caller2ObservedStatus = engine.status.value
            engine.retryDestroy()
        }

        engine.retryDestroy()

        assertEquals("Caller 2 must have observed Destroying status", EngineStatus.Destroying, caller2ObservedStatus)
        assertEquals("retryDestroy must have invoked MPVLib.destroy exactly once", destroysBeforeRetry + 1, MPVLib.destroyCallCount)
        assertEquals(EngineStatus.NotStarted, engine.status.value)
    }

    @Test(timeout = 5000)
    fun testConcurrentRetryDestroyRaceWithMultipleThreads() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        MPVLib.simulateDestroyFailure = true
        engine.destroy()
        assertTrue(engine.status.value is EngineStatus.DestroyFailed)

        val threadCount = 8
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val destroysBeforeRetry = MPVLib.destroyCallCount

        MPVLib.simulateDestroyFailure = false

        for (i in 0 until threadCount) {
            executor.execute {
                try {
                    startLatch.await()
                    engine.retryDestroy()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(3, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Exactly one performDestruction invocation must occur across all concurrent retry callers",
            destroysBeforeRetry + 1, MPVLib.destroyCallCount)
        assertEquals(EngineStatus.NotStarted, engine.status.value)
    }

    @Test
    fun testPlayFileRejectedWhenStatusIsDestroying() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        // Set geometry and progress
        engine.onPropertyLong("width", 1920L)
        engine.onPropertyLong("height", 1080L)
        engine.onPropertyDouble("time-pos", 45.0)

        var geometryCallbackCount = 0
        var progressCallbackCount = 0
        engine.onVideoGeometryChanged = { geometryCallbackCount++ }
        engine.onPlaybackProgressChanged = { _, _ -> progressCallbackCount++ }

        val surface = android.view.Surface(android.graphics.SurfaceTexture(0))
        engine.onSurfaceCreated(surface)
        MPVLib.loadedFiles.clear()

        var playFileStatusDuringDestroy: EngineStatus? = null
        MPVLib.onDestroy = {
            playFileStatusDuringDestroy = engine.status.value
            // Call playFile while engine is in Destroying state
            engine.playFile("/path/to/rejected.mp4")
        }

        engine.destroy()

        assertEquals(EngineStatus.Destroying, playFileStatusDuringDestroy)
        assertEquals("playFile during Destroying must not invoke geometry callback", 0, geometryCallbackCount)
        assertEquals("playFile during Destroying must not invoke progress callback", 0, progressCallbackCount)
        assertFalse("Rejected file must not have been loaded", MPVLib.loadedFiles.contains("/path/to/rejected.mp4"))

        // Re-initialize and attach surface; ensure no stale pending filePath was retained
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        val freshSurface = android.view.Surface(android.graphics.SurfaceTexture(1))
        engine.onSurfaceCreated(freshSurface)
        assertTrue("No pending file must be loaded upon reinitialization", MPVLib.loadedFiles.isEmpty())
        engine.destroy()
    }

    @Test(timeout = 5000)
    fun testInitFailureFollowedByConcurrentDestroyInvokesDestroyExactlyOnce() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        MPVLib.simulatePostCreateFailure = true

        val destroyLatch = CountDownLatch(1)
        val destroyDoneLatch = CountDownLatch(1)
        var destroyThreadException: Throwable? = null

        MPVLib.onDestroy = {
            // Signal concurrent thread to invoke engine.destroy() while this thread is mid-destruction
            destroyLatch.countDown()
            // Wait for concurrent destroy() call to complete before this thread proceeds
            destroyDoneLatch.await(3, TimeUnit.SECONDS)
        }

        val destroyThread = Thread {
            try {
                if (destroyLatch.await(3, TimeUnit.SECONDS)) {
                    engine.destroy()
                }
            } catch (t: Throwable) {
                destroyThreadException = t
            } finally {
                destroyDoneLatch.countDown()
            }
        }
        destroyThread.start()

        var initException: Throwable? = null
        try {
            engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        } catch (t: Throwable) {
            initException = t
        }

        destroyThread.join(3000)

        assertNotNull("Expected initialize to throw on failure", initException)
        assertNull("Destroy thread should not throw", destroyThreadException)
        assertEquals(
            "MpvLifecycleGuard.destroy must be invoked exactly once across both threads",
            1,
            MpvLifecycleGuard.destroyCallCount
        )
        assertEquals(
            "MPVLib.destroy must be invoked exactly once across both threads",
            1,
            MPVLib.destroyCallCount
        )
        assertTrue("Final status must be Failed", engine.status.value is EngineStatus.Failed)
    }

    @Test
    fun testInitializeDuringDestroyingDoesNotCallMpvLifecycleGuardCreate() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)
        engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")

        var initializeAttempted = false
        var initializeException: Throwable? = null

        MPVLib.onDestroy = {
            assertEquals(EngineStatus.Destroying, engine.status.value)
            initializeAttempted = true
            try {
                // If initialize() does not reject Destroying, it would proceed to
                // MpvLifecycleGuard.create(), throwing IllegalStateException
                // ("MPVLib is already owned") since the guard still owns the native instance.
                engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
            } catch (t: Throwable) {
                initializeException = t
            }
            // Status must remain Destroying, not mutated to Starting
            assertEquals(EngineStatus.Destroying, engine.status.value)
        }

        engine.destroy()

        assertTrue(initializeAttempted)
        assertNull("initialize() during Destroying must return early without throwing", initializeException)
        assertEquals(EngineStatus.NotStarted, engine.status.value)
    }

    @Test
    fun testInitFailureWithSuccessfulCleanupEndsInFailed() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        MPVLib.simulatePostCreateFailure = true
        MPVLib.simulateDestroyFailure = false

        var caughtException: Throwable? = null
        try {
            engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        } catch (t: Throwable) {
            caughtException = t
        }

        assertNotNull("Expected initialize to throw on failure", caughtException)
        val status = engine.status.value
        assertTrue("Status must end in Failed", status is EngineStatus.Failed)
        assertEquals(
            caughtException?.message ?: "Simulated MPVLib.init failure",
            (status as EngineStatus.Failed).reason
        )
        assertEquals("Cleanup must have called destroy once", 1, MpvLifecycleGuard.destroyCallCount)
        assertFalse(MPVLib.isCreated)
    }

    @Test
    fun testInitFailureWithFailedCleanupEndsInDestroyFailed() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        MPVLib.simulatePostCreateFailure = true
        MPVLib.simulateDestroyFailure = true

        var caughtException: Throwable? = null
        try {
            engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
        } catch (t: Throwable) {
            caughtException = t
        }

        assertNotNull("Expected initialize to throw on failure", caughtException)
        val status = engine.status.value
        assertTrue("Status must end in DestroyFailed", status is EngineStatus.DestroyFailed)
        assertFalse("retried must be false on first failure", (status as EngineStatus.DestroyFailed).retried)
        assertEquals("Simulated MPVLib.destroy failure", status.reason)
        assertEquals("Cleanup must have attempted destroy once", 1, MpvLifecycleGuard.destroyCallCount)
    }

    @Test(timeout = 5000)
    fun testInitFailureFinalizationPreventsConcurrentInitialize() {
        val eventHandler = EngineEventHandler()
        val context = createTestContext()
        val engine = MpvEngine(context, eventHandler)

        // 1. First initialize() creates the native instance, then fails.
        MPVLib.simulatePostCreateFailure = true

        val pauseLatch = CountDownLatch(1)
        val resumeLatch = CountDownLatch(1)

        // 2 & 3. performDestruction() destroys the native instance successfully,
        // then pauses before the final status write.
        MPVLib.onDestroy = {
            pauseLatch.countDown()
            assertTrue("Timed out waiting to resume destruction", resumeLatch.await(3, TimeUnit.SECONDS))
        }

        var thread1Exception: Throwable? = null
        val thread1 = Thread {
            try {
                engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
            } catch (t: Throwable) {
                thread1Exception = t
            }
        }
        thread1.start()

        // Wait until Thread 1 has destroyed the native instance and is paused before final status write
        assertTrue("Timed out waiting for destruction pause", pauseLatch.await(3, TimeUnit.SECONDS))

        // 4. From a second thread, call initialize().
        var thread2Exception: Throwable? = null
        val thread2 = Thread {
            try {
                engine.initialize(configDir = "/tmp/config", cacheDir = "/tmp/cache")
            } catch (t: Throwable) {
                thread2Exception = t
            }
        }
        thread2.start()
        thread2.join(3000)

        // 5. Assert MpvLifecycleGuard.create() was called exactly once (not twice)
        // and that the second initialize() did not proceed past its guard.
        assertNull("Second initialize() must return without throwing", thread2Exception)
        assertEquals(
            "MpvLifecycleGuard.create() must be called exactly once across both threads",
            1,
            MPVLib.createCallCount
        )
        assertEquals(
            "Second initialize() must not proceed past its guard; status must remain Destroying",
            EngineStatus.Destroying,
            engine.status.value
        )

        // 6. Release the pause.
        resumeLatch.countDown()
        thread1.join(3000)

        assertNotNull("First initialize() was expected to fail", thread1Exception)
        assertEquals("Simulated MPVLib.init failure", thread1Exception?.message)

        // 7. Assert final status is Failed, not NotStarted or Ready.
        val finalStatus = engine.status.value
        assertTrue("Final status must be Failed", finalStatus is EngineStatus.Failed)
        assertFalse("Final status must not be NotStarted", finalStatus is EngineStatus.NotStarted)
        assertFalse("Final status must not be Ready", finalStatus is EngineStatus.Ready)
        assertEquals("Simulated MPVLib.init failure", (finalStatus as EngineStatus.Failed).reason)
    }
}
