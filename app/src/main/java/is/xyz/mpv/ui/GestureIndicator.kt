package `is`.xyz.mpv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

sealed interface GestureIndicator {
    data class Seek(val deltaSec: Double, val targetPositionSec: Double) : GestureIndicator
    data class Volume(val level: Int, val max: Int) : GestureIndicator
    data class Brightness(val level: Float) : GestureIndicator
    data class Speed(val multiplier: Float) : GestureIndicator
    data class Zoom(val level: Float) : GestureIndicator
}

@Composable
fun GestureIndicatorOverlay(
    indicator: GestureIndicator?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = indicator != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        if (indicator == null) return@AnimatedVisibility

        if (indicator is GestureIndicator.Seek) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = TimeFormatter.formatDelta(indicator.deltaSec),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "[${TimeFormatter.formatTime(indicator.targetPositionSec)}]",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            return@AnimatedVisibility
        }

        val (icon, text) = when (indicator) {
            is GestureIndicator.Volume -> {
                Icons.Filled.VolumeUp to "Volume ${indicator.level}/${indicator.max}"
            }
            is GestureIndicator.Brightness -> {
                Icons.Filled.BrightnessHigh to "Brightness ${(indicator.level * 100).toInt()}%"
            }
            is GestureIndicator.Speed -> {
                Icons.Filled.Speed to "${indicator.multiplier}x"
            }
            is GestureIndicator.Zoom -> {
                Icons.Filled.ZoomIn to "${(indicator.level * 100).toInt()}%"
            }
            is GestureIndicator.Seek -> return@AnimatedVisibility
        }

        Box(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text,
                    color = Color.White
                )
            }
        }
    }
}
