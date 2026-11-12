package `is`.xyz.mpv.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.DecoderMode

private enum class MoreOptionsSheetView {
    MAIN,
    DECODER
}

@Composable
fun MoreOptionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isPipModeActive: Boolean = false,
    onLockClick: (() -> Unit)? = null,
    onPipModeClick: (() -> Unit)? = null,
    decoderMode: DecoderMode = DecoderMode.HW_PLUS,
    onDecoderModeChange: ((DecoderMode) -> Unit)? = null,
) {
    var currentView by remember { mutableStateOf(MoreOptionsSheetView.MAIN) }

    LaunchedEffect(visible) {
        if (!visible) {
            currentView = MoreOptionsSheetView.MAIN
        }
    }

    BackHandler(enabled = visible && currentView == MoreOptionsSheetView.DECODER) {
        currentView = MoreOptionsSheetView.MAIN
    }

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
                            if (currentView == MoreOptionsSheetView.MAIN) {
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
                                Spacer(modifier = Modifier.width(48.dp)) // balance the X button
                            } else {
                                IconButton(onClick = { currentView = MoreOptionsSheetView.MAIN }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                                Text(
                                    text = "Decoder",
                                    style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Close"
                                    )
                                }
                            }
                        }

                        if (currentView == MoreOptionsSheetView.MAIN) {
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
                                if (onPipModeClick != null) {
                                    MoreOptionsRow(
                                        icon = Icons.Filled.PictureInPictureAlt,
                                        label = "PIP mode",
                                        isActive = isPipModeActive,
                                        onClick = {
                                            onPipModeClick()
                                            onDismiss()
                                        }
                                    )
                                }
                                MoreOptionsRow(
                                    icon = Icons.Filled.Memory,
                                    label = "Decoder",
                                    trailingText = decoderMode.displayName,
                                    onClick = {
                                        currentView = MoreOptionsSheetView.DECODER
                                    }
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                DecoderMode.entries.forEach { mode ->
                                    val isSelected = mode == decoderMode
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = isSelected,
                                                onClick = { onDecoderModeChange?.invoke(mode) },
                                                role = Role.RadioButton
                                            )
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = null
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = mode.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                }
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
    trailingText: String? = null,
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
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}
