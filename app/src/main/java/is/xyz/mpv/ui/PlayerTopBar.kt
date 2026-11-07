package `is`.xyz.mpv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTopBar(
    title: String?,
    onBackClick: () -> Unit,
    onOpenFileClick: (() -> Unit)? = null,
    onAudioTrackClick: (() -> Unit)? = null,
    onSubtitleTrackClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    isEngineReady: Boolean = true,
    modifier: Modifier = Modifier
) {
    val brush = Brush.verticalGradient(
        colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
    )
    Box(
        modifier = modifier.background(brush)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = if (!title.isNullOrBlank()) title else "PCX Player",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            },
            actions = {
                if (onAudioTrackClick != null) {
                    IconButton(
                        onClick = onAudioTrackClick,
                        enabled = isEngineReady
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Audiotrack,
                            contentDescription = "Audio track",
                            tint = if (isEngineReady) Color.White else Color.Gray
                        )
                    }
                }
                if (onSubtitleTrackClick != null) {
                    IconButton(
                        onClick = onSubtitleTrackClick,
                        enabled = isEngineReady
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Subtitles,
                            contentDescription = "Subtitle track",
                            tint = if (isEngineReady) Color.White else Color.Gray
                        )
                    }
                }
                if (onMoreClick != null) {
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options",
                            tint = Color.White
                        )
                    }
                }
                if (onOpenFileClick != null) {
                    IconButton(onClick = onOpenFileClick) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = "Open video file",
                            tint = Color.White
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
                actionIconContentColor = Color.White
            )
        )
    }
}