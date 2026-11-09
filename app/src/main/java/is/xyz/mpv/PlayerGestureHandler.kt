package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import `is`.xyz.mpv.ui.SeekSource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * UI-only gesture handling for the video surface.
 *
 * This file detects gestures and handles Android window brightness / media volume.
 * Playback operations are supplied by PlayScreen through callbacks. No gesture
 * state or gesture-specific API is added to PlayerViewModel.
 *
 * Gesture mapping:
 * - Double tap left/right: seek backward/forward.
 * - Long press: temporarily request 2x playback speed; restore to 1x on release.
 * - Vertical drag on left half: screen brightness.
 * - Vertical drag on right half: media volume.
 * - Horizontal drag: relative seek.
 * - Pinch: report incremental zoom factor to the caller.
 *
 * Attach this modifier to the video-area container, not to the entire screen
 * containing buttons, sliders, or dialogs.
 */
@Composable
fun Modifier.playerGestureHandler(
    onSeekBy: (delta: Double, source: SeekSource) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onZoomBy: (Float) -> Unit,
    onSingleTap: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    onSeekGestureStart: (() -> Unit)? = null,
    seekSecondsPerFullWidth: Float = 150f,
    getCurrentSpeed: (() -> Float)? = null,
    onVolumeChange: ((level: Int, max: Int) -> Unit)? = null,
    onBrightnessChange: ((level: Float) -> Unit)? = null,
    isLongPressActive: MutableState<Boolean>? = null,
    isGestureLocked: Boolean = false,
): Modifier {
    val context = LocalContext.current
    val activity = context.findActivity()
    val window = activity?.window
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val currentOnSeekBy = rememberUpdatedState(onSeekBy)
    val currentOnSpeedChange = rememberUpdatedState(onSpeedChange)
    val currentOnZoomBy = rememberUpdatedState(onZoomBy)
    val currentOnSingleTap = rememberUpdatedState(onSingleTap)
    val currentOnDoubleTap = rememberUpdatedState(onDoubleTap)
    val currentOnSeekGestureStart = rememberUpdatedState(onSeekGestureStart)
    val currentGetCurrentSpeed = rememberUpdatedState(getCurrentSpeed)
    val currentOnVolumeChange = rememberUpdatedState(onVolumeChange)
    val currentOnBrightnessChange = rememberUpdatedState(onBrightnessChange)
    val currentIsLongPressActive = rememberUpdatedState(isLongPressActive)
    val currentIsGestureLocked = rememberUpdatedState(isGestureLocked)

    val flags = remember { GestureFlags() }

    return this
        // Tap/long-press and swipe/pinch are in separate pointerInput modifiers so that
        // detectTapGestures can own its tap disambiguation timing independently of the
        // awaitEachGesture loop. Both modifiers require unconsumed down events so that controls
        // drawn on top (such as the seekbar Slider) take precedence and are not hijacked.
        .pointerInput(Unit) {
            var speedBeforeLongPress = 1f

            detectTapGestures(
                onTap = {
                    currentOnSingleTap.value?.invoke()
                },
                onDoubleTap = { position ->
                    if (!currentIsGestureLocked.value) {
                        when {
                            position.x <= size.width * 0.28f -> currentOnSeekBy.value(-10.0, SeekSource.DOUBLE_TAP)
                            position.x >= size.width * 0.72f -> currentOnSeekBy.value(10.0, SeekSource.DOUBLE_TAP)
                            else -> currentOnDoubleTap.value?.invoke()
                        }
                    }
                },
                onLongPress = {
                    if (currentIsGestureLocked.value) return@detectTapGestures
                    if (flags.gestureClaimed) return@detectTapGestures
                    flags.longPressActive = true
                    currentIsLongPressActive.value?.value = true
                    currentOnSpeedChange.value(2f)
                },
                onPress = {
                    try {
                        flags.longPressActive = false
                        currentIsLongPressActive.value?.value = false
                        speedBeforeLongPress = currentGetCurrentSpeed.value?.invoke() ?: 1f
                        tryAwaitRelease()
                    } finally {
                        if (flags.longPressActive) {
                            flags.longPressActive = false
                            currentIsLongPressActive.value?.value = false
                            currentOnSpeedChange.value(speedBeforeLongPress)
                        }
                    }
                }
            )
        }
        .pointerInput(window, audioManager, seekSecondsPerFullWidth) {
            awaitEachGesture {
                val edgePx = with(density) { 24.dp.toPx() }
                val seekThresholdPx = with(density) { 8.dp.toPx() }
                var mode = GestureMode.UNDECIDED
                var totalPanX = 0f
                var totalPanY = 0f
                var lastAppliedPanX = 0f
                var lastAppliedPanY = 0f
                var committedPanY = 0f
                var startVolume = 0
                var maxVolume = 1
                var startBrightness = 0.5f

                val firstDownPosition = awaitFirstDown(requireUnconsumed = true).position
                flags.gestureClaimed = false

                do {
                    val event = awaitPointerEvent()
                    val pressedCount = event.changes.count { it.pressed }

                    if (pressedCount == 0) break
                    if (flags.longPressActive) continue

                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()

                    // A two-finger gesture takes priority over one-finger movement.
                    if (pressedCount >= 2) {
                        mode = GestureMode.PINCH
                        if (zoom.isFinite() && zoom > 0f && abs(zoom - 1f) > 0.002f) {
                            currentOnZoomBy.value(zoom)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                        continue
                    }

                    // Once a pinch has started, don't reinterpret the remaining
                    // finger as a swipe in the same gesture.
                    if (mode == GestureMode.PINCH || mode == GestureMode.SUPPRESSED) {
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                        continue
                    }

                    totalPanX += pan.x
                    totalPanY += pan.y

                    if (mode == GestureMode.UNDECIDED) {
                        val touchSlop = viewConfiguration.touchSlop
                        if (abs(totalPanX) < touchSlop && abs(totalPanY) < touchSlop) {
                            continue
                        }

                        mode = if (abs(totalPanX) > abs(totalPanY)) {
                            currentOnSeekGestureStart.value?.invoke()
                            GestureMode.SEEK
                        } else if (firstDownPosition.x < size.width / 2f) {
                            GestureMode.BRIGHTNESS
                        } else {
                            GestureMode.VOLUME
                        }

                        flags.gestureClaimed = true
                        committedPanY = totalPanY

                        if (mode == GestureMode.SEEK) {
                            val swipingRight = totalPanX > 0f
                            val swipingLeft = totalPanX < 0f
                            val nearLeftEdge = firstDownPosition.x <= edgePx
                            val nearRightEdge = firstDownPosition.x >= size.width - edgePx
                            if ((nearLeftEdge && swipingRight) || (nearRightEdge && swipingLeft)) {
                                mode = GestureMode.SUPPRESSED
                            }
                        } else if (mode == GestureMode.BRIGHTNESS || mode == GestureMode.VOLUME) {
                            val swipingDown = totalPanY > 0f
                            val nearTopEdge = firstDownPosition.y <= edgePx
                            if (nearTopEdge && swipingDown) {
                                mode = GestureMode.SUPPRESSED
                            }
                        }

                        if (mode == GestureMode.VOLUME) {
                            startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                .coerceAtLeast(1)
                        } else if (mode == GestureMode.BRIGHTNESS) {
                            startBrightness = window?.attributes?.screenBrightness
                                ?.takeIf { it >= 0f }
                                ?: currentSystemBrightness(context)
                        }
                    }

                    if (!currentIsGestureLocked.value) {
                        when (mode) {
                            GestureMode.SEEK -> {
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val deltaX = totalPanX - lastAppliedPanX
                                if (abs(deltaX) >= seekThresholdPx) {
                                    currentOnSeekBy.value(
                                        (deltaX / width * seekSecondsPerFullWidth).toDouble(),
                                        SeekSource.SWIPE
                                    )
                                    lastAppliedPanX = totalPanX
                                }
                            }

                            GestureMode.VOLUME -> {
                                val deltaY = totalPanY - lastAppliedPanY
                                if (abs(deltaY) >= seekThresholdPx) {
                                    val height = size.height.toFloat().coerceAtLeast(1f)
                                    val change = (-(totalPanY - committedPanY) / height * maxVolume * 1.5f).roundToInt()
                                    val target = (startVolume + change).coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                                    currentOnVolumeChange.value?.invoke(target, maxVolume)
                                    lastAppliedPanY = totalPanY
                                }
                            }

                            GestureMode.BRIGHTNESS -> {
                                val deltaY = totalPanY - lastAppliedPanY
                                if (abs(deltaY) >= seekThresholdPx) {
                                    val height = size.height.toFloat().coerceAtLeast(1f)
                                    val change = -(totalPanY - committedPanY) / height * 1.5f
                                    val target = (startBrightness + change).coerceIn(0f, 1f)
                                    window?.updateBrightness(target)
                                    currentOnBrightnessChange.value?.invoke(target)
                                    lastAppliedPanY = totalPanY
                                }
                            }

                            GestureMode.UNDECIDED,
                            GestureMode.PINCH,
                            GestureMode.SUPPRESSED -> Unit
                        }
                    }

                    if (mode != GestureMode.UNDECIDED) {
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })

                if (mode == GestureMode.SEEK && !currentIsGestureLocked.value) {
                    val deltaX = totalPanX - lastAppliedPanX
                    if (abs(deltaX) > 0.001f) {
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        currentOnSeekBy.value(
                            (deltaX / width * seekSecondsPerFullWidth).toDouble(),
                            SeekSource.SWIPE
                        )
                        lastAppliedPanX = totalPanX
                    }
                }
            }
        }
}

private class GestureFlags {
    var longPressActive = false
    var gestureClaimed = false
}

private enum class GestureMode {
    UNDECIDED,
    SEEK,
    VOLUME,
    BRIGHTNESS,
    PINCH,
    SUPPRESSED,
}

private fun currentSystemBrightness(context: Context): Float {
    return try {
        val raw = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS
        )
        (raw / 255f).coerceIn(0f, 1f)
    } catch (_: Exception) {
        0.5f
    }
}

private fun Window.updateBrightness(value: Float) {
    val params = attributes
    params.screenBrightness = value.coerceIn(0f, 1f)
    attributes = params
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}