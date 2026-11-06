package `is`.xyz.mpv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PlayerBottomBar(
    isEnabled: Boolean,
    positionSec: Double,
    durationSec: Double,
    onSeek: (Double) -> Unit,
    onSeekStarted: (() -> Unit)? = null,
    onRotateClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
    )
    Box(
        modifier = modifier.background(brush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (onRotateClick != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(onClick = onRotateClick) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "Rotate screen",
                            tint = Color.White
                        )
                    }
                }
            }
            PlayerSeekBar(
                positionSec = positionSec,
                durationSec = durationSec,
                enabled = isEnabled,
                onSeek = onSeek,
                onSeekStarted = onSeekStarted,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
