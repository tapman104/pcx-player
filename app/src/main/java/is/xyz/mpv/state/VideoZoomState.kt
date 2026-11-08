package `is`.xyz.mpv.state

data class VideoTransform(
    val zoom: Float = 1f,
    val panX: Double = 0.0,
    val panY: Double = 0.0,
)

data class VideoZoomState(
    val current: VideoTransform = VideoTransform(),
    val savedForFitScreen: VideoTransform? = null,
) {
    val isFitScreenEnabled: Boolean
        get() = savedForFitScreen != null

    val zoom: Float
        get() = current.zoom

    val panX: Double
        get() = current.panX

    val panY: Double
        get() = current.panY
}
