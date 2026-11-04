package `is`.xyz.mpv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Minimal placeholder picker screen. Replace with a real browser/library
 * screen later (folder/video grid, recents, etc.) — this is scoped only to
 * unblock MainActivity's split from PlayerActivity.
 */
@Composable
fun HomeScreen(
    onOpenFileClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    // Surface forces the theme's background color explicitly. Without it,
    // this Box has no background of its own and falls through to the
    // Activity window background, which doesn't track theme mode changes.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Button(onClick = onOpenFileClick) {
                Text("Open video")
            }

            FloatingActionButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings"
                )
            }
        }
    }
}