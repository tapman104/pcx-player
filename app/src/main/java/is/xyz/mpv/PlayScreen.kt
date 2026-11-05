package `is`.xyz.mpv

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import `is`.xyz.mpv.ui.AudioTrackSheet
import `is`.xyz.mpv.ui.GestureIndicator
import `is`.xyz.mpv.ui.GestureIndicatorOverlay
import `is`.xyz.mpv.ui.PlayerBottomBar
import `is`.xyz.mpv.ui.PlayerTopBar
import `is`.xyz.mpv.ui.SeekSource
import `is`.xyz.mpv.ui.SubtitleTrackSheet
import `is`.xyz.mpv.ui.VideoSurface
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class TrackDialogType {
    NONE,
    AUDIO,
    SUBTITLE
}

@Composable
fun PlayScreen(
    onOpenFileClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    onControlsVisibilityChanged: ((Boolean) -> Unit)? = null,
    viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.factory(
            LocalContext.current
        )
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val orientationState by viewModel.orientationState.collectAsState()
    val currentZoom by viewModel.currentZoom.collectAsState()
    val subFontSize by viewModel.subFontSize.collectAsState()
    val subPos by viewModel.subPos.collectAsState()
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    var controlsVisible by remember { mutableStateOf(true) }
    var activeDialog by remember { mutableStateOf(TrackDialogType.NONE) }
    var indicator by remember { mutableStateOf<GestureIndicator?>(null) }
    var indicatorRevision by remember { mutableStateOf(0) }
    val isLongPressActive = remember { mutableStateOf(false) }
    var doubleTapAccumulatedDelta by remember { mutableStateOf(0.0) }
    var seekStartSec by remember { mutableStateOf(0.0) }
    var seekAccumulatedDelta by remember { mutableStateOf(0.0) }
    val coroutineScope = rememberCoroutineScope()
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var topBarHeight by remember { mutableStateOf(0.dp) }
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    fun resetHideTimer() {
        hideJob?.cancel()
        hideJob = coroutineScope.launch {
            delay(5_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(indicatorRevision, isLongPressActive.value) {
        if (indicator != null) {
            hideJob?.cancel()
            controlsVisible = false
            if (isLongPressActive.value) {
                return@LaunchedEffect
            }
            val timeoutMs = if (indicator is GestureIndicator.Seek && (indicator as GestureIndicator.Seek).source == SeekSource.DOUBLE_TAP) {
                700L
            } else {
                600L
            }
            delay(timeoutMs)
            if (!isLongPressActive.value) {
                indicator = null
                doubleTapAccumulatedDelta = 0.0
            }
        }
    }

    LaunchedEffect(controlsVisible) {
        onControlsVisibilityChanged?.invoke(controlsVisible)
        if (!controlsVisible) {
            topBarHeight = 0.dp
            bottomBarHeight = 0.dp
        }
    }

    LaunchedEffect(Unit) {
        resetHideTimer()
    }

    LaunchedEffect(activeDialog) {
        if (activeDialog != TrackDialogType.NONE) {
            hideJob?.cancel()
        }
    }

    BackHandler(enabled = activeDialog != TrackDialogType.NONE) {
        activeDialog = TrackDialogType.NONE
        resetHideTimer()
    }

    val handleBack: () -> Unit = onBackClick ?: {
        if (backDispatcher?.hasEnabledCallbacks() == true) {
            backDispatcher.onBackPressed()
        } else {
            (context as? Activity)?.finish()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Base layer: Fullscreen video surface
        VideoSurface(
            engine = viewModel.engine,
            modifier = Modifier.fillMaxSize()
        )

        // Mid layer: unified gesture surface
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarHeight, bottom = bottomBarHeight)
                .playerGestureHandler(
                    isLongPressActive = isLongPressActive,
                    onSingleTap = {
                        if (activeDialog == TrackDialogType.NONE) {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) resetHideTimer()
                        }
                    },
                    onSeekGestureStart = {
                        doubleTapAccumulatedDelta = 0.0
                        seekStartSec = viewModel.playbackProgress.value.positionSec
                        seekAccumulatedDelta = 0.0
                    },
                    onSeekBy = { delta, source ->
                        when (source) {
                            SeekSource.DOUBLE_TAP -> {
                                val isCurrentDoubleTap = indicator is GestureIndicator.Seek &&
                                    (indicator as GestureIndicator.Seek).source == SeekSource.DOUBLE_TAP
                                val isSameDirection = isCurrentDoubleTap && (
                                    (delta > 0 && doubleTapAccumulatedDelta > 0) ||
                                    (delta < 0 && doubleTapAccumulatedDelta < 0)
                                )

                                if (isSameDirection) {
                                    doubleTapAccumulatedDelta += delta
                                } else {
                                    doubleTapAccumulatedDelta = delta
                                }

                                val currentPos = viewModel.playbackProgress.value.positionSec
                                val target = (currentPos + delta).coerceIn(0.0, viewModel.playbackProgress.value.durationSec)
                                indicator = GestureIndicator.Seek(
                                    deltaSec = doubleTapAccumulatedDelta,
                                    targetPositionSec = target,
                                    source = SeekSource.DOUBLE_TAP
                                )
                                indicatorRevision++
                                viewModel.seekBy(delta)
                            }
                            SeekSource.SWIPE -> {
                                doubleTapAccumulatedDelta = 0.0
                                seekAccumulatedDelta += delta
                                val target = (seekStartSec + seekAccumulatedDelta).coerceIn(0.0, viewModel.playbackProgress.value.durationSec)
                                indicator = GestureIndicator.Seek(
                                    deltaSec = target - seekStartSec,
                                    targetPositionSec = target,
                                    source = SeekSource.SWIPE
                                )
                                indicatorRevision++
                                viewModel.seekBy(delta)
                            }
                        }
                    },
                    onSpeedChange = {
                        doubleTapAccumulatedDelta = 0.0
                        viewModel.setSpeed(it.toDouble())
                        if (isLongPressActive.value) {
                            indicator = GestureIndicator.Speed(it)
                            indicatorRevision++
                        }
                    },
                    onZoomBy = {
                        doubleTapAccumulatedDelta = 0.0
                        viewModel.zoomBy(it)
                        indicator = GestureIndicator.Zoom(viewModel.currentZoom.value)
                        indicatorRevision++
                    },
                    onDoubleTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) resetHideTimer()
                    },
                    getCurrentSpeed = { viewModel.getCurrentSpeed() },
                    onVolumeChange = { level, max ->
                        doubleTapAccumulatedDelta = 0.0
                        indicator = GestureIndicator.Volume(level, max)
                        indicatorRevision++
                    },
                    onBrightnessChange = { level ->
                        doubleTapAccumulatedDelta = 0.0
                        indicator = GestureIndicator.Brightness(level)
                        indicatorRevision++
                    },
                )
        )

        // Overlay: Center play/pause button
        Box(
            modifier = Modifier.align(Alignment.Center)
        ) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                IconButton(
                    onClick = {
                        resetHideTimer()
                        viewModel.togglePlayPause()
                    },
                    enabled = uiState.isEngineReady && uiState.isPlaying != null,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                color = Color.Black.copy(alpha = 0.4f),
                                shape = CircleShape
                            )
                    ) {
                        when (uiState.isPlaying) {
                            true -> Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = "Pause",
                                tint = Color.White,
                                modifier = Modifier.size(52.dp)
                            )
                            false -> Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(52.dp)
                            )
                            null -> CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = Color.White,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                }
            }
        }

        // Overlay: Top app bar with back navigation and title
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .onGloballyPositioned {
                    if (controlsVisible) {
                        topBarHeight = with(density) { it.size.height.toDp() }
                    }
                }
        ) {
            PlayerTopBar(
                title = uiState.currentFileName,
                isEngineReady = uiState.isEngineReady,
                onBackClick = {
                    resetHideTimer()
                    handleBack()
                },
                onOpenFileClick = onOpenFileClick?.let { onClick ->
                    {
                        resetHideTimer()
                        onClick()
                    }
                },
                onAudioTrackClick = {
                    hideJob?.cancel()
                    activeDialog = TrackDialogType.AUDIO
                },
                onSubtitleTrackClick = {
                    hideJob?.cancel()
                    activeDialog = TrackDialogType.SUBTITLE
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Overlay: Bottom app bar with seek bar
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .onGloballyPositioned {
                    if (controlsVisible) {
                        bottomBarHeight = with(density) { it.size.height.toDp() }
                    }
                }
        ) {
            PlayerBottomBarHost(
                playbackProgressFlow = viewModel.playbackProgress,
                isEnabled = uiState.isEngineReady,
                onSeek = { pos ->
                    viewModel.seekTo(pos)
                    controlsVisible = true
                    resetHideTimer()
                },
                onSeekStarted = {
                    hideJob?.cancel()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Overlay: Gesture indicators
        // Calculate the actual rendered video content rectangle to account for letterboxing / pillarboxing.
        // We obtain the video's intrinsic dimensions and rotation from viewModel.orientationState
        // (sourced directly from mpv's reported width, height, and video-rotate properties)
        // and container dimensions from BoxWithConstraints (maxWidth, maxHeight).
        val rotation = ((orientationState.videoRotationDegrees % 360) + 360) % 360
        val isSwapped = rotation == 90 || rotation == 270
        val effectiveVideoWidth = if (isSwapped) orientationState.videoHeight else orientationState.videoWidth
        val effectiveVideoHeight = if (isSwapped) orientationState.videoWidth else orientationState.videoHeight

        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val (visibleVideoWidth, pillarboxPadding) = if (effectiveVideoWidth > 0 && effectiveVideoHeight > 0 && containerHeight.value > 0f) {
            val videoAspect = effectiveVideoWidth.toFloat() / effectiveVideoHeight.toFloat()
            val containerAspect = containerWidth.value / containerHeight.value
            if (containerAspect > videoAspect) {
                // Container is wider than the video: pillarboxed with black bars on left and right.
                val visibleW = containerHeight * videoAspect
                val pillarbox = (containerWidth - visibleW) / 2f
                visibleW to pillarbox
            } else {
                // Container is taller than (or equal to) the video: letterboxed with black bars on top and bottom.
                // Horizontally, the video spans the full container width.
                containerWidth to 0.dp
            }
        } else {
            containerWidth to 0.dp
        }
        val doubleTapSidePadding = pillarboxPadding + (visibleVideoWidth * 0.11f)

        val (indicatorAlignment, indicatorPadding) = when (val ind = indicator) {
            is GestureIndicator.Brightness -> Alignment.CenterEnd to PaddingValues(end = 24.dp)
            is GestureIndicator.Volume -> Alignment.CenterStart to PaddingValues(start = 24.dp)
            is GestureIndicator.Seek -> {
                if (ind.source == SeekSource.DOUBLE_TAP) {
                    if (ind.deltaSec < 0) {
                        Alignment.CenterStart to PaddingValues(start = doubleTapSidePadding)
                    } else {
                        Alignment.CenterEnd to PaddingValues(end = doubleTapSidePadding)
                    }
                } else {
                    Alignment.Center to PaddingValues(0.dp)
                }
            }
            else -> Alignment.Center to PaddingValues(0.dp)
        }
        GestureIndicatorOverlay(
            indicator = indicator,
            modifier = Modifier.align(indicatorAlignment).padding(indicatorPadding)
        )
        AudioTrackSheet(
            visible = activeDialog == TrackDialogType.AUDIO,
            tracks = uiState.trackState.audioTracks,
            selectedTrackId = uiState.trackState.selectedAudioId,
            onSelect = { trackId ->
                viewModel.selectAudioTrack(trackId)
                activeDialog = TrackDialogType.NONE
                resetHideTimer()
            },
            onDismiss = {
                activeDialog = TrackDialogType.NONE
                resetHideTimer()
            }
        )

        SubtitleTrackSheet(
            visible = activeDialog == TrackDialogType.SUBTITLE,
            tracks = uiState.trackState.subtitleTracks,
            selectedTrackId = uiState.trackState.selectedSubtitleId,
            onSelect = { trackId ->
                viewModel.selectSubtitleTrack(trackId)
                activeDialog = TrackDialogType.NONE
                resetHideTimer()
            },
            onDismiss = {
                activeDialog = TrackDialogType.NONE
                resetHideTimer()
            },
            subFontSize = subFontSize,
            subPos = subPos,
            onFontSizeChange = { viewModel.previewSubFontSize(it) },
            onFontSizeCommit = { viewModel.commitSubFontSize(it) },
            onPosChange = { viewModel.previewSubPos(it) },
            onPosCommit = { viewModel.commitSubPos(it) },
        )
    }
}

/**
 * Narrow collection point for high-frequency playback progress.
 * Only this wrapper (and PlayerBottomBar) recomposes on position ticks;
 * PlayScreen, VideoSurface, and PlayerTopBar do not.
 */
@Composable
private fun PlayerBottomBarHost(
    playbackProgressFlow: StateFlow<PlaybackProgress>,
    isEnabled: Boolean,
    onSeek: (Double) -> Unit,
    onSeekStarted: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val progress by playbackProgressFlow.collectAsState()
    PlayerBottomBar(
        isEnabled = isEnabled,
        positionSec = progress.positionSec,
        durationSec = progress.durationSec,
        onSeek = onSeek,
        onSeekStarted = onSeekStarted,
        modifier = modifier
    )
}
