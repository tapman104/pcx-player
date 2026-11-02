package `is`.xyz.mpv

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineEventHandlerTest {

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

    @Test
    fun testMultitrackParsingAndEventHandling() {
        // Simulates mpv's "track-list" property for test_multitrack.mkv:
        // Stream #0:0 - Video (id: 1)
        // Stream #0:1 - Audio (id: 2, eng, "English Stereo", selected=true)
        // Stream #0:2 - Audio (id: 3, jpn, "Japanese Commentary", selected=false)
        // Stream #0:3 - Subtitle (id: 4, eng, "English Full", selected=true)
        // Stream #0:4 - Subtitle (id: 5, spa, "Spanish Subtitles", selected=false)
        val trackListNode = MPVNode.ArrayNode(
            arrayOf(
                createTrackMap(id = 1, type = "video", title = "H.264 Video", selected = true),
                createTrackMap(id = 2, type = "audio", lang = "eng", title = "English Stereo", selected = true),
                createTrackMap(id = 3, type = "audio", lang = "jpn", title = "Japanese Commentary", selected = false),
                createTrackMap(id = 4, type = "sub", lang = "eng", title = "English Full", selected = true),
                createTrackMap(id = 5, type = "sub", lang = "spa", title = "Spanish Subtitles", selected = false),
            )
        )

        val handler = EngineEventHandler()

        // 1. Send track-list node event
        handler.eventProperty("track-list", trackListNode)
        val state1 = handler.trackState.value

        println("=== TEST LOG: Initial Parsed TrackState ===")
        println("Audio Tracks (${state1.audioTracks.size}):")
        state1.audioTracks.forEach { println("  -> $it") }
        println("Subtitle Tracks (${state1.subtitleTracks.size}):")
        state1.subtitleTracks.forEach { println("  -> $it") }
        println("Selected Audio ID: ${state1.selectedAudioId}")
        println("Selected Subtitle ID: ${state1.selectedSubtitleId}")

        // Assertions for initial parse
        assertEquals(2, state1.audioTracks.size)
        assertEquals(2, state1.subtitleTracks.size)
        assertEquals(2, state1.selectedAudioId)
        assertEquals(4, state1.selectedSubtitleId)

        assertEquals(2, state1.audioTracks[0].id)
        assertEquals(TrackType.Audio, state1.audioTracks[0].type)
        assertEquals("eng", state1.audioTracks[0].lang)
        assertEquals("English Stereo", state1.audioTracks[0].title)
        assertTrue(state1.audioTracks[0].isSelected)

        assertEquals(3, state1.audioTracks[1].id)
        assertEquals(TrackType.Audio, state1.audioTracks[1].type)
        assertEquals("jpn", state1.audioTracks[1].lang)
        assertEquals("Japanese Commentary", state1.audioTracks[1].title)
        assertFalse(state1.audioTracks[1].isSelected)

        assertEquals(4, state1.subtitleTracks[0].id)
        assertEquals(TrackType.Subtitle, state1.subtitleTracks[0].type)
        assertEquals("eng", state1.subtitleTracks[0].lang)
        assertEquals("English Full", state1.subtitleTracks[0].title)
        assertTrue(state1.subtitleTracks[0].isSelected)

        assertEquals(5, state1.subtitleTracks[1].id)
        assertEquals(TrackType.Subtitle, state1.subtitleTracks[1].type)
        assertEquals("spa", state1.subtitleTracks[1].lang)
        assertEquals("Spanish Subtitles", state1.subtitleTracks[1].title)
        assertFalse(state1.subtitleTracks[1].isSelected)

        // 2. Select audio track 3 via aid
        handler.eventProperty("aid", "3")
        val state2 = handler.trackState.value
        println("\n=== TEST LOG: After selecting audio track 3 (aid='3') ===")
        println("Selected Audio ID: ${state2.selectedAudioId}")
        state2.audioTracks.forEach { println("  -> $it") }
        assertEquals(3, state2.selectedAudioId)
        assertFalse(state2.audioTracks[0].isSelected)
        assertTrue(state2.audioTracks[1].isSelected)

        // 3. Turn subtitles off via sid="no"
        handler.eventProperty("sid", "no")
        val state3 = handler.trackState.value
        println("\n=== TEST LOG: After disabling subtitles (sid='no') ===")
        println("Selected Subtitle ID: ${state3.selectedSubtitleId}")
        state3.subtitleTracks.forEach { println("  -> $it") }
        assertNull(state3.selectedSubtitleId)
        assertFalse(state3.subtitleTracks[0].isSelected)
        assertFalse(state3.subtitleTracks[1].isSelected)

        // 4. Select subtitle track 5 via sid="5"
        handler.eventProperty("sid", "5")
        val state4 = handler.trackState.value
        println("\n=== TEST LOG: After selecting subtitle track 5 (sid='5') ===")
        println("Selected Subtitle ID: ${state4.selectedSubtitleId}")
        state4.subtitleTracks.forEach { println("  -> $it") }
        assertEquals(5, state4.selectedSubtitleId)
        assertFalse(state4.subtitleTracks[0].isSelected)
        assertTrue(state4.subtitleTracks[1].isSelected)
    }

    @Test
    fun testPauseAndMediaTitleEventsUpdateHandlerState() {
        val handler = EngineEventHandler()

        // Initially null before any events arrive
        assertNull(handler.trackState.value.isPaused)
        assertNull(handler.trackState.value.mediaTitle)

        // 1. Pause event: true
        handler.eventProperty("pause", true)
        assertEquals(true, handler.trackState.value.isPaused)

        // 2. Pause event: false (playing)
        handler.eventProperty("pause", false)
        assertEquals(false, handler.trackState.value.isPaused)

        // 3. Media-title event
        handler.eventProperty("media-title", "Big Buck Bunny 1080p")
        assertEquals("Big Buck Bunny 1080p", handler.trackState.value.mediaTitle)

        // 4. Reset clears isPaused and mediaTitle
        handler.reset()
        assertNull(handler.trackState.value.isPaused)
        assertNull(handler.trackState.value.mediaTitle)
        assertTrue(handler.trackState.value.audioTracks.isEmpty())
        assertTrue(handler.trackState.value.subtitleTracks.isEmpty())
    }

    @Test
    fun testPlayFileResetsTrackState() {
        val eventHandler = EngineEventHandler()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        val engine = MpvEngine(context, eventHandler)

        // Set non-default track and pause state
        eventHandler.eventProperty("aid", "2")
        eventHandler.eventProperty("sid", "5")
        eventHandler.eventProperty("media-title", "Old File Movie")
        eventHandler.eventProperty("pause", true)

        val beforeState = eventHandler.trackState.value
        assertEquals(2, beforeState.selectedAudioId)
        assertEquals(5, beforeState.selectedSubtitleId)
        assertEquals("Old File Movie", beforeState.mediaTitle)
        assertEquals(true, beforeState.isPaused)

        // Calling playFile on engine must reset eventHandler trackState to defaults
        engine.playFile("/path/to/new_video.mp4")

        val afterState = eventHandler.trackState.value
        assertTrue(afterState.audioTracks.isEmpty())
        assertTrue(afterState.subtitleTracks.isEmpty())
        assertNull(afterState.selectedAudioId)
        assertNull(afterState.selectedSubtitleId)
        assertNull(afterState.mediaTitle)
        assertNull(afterState.isPaused)
    }

    @Test
    fun testEmptyAndBlankTracks() {
        val emptyNode = MPVNode.ArrayNode(emptyArray())
        val tracks = EngineEventHandler.parseTrackList(emptyNode)
        assertTrue(tracks.isEmpty())

        val blankStringsTrack = MPVNode.ArrayNode(
            arrayOf(
                createTrackMap(id = 10, type = "audio", lang = "", title = "   ", selected = false)
            )
        )
        val parsed = EngineEventHandler.parseTrackList(blankStringsTrack)
        assertEquals(1, parsed.size)
        assertNull(parsed[0].lang)
        assertNull(parsed[0].title)
    }

    @Test
    fun testGeometryAndProgressStateFlowsAndPropertyUpdates() {
        val handler = EngineEventHandler()

        var callbackGeometry: VideoGeometry? = null
        var callbackPos: Double? = null
        var callbackDur: Double? = null

        handler.onVideoGeometryChanged = { callbackGeometry = it }
        handler.onPlaybackProgressChanged = { pos, dur ->
            callbackPos = pos
            callbackDur = dur
        }

        // Test geometry Long property updates
        assertTrue(handler.onPropertyLong("width", 1920L))
        assertEquals(1920, handler.currentGeometry.width)
        assertEquals(1920, handler.videoGeometry.value.width)
        assertEquals(1920, handler.geometry.value.width)
        assertEquals(1920, callbackGeometry?.width)

        assertTrue(handler.onPropertyLong("height", 1080L))
        assertEquals(1080, handler.currentGeometry.height)
        assertEquals(1080, handler.videoGeometry.value.height)
        assertEquals(1080, callbackGeometry?.height)

        assertTrue(handler.onPropertyLong("video-rotate", 180L))
        assertEquals(180, handler.currentGeometry.rotationDegrees)
        assertEquals(180, handler.videoGeometry.value.rotationDegrees)
        assertEquals(180, callbackGeometry?.rotationDegrees)

        // Unknown Long property is not consumed
        assertFalse(handler.onPropertyLong("unknown-prop", 42L))

        // Test progress Double property updates
        assertTrue(handler.onPropertyDouble("time-pos", 12.5))
        assertEquals(12.5, handler.currentPositionSec, 0.001)
        assertEquals(12.5, handler.playbackProgress.value.positionSec, 0.001)
        assertEquals(12.5, handler.progress.value.positionSec, 0.001)
        assertEquals(12.5, callbackPos ?: 0.0, 0.001)

        assertTrue(handler.onPropertyDouble("duration", 300.0))
        assertEquals(300.0, handler.currentDurationSec, 0.001)
        assertEquals(300.0, handler.playbackProgress.value.durationSec, 0.001)
        assertEquals(300.0, handler.progress.value.durationSec, 0.001)
        assertEquals(300.0, callbackDur ?: 0.0, 0.001)

        // Unknown Double property is not consumed
        assertFalse(handler.onPropertyDouble("unknown-prop", 3.14))
    }

    @Test
    fun testClearGeometryClearProgressAndReset() {
        val handler = EngineEventHandler()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        val engine = MpvEngine(context, handler)

        // Set non-default geometry and progress
        handler.onPropertyLong("width", 1280L)
        handler.onPropertyLong("height", 720L)
        handler.onPropertyDouble("time-pos", 50.0)
        handler.onPropertyDouble("duration", 100.0)

        assertEquals(1280, handler.geometry.value.width)
        assertEquals(50.0, handler.progress.value.positionSec, 0.001)

        // 1. clearGeometry()
        handler.clearGeometry()
        assertEquals(VideoGeometry(), handler.geometry.value)
        assertEquals(VideoGeometry(), handler.currentGeometry)

        // 2. clearProgress()
        handler.clearProgress()
        assertEquals(PlaybackProgress(0.0, 0.0), handler.progress.value)
        assertEquals(0.0, handler.currentPositionSec, 0.001)
        assertEquals(0.0, handler.currentDurationSec, 0.001)

        // 3. Set state again and test engine.playFile() calls reset() which clears all
        handler.onPropertyLong("width", 1920L)
        handler.onPropertyDouble("time-pos", 75.0)
        handler.eventProperty("aid", "1")

        engine.playFile("/path/to/video.mp4")

        assertEquals(VideoGeometry(), handler.geometry.value)
        assertEquals(PlaybackProgress(0.0, 0.0), handler.progress.value)
        assertNull(handler.trackState.value.selectedAudioId)
    }
}
