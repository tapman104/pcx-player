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
    onVideoClick: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = videos,
            key = { it.id },
        ) { video ->
            VideoListItem(
                mediaFile = video,
                onClick = onVideoClick,
            )
        }
    }
}

