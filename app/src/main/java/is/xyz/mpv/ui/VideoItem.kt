package `is`.xyz.mpv.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.feature.library.MediaFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object VideoThumbnailLoader {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024)
    private val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    suspend fun loadThumbnail(context: Context, mediaFile: MediaFile): Bitmap? =
        withContext(Dispatchers.IO) {
            val uriString = mediaFile.uri.toString()
            cache.get(uriString)?.let { return@withContext it }

            val bitmap: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(
                        mediaFile.uri,
                        Size(512, 288),
                        null
                    )
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver,
                        mediaFile.id,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            if (bitmap != null) {
                cache.put(uriString, bitmap)
            }
            bitmap
        }
}

@Composable
fun VideoItem(
    mediaFile: MediaFile,
    onClick: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    VideoItemContainer(
        mediaFile = mediaFile,
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            VideoThumbnail(
                mediaFile = mediaFile,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(6.dp))

            VideoTitle(displayName = mediaFile.displayName)
        }
    }
}

@Composable
fun VideoListItem(
    mediaFile: MediaFile,
    onClick: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    VideoItemContainer(
        mediaFile = mediaFile,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VideoThumbnail(
                mediaFile = mediaFile,
                modifier = Modifier.width(128.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            VideoTitle(
                displayName = mediaFile.displayName,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VideoItemContainer(
    mediaFile: MediaFile,
    onClick: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick(mediaFile) }
            .padding(4.dp),
    ) {
        content()
    }
}

@Composable
private fun VideoThumbnail(
    mediaFile: MediaFile,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var thumbnail by remember(mediaFile.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(mediaFile.id, mediaFile.uri) {
        thumbnail = VideoThumbnailLoader.loadThumbnail(context, mediaFile)
    }

    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = thumbnail
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = mediaFile.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }

        if (mediaFile.durationMs > 0) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
            ) {
                Text(
                    text = TimeFormatter.formatTime(mediaFile.durationMs / 1000.0),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoTitle(
    displayName: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = displayName,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

