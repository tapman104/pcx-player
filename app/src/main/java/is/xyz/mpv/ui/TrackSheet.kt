package `is`.xyz.mpv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.Track

internal fun getTrackDisplayName(track: Track): String {
    return when {
        !track.title.isNullOrBlank() -> track.title.trim()
        !track.lang.isNullOrBlank() -> track.lang.trim()
        else -> "Track ${track.id}"
    }
}

private enum class SubtitleSheetView {
    TRACKS,
    APPEARANCE
}

@Composable
fun AudioTrackSheet(
    visible: Boolean,
    tracks: List<Track>,
    selectedTrackId: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
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
                            text = "Audio Track",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(48.dp)) // balance the X button, no gear here
                    }

                    // Track list — same row/radio logic as AudioTrackDialog
                    if (tracks.isEmpty()) {
                        Text(
                            text = "No audio tracks available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            tracks.forEach { track ->
                                val isSelected = (selectedTrackId != null && track.id == selectedTrackId) ||
                                        (selectedTrackId == null && track.isSelected)
                                val label = getTrackDisplayName(track)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = isSelected,
                                            onClick = {
                                                onSelect(track.id)
                                                onDismiss()
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (!track.title.isNullOrBlank() && !track.lang.isNullOrBlank() && !track.title.trim().equals(track.lang.trim(), ignoreCase = true)) {
                                            Text(
                                                text = track.lang.trim(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
}
}

@Composable
fun SubtitleTrackSheet(
    visible: Boolean,
    tracks: List<Track>,
    selectedTrackId: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
    subFontSize: Int,
    subPos: Int,
    onFontSizeChange: (Int) -> Unit,
    onFontSizeCommit: (Int) -> Unit,
    onPosChange: (Int) -> Unit,
    onPosCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentView by remember { mutableStateOf(SubtitleSheetView.TRACKS) }
    var sliderFontSize by remember(subFontSize) { mutableFloatStateOf(subFontSize.toFloat()) }
    var sliderPos by remember(subPos) { mutableFloatStateOf(subPos.toFloat()) }

    LaunchedEffect(visible) {
        if (!visible) {
            currentView = SubtitleSheetView.TRACKS
        }
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
                        if (currentView == SubtitleSheetView.TRACKS) {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close"
                                )
                            }
                            Text(
                                text = "Subtitle Track",
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { currentView = SubtitleSheetView.APPEARANCE }) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Subtitle Appearance"
                                )
                            }
                        } else {
                            IconButton(onClick = { currentView = SubtitleSheetView.TRACKS }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to Subtitle Tracks"
                                )
                            }
                            Text(
                                text = "Appearance",
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

                    // Content
                    when (currentView) {
                        SubtitleSheetView.TRACKS -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                val isOffSelected = selectedTrackId == null || tracks.none { it.id == selectedTrackId }

                                // Explicit "Off" row - always present
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = isOffSelected,
                                            onClick = {
                                                onSelect(null)
                                                onDismiss()
                                            },
                                            role = Role.RadioButton
                                        )
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isOffSelected,
                                        onClick = null
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Off",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isOffSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isOffSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (tracks.isEmpty()) {
                                    Text(
                                        text = "No subtitle tracks available",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp)
                                    )
                                } else {
                                    tracks.forEach { track ->
                                        val isSelected = !isOffSelected && (selectedTrackId == track.id)
                                        val label = getTrackDisplayName(track)

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .selectable(
                                                selected = isSelected,
                                                onClick = {
                                                    onSelect(track.id)
                                                    onDismiss()
                                                },
                                                role = Role.RadioButton
                                            )
                                            .padding(horizontal = 8.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = null
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (!track.title.isNullOrBlank() && !track.lang.isNullOrBlank() && !track.title.trim().equals(track.lang.trim(), ignoreCase = true)) {
                                                Text(
                                                    text = track.lang.trim(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    SubtitleSheetView.APPEARANCE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            // Font size
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Font size",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = sliderFontSize.toInt().toString(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Slider(
                                value = sliderFontSize,
                                onValueChange = {
                                    sliderFontSize = it
                                    onFontSizeChange(it.toInt())
                                },
                                onValueChangeFinished = { onFontSizeCommit(sliderFontSize.toInt()) },
                                valueRange = 10f..120f,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Position
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Position",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = sliderPos.toInt().toString(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Slider(
                                value = sliderPos,
                                onValueChange = {
                                    sliderPos = it
                                    onPosChange(it.toInt())
                                },
                                onValueChangeFinished = { onPosCommit(sliderPos.toInt()) },
                                valueRange = 0f..100f,
                                modifier = Modifier.fillMaxWidth()
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

