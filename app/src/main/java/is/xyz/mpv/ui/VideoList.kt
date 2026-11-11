package `is`.xyz.mpv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.feature.library.MediaFile

@Composable
fun VideoList(
    videos: List<MediaFile>,
    menuVideo: MediaFile?,
    onVideoClick: (MediaFile) -> Unit,
    onMenuOpen: (MediaFile) -> Unit,
    onMenuDismiss: () -> Unit,
    onCopy: (MediaFile) -> Unit,
    onRename: (MediaFile) -> Unit,
    onDelete: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            items = videos,
            key = { it.id },
        ) { video ->
            VideoListItem(
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

