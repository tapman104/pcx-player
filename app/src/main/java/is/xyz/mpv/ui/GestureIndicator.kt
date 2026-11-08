package `is`.xyz.mpv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

enum class SeekSource {
    DOUBLE_TAP,
    SWIPE,
}

sealed interface GestureIndicator {
    data class Seek(
        val deltaSec: Double,
        val targetPositionSec: Double,
        val source: SeekSource = SeekSource.SWIPE
    ) : GestureIndicator
    data class Volume(val level: Int, val max: Int) : GestureIndicator
    data class Brightness(val level: Float) : GestureIndicator
    data class Speed(val multiplier: Float) : GestureIndicator
    data class Zoom(val level: Float) : GestureIndicator
}

fun alignmentFor(
    indicator: GestureIndicator?,
    doubleTapSidePadding: Dp = 0.dp
): Pair<Alignment, PaddingValues> = when (indicator) {
    is GestureIndicator.Brightness -> Alignment.CenterEnd to PaddingValues(end = 24.dp)
    is GestureIndicator.Volume -> Alignment.CenterStart to PaddingValues(start = 24.dp)
    is GestureIndicator.Seek -> {
        if (indicator.source == SeekSource.DOUBLE_TAP) {
            if (indicator.deltaSec < 0) {
                Alignment.CenterStart to PaddingValues(start = doubleTapSidePadding)
            } else {
                Alignment.CenterEnd to PaddingValues(end = doubleTapSidePadding)
            }
        } else {
            Alignment.Center to PaddingValues(0.dp)
        }
    }
    is GestureIndicator.Speed -> Alignment.TopCenter to PaddingValues(top = 80.dp)
    is GestureIndicator.Zoom, null -> Alignment.Center to PaddingValues(0.dp)
}

@Composable
fun BoxScope.GestureIndicatorOverlay(
    indicator: GestureIndicator?,
    modifier: Modifier = Modifier,
    doubleTapSidePadding: Dp = 0.dp,
) {
    var lastIndicator by remember { mutableStateOf<GestureIndicator?>(null) }
    if (indicator != null) {
        lastIndicator = indicator
    }

    val current = indicator ?: lastIndicator
    val (indicatorAlignment, indicatorPadding) = alignmentFor(current, doubleTapSidePadding)

    AnimatedVisibility(
        visible = indicator != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.align(indicatorAlignment).padding(indicatorPadding),
    ) {
        val current = current ?: return@AnimatedVisibility

        if (current is GestureIndicator.Seek) {
            when (current.source) {
                SeekSource.DOUBLE_TAP -> {
                    val icon = if (current.deltaSec >= 0) {
                        Icons.Filled.FastForward
                    } else {
                        Icons.Filled.FastRewind
                    }
                    val sign = if (current.deltaSec < 0) "-" else "+"
                    val seconds = abs(current.deltaSec).toLong()
                    val text = "$sign${seconds}s"

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = text,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        )
                    }
                }
                SeekSource.SWIPE -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = TimeFormatter.formatDelta(current.deltaSec),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "[${TimeFormatter.formatTime(current.targetPositionSec)}]",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                }
            }
            return@AnimatedVisibility
        }

        if (current is GestureIndicator.Speed) {
            Text(
                text = "${current.multiplier}x",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            )
            return@AnimatedVisibility
        }

        val (icon, text) = when (current) {
            is GestureIndicator.Volume -> {
                Icons.Filled.VolumeUp to "Volume ${current.level}/${current.max}"
            }
            is GestureIndicator.Brightness -> {
                Icons.Filled.BrightnessHigh to "Brightness ${(current.level * 100).toInt()}%"
            }
            is GestureIndicator.Zoom -> {
                Icons.Filled.ZoomIn to "${(current.level * 100).toInt()}%"
            }
            is GestureIndicator.Seek -> return@AnimatedVisibility
            is GestureIndicator.Speed -> return@AnimatedVisibility
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
