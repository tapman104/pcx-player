package `is`.xyz.mpv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.feature.library.MediaFile

@Composable
fun VideoGrid(
    videos: List<MediaFile>,
    menuVideo: MediaFile?,
    onVideoClick: (MediaFile) -> Unit,
    onMenuOpen: (MediaFile) -> Unit,
    onMenuDismiss: () -> Unit,
    onCopy: (MediaFile) -> Unit,
    onRename: (MediaFile) -> Unit,
    onDelete: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columnCount = when {
            maxWidth < 600.dp -> 2
            maxWidth < 840.dp -> 3
            else -> 4
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = videos,
                key = { it.id }
            ) { video ->
                VideoItem(
                    mediaFile = video,
                    isMenuOpen = menuVideo?.id == video.id,
                    onClick = onVideoClick,
                    onMenuOpen = onMenuOpen,
                    onMenuDismiss = onMenuDismiss,
                    onCopy = onCopy,
                    onRename = onRename,
                    onDelete = onDelete,
                )
            }
        }
    }
}

