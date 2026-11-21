package `is`.xyz.mpv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import `is`.xyz.mpv.MpvEngine
import `is`.xyz.mpv.MpvSurfaceView

@Composable
fun VideoSurface(
    engine: MpvEngine,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MpvSurfaceView(context, engine)
            },
            onRelease = { view ->
                view.release()
            }
        )
    }
}
