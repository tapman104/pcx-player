package `is`.xyz.mpv

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import `is`.xyz.mpv.ui.AudioTrackSheet
import `is`.xyz.mpv.ui.GestureIndicator
import `is`.xyz.mpv.ui.GestureIndicatorOverlay
import `is`.xyz.mpv.ui.MoreOptionsSheet
import `is`.xyz.mpv.ui.PlayerBottomBar
import `is`.xyz.mpv.ui.PlayerTopBar
import `is`.xyz.mpv.ui.SeekSource
import `is`.xyz.mpv.ui.SlideToUnlock
import `is`.xyz.mpv.ui.SubtitleTrackSheet
import `is`.xyz.mpv.ui.VideoSurface
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class TrackDialogType {
    NONE,
    AUDIO,
    SUBTITLE,
    MORE_OPTIONS
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
    val subFontSize by viewModel.subFontSize.collectAsState()
    val subPos by viewModel.subPos.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    // Brightness gesture overrides window.attributes.screenBrightness, which otherwise
    // stays overridden (system auto-brightness stays disabled) after leaving this screen.
    // Reset it to "follow system" on dispose.
    DisposableEffect(Unit) {
        onDispose {
            var ctx: android.content.Context? = context
            while (ctx is android.content.ContextWrapper && ctx !is Activity) {
                ctx = ctx.baseContext
            }
            (ctx as? Activity)?.window?.let { window ->
                val params = window.attributes
                params.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = params
            }
        }
    }

    var isLocked by remember { mutableStateOf(false) }
    var lockControlsVisible by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var activeDialog by remember { mutableStateOf(TrackDialogType.NONE) }
    var indicator by remember { mutableStateOf<GestureIndicator?>(null) }
    val isLongPressActive = remember { mutableStateOf(false) }
    var doubleTapAccumulatedDelta by remember { mutableStateOf(0.0) }
    var seekStartSec by remember { mutableStateOf(0.0) }
    var seekAccumulatedDelta by remember { mutableStateOf(0.0) }
    val coroutineScope = rememberCoroutineScope()
    val hideJob = remember { JobHolder() }
    var topBarHeight by remember { mutableStateOf(0.dp) }
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    fun resetHideTimer() {
        if (isLocked) return
        hideJob.job?.cancel()
        hideJob.job = coroutineScope.launch {
            delay(5_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(indicator, isLongPressActive.value) {
        if (indicator != null) {
            hideJob.job?.cancel()
            controlsVisible = false
            if (isLongPressActive.value || indicator is GestureIndicator.Zoom) {
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

    LaunchedEffect(controlsVisible, isLocked) {
        val effectiveControlsVisible = !isLocked && controlsVisible
        onControlsVisibilityChanged?.invoke(effectiveControlsVisible)
        if (!effectiveControlsVisible) {
            topBarHeight = 0.dp
            bottomBarHeight = 0.dp
        }
    }

    LaunchedEffect(Unit) {
        resetHideTimer()
    }

    LaunchedEffect(activeDialog) {
        if (activeDialog != TrackDialogType.NONE) {
            hideJob.job?.cancel()
        }
    }

    BackHandler(enabled = activeDialog != TrackDialogType.NONE) {
        activeDialog = TrackDialogType.NONE
        resetHideTimer()
    }

    BackHandler(enabled = isLocked) {
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
                    isGestureLocked = isLocked,
                    isLongPressActive = if (isLocked) null else isLongPressActive,
                    onSingleTap = {
                        if (isLocked) {
                            lockControlsVisible = !lockControlsVisible
                        } else if (activeDialog == TrackDialogType.NONE) {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) resetHideTimer()
                        }
                    },
                    onSeekGestureStart = if (isLocked) { {} } else {
                        {
                            doubleTapAccumulatedDelta = 0.0
                            seekStartSec = viewModel.playbackProgress.value.positionSec
                            seekAccumulatedDelta = 0.0
                        }
                    },
                    onSeekBy = if (isLocked) { _, _ -> } else { delta, source ->
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
                                viewModel.seekBy(delta)
                            }
                        }
                    },
                    onSpeedChange = if (isLocked) { _ -> } else {
                        {
                            doubleTapAccumulatedDelta = 0.0
                            viewModel.setSpeed(it.toDouble())
                            if (isLongPressActive.value) {
                                indicator = GestureIndicator.Speed(it)
                            }
                        }
                    },
                    onZoomBy = if (isLocked) { _ -> } else {
                        {
                            doubleTapAccumulatedDelta = 0.0
                            viewModel.zoomBy(it)
                            if (indicator !is GestureIndicator.Zoom) {
                                indicator = GestureIndicator.Zoom(1f)
                            }
                        }
                    },
                    onDoubleTap = if (isLocked) { {} } else {
                        {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) resetHideTimer()
                        }
                    },
                    getCurrentSpeed = { viewModel.getCurrentSpeed() },
                    onVolumeChange = if (isLocked) null else { level, max ->
                        doubleTapAccumulatedDelta = 0.0
                        indicator = GestureIndicator.Volume(level, max)
                    },
                    onBrightnessChange = if (isLocked) null else { level ->
                        doubleTapAccumulatedDelta = 0.0
                        indicator = GestureIndicator.Brightness(level)
                    },
                )
        )

        // Overlay: Center play/pause button
        Box(
            modifier = Modifier.align(Alignment.Center)
        ) {
            AnimatedVisibility(
                visible = !isLocked && controlsVisible,
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
            visible = !isLocked && controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .onGloballyPositioned {
                    if (!isLocked && controlsVisible) {
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
                    hideJob.job?.cancel()
                    activeDialog = TrackDialogType.AUDIO
                },
                onSubtitleTrackClick = {
                    hideJob.job?.cancel()
                    activeDialog = TrackDialogType.SUBTITLE
                },
                onMoreClick = {
                    hideJob.job?.cancel()
                    activeDialog = TrackDialogType.MORE_OPTIONS
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Overlay: Bottom app bar with seek bar
        AnimatedVisibility(
            visible = !isLocked && controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .onGloballyPositioned {
                    if (!isLocked && controlsVisible) {
                        bottomBarHeight = with(density) { it.size.height.toDp() }
                    }
                }
        ) {
            PlayerBottomBarHost(
                playbackProgressFlow = viewModel.playbackProgress,
                isEnabled = uiState.isEngineReady,
                isFitScreenEnabledFlow = viewModel.isFitScreenEnabled,
                onSeek = { pos ->
                    viewModel.seekTo(pos)
                    controlsVisible = true
                    resetHideTimer()
                },
                onSeekStarted = {
                    hideJob.job?.cancel()
                },
                onRotateClick = {
                    viewModel.toggleOrientationOverride { configuration.orientation }
                    resetHideTimer()
                },
                onFitScreenClick = {
                    viewModel.toggleFitScreen()
                    resetHideTimer()
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

        GestureIndicatorHost(
            indicator = if (isLocked) null else indicator,
            currentZoomFlow = viewModel.currentZoom,
            onDismissZoom = {
                indicator = null
            },
            doubleTapSidePadding = doubleTapSidePadding
        )
        AudioTrackSheet(
            visible = !isLocked && activeDialog == TrackDialogType.AUDIO,
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
            visible = !isLocked && activeDialog == TrackDialogType.SUBTITLE,
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

        MoreOptionsSheet(
            visible = !isLocked && activeDialog == TrackDialogType.MORE_OPTIONS,
            onDismiss = {
                activeDialog = TrackDialogType.NONE
                resetHideTimer()
            },
            isPipModeActive = false,
            onLockClick = {
                isLocked = true
                lockControlsVisible = false
                controlsVisible = false
                activeDialog = TrackDialogType.NONE
                hideJob.job?.cancel()
            },
            onPipModeClick = {
                // TODO: implement
            }
        )

        // Overlay: Lock controls (badge + unlock)
        AnimatedVisibility(
            visible = isLocked && lockControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Locked",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Locked",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                }

                SlideToUnlock(
                    onUnlock = {
                        isLocked = false
                        lockControlsVisible = false
                        controlsVisible = true
                        resetHideTimer()
                    }
                )
            }
        }
    }
}

/**
 * Narrow collection point for high-frequency playback progress and fit-screen state.
 * Only this wrapper (and PlayerBottomBar) recomposes on position ticks or fit-screen toggles;
 * PlayScreen, VideoSurface, and PlayerTopBar do not.
 */
@Composable
private fun PlayerBottomBarHost(
    playbackProgressFlow: StateFlow<PlaybackProgress>,
    isEnabled: Boolean,
    isFitScreenEnabledFlow: StateFlow<Boolean>,
    onSeek: (Double) -> Unit,
    onSeekStarted: (() -> Unit)? = null,
    onRotateClick: (() -> Unit)? = null,
    onFitScreenClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isFitScreenEnabled by isFitScreenEnabledFlow.collectAsState()

    var progress by remember { mutableStateOf(playbackProgressFlow.value) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekVersion by remember { mutableStateOf(0) }

    LaunchedEffect(seekVersion) {
        if (seekVersion > 0) {
            // Post-seek delay: prevent stale engine progress from overwriting user seek position
            delay(1000L)
        }
        while (true) {
            if (!isSeeking) {
                progress = playbackProgressFlow.value
            }
            delay(1000L)
        }
    }

    PlayerBottomBar(
        isEnabled = isEnabled,
        positionSec = progress.positionSec,
        durationSec = progress.durationSec,
        onSeek = { targetPos ->
            isSeeking = false
            progress = progress.copy(positionSec = targetPos)
            seekVersion++
            onSeek(targetPos)
        },
        onSeekStarted = {
            isSeeking = true
            onSeekStarted?.invoke()
        },
        onRotateClick = onRotateClick,
        isFitScreenEnabled = isFitScreenEnabled,
        onFitScreenClick = onFitScreenClick,
        modifier = modifier
    )
}

/**
 * Narrow collection point for zoom gesture indicator.
 * Observes currentZoom locally so pinch-zoom updates do not trigger recomposition of PlayScreen.
 */
@Composable
private fun BoxScope.GestureIndicatorHost(
    indicator: GestureIndicator?,
    currentZoomFlow: StateFlow<Float>,
    onDismissZoom: () -> Unit,
    doubleTapSidePadding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val currentZoom by currentZoomFlow.collectAsState()

    LaunchedEffect(currentZoom, indicator is GestureIndicator.Zoom) {
        if (indicator is GestureIndicator.Zoom) {
            delay(600L)
            onDismissZoom()
        }
    }

    val effectiveIndicator = if (indicator is GestureIndicator.Zoom) {
        GestureIndicator.Zoom(currentZoom)
    } else {
        indicator
    }

    GestureIndicatorOverlay(
        indicator = effectiveIndicator,
        doubleTapSidePadding = doubleTapSidePadding,
        modifier = modifier
    )
}

private class JobHolder(var job: Job? = null)