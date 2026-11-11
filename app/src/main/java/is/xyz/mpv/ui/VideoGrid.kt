package `is`.xyz.mpv.ui

import androidx.compose.foundation.layout.Arrangement
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
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxSize(),
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

