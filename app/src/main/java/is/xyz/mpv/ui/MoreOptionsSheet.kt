package `is`.xyz.mpv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun MoreOptionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isPopUpPlayerActive: Boolean = false,
    isRepeatModeActive: Boolean = false,
    onLockClick: (() -> Unit)? = null,
    onSleepTimerClick: (() -> Unit)? = null,
    onPlaybackSpeedClick: (() -> Unit)? = null,
    onJumpToTimeClick: (() -> Unit)? = null,
    onEqualizerClick: (() -> Unit)? = null,
    onPlayAsAudioClick: (() -> Unit)? = null,
    onPopUpPlayerClick: (() -> Unit)? = null,
    onRepeatModeClick: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Scrim
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        // Sheet sliding from right
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            BoxWithConstraints {
                val sheetWidth = minOf(maxWidth * 0.85f, 320.dp)
                Surface(
                    modifier = Modifier
                        .width(sheetWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ),
                    tonalElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close"
                                )
                            }
                            Text(
                                text = "Options",
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(48.dp)) // balance the X button, no gear here
                        }

                        // --- Add your rows here ---
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (onLockClick != null) {
                                MoreOptionsRow(
                                    icon = Icons.Filled.Lock,
                                    label = "Lock",
                                    onClick = {
                                        onLockClick()
                                        onDismiss()
                                    }
                                )
                            }
                            if (onSleepTimerClick != null) {
                                MoreOptionsRow(
                                    icon = Icons.Filled.Timer,
                                    label = "Sleep timer",
                                    onClick = {
                                        onSleepTimerClick()
                                        onDismiss()
                                    }
                                )
                            }
                            if (onPlaybackSpeedClick != null) {
                                MoreOptionsRow(
                                    icon = Icons.Filled.Speed,
                                    label = "Playback speed",
                                    onClick = {
                                        onPlaybackSpeedClick()
                                        onDismiss()
                                    }
                                )
                            }
                            if (onJumpToTimeClick != null) {
                                MoreOptionsRow(
                                    icon = Icons.Filled.AccessTime,
                                    label = "Jump to Time",
                                    onClick = {
                                        onJumpToTimeClick()
                                        onDismiss()
                                    }
                                )
                            }
                            if (onEqualizerClick != null) {
                                MoreOptionsRow(
                                    icon = Icons.Filled.Equalizer,
                                    label = "Equalizer",
                                    onClick = {
                                        onEqualizerClick()
                                        onDismiss()
                                    }
                                )
                            }
                            if (onPlayAsAudioClick != null) {
                                MoreOptionsRow(
                                    icon = Icons.Filled.Audiotrack,
                                    label = "Play as audio",
                                    onClick = {
                                        onPlayAsAudioClick()
                                        onDismiss()
                                    }
                                )
                            }
                            if (onPopUpPlayerClick != null) {
                                MoreOptionsRow(
                                    icon = Icons.Filled.PictureInPictureAlt,
                                    label = "Pop-Up player",
                                    isActive = isPopUpPlayerActive,
                                    onClick = {
                                        onPopUpPlayerClick()
                                        onDismiss()
                                    }
                                )
                            }
                            if (onRepeatModeClick != null) {
                                MoreOptionsRow(
                                    icon = Icons.Filled.Repeat,
                                    label = "Repeat mode",
                                    isActive = isRepeatModeActive,
                                    onClick = {
                                        onRepeatModeClick()
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreOptionsRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    isActive: Boolean = false,
) {
    val contentColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

