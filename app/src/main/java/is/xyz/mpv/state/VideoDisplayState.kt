package `is`.xyz.mpv.state

data class VideoTransform(val zoom: Float = 1f, val panX: Double = 0.0, val panY: Double = 0.0)

enum class VideoDisplayMode { ORIGINAL, FULL_SCREEN }

data class VideoDisplayState(
    val transform: VideoTransform = VideoTransform(),
    val mode: VideoDisplayMode = VideoDisplayMode.ORIGINAL
)
