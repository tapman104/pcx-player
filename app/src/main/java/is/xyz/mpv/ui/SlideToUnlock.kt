package `is`.xyz.mpv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideToUnlock(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val animatedProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    var animJob by remember { mutableStateOf<Job?>(null) }
    val interactionSource = remember { MutableInteractionSource() }

    val currentFraction = if (isDragging) dragFraction else animatedProgress.value

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = "Slide to unlock",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Slider(
            value = currentFraction.coerceIn(0f, 1f),
            onValueChange = { newFraction ->
                animJob?.cancel()
                isDragging = true
                dragFraction = newFraction
            },
            onValueChangeFinished = {
                val releasedFraction = dragFraction
                if (releasedFraction >= 0.8f) {
                    isDragging = false
                    dragFraction = 0f
                    animJob?.cancel()
                    animJob = coroutineScope.launch {
                        animatedProgress.snapTo(0f)
                    }
                    onUnlock()
                } else {
                    animJob?.cancel()
                    animJob = coroutineScope.launch {
                        animatedProgress.snapTo(releasedFraction)
                        isDragging = false
                        animatedProgress.animateTo(0f, animationSpec = tween(durationMillis = 200))
                    }
                }
            },
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            track = { sliderState ->
                val range = sliderState.valueRange
                val fraction = if (range.endInclusive > range.start) {
                    ((sliderState.value - range.start) /
                            (range.endInclusive - range.start)).coerceIn(0f, 1f)
                } else 0f

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val r = h / 2f

                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(r, r)
                    )

                    if (fraction > 0f) {
                        drawRoundRect(
                            color = Color.White,
                            size = Size(w * fraction, h),
                            cornerRadius = CornerRadius(r, r)
                        )
                    }
                }
            },
            thumb = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

