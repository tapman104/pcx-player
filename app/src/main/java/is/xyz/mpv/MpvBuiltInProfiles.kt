package `is`.xyz.mpv

data class MpvProfile(
    val id: String,
    val name: String,
    val description: String,
    val videoScale: String? = null,
    val videoDownscale: String? = null,
)

object MpvBuiltInProfiles {
    val MPV_DEFAULT = MpvProfile(
        id = "mpv_default",
        name = "MPV Default",
        description = "Use MPV's normal built-in defaults for optional settings",
        videoScale = null,
        videoDownscale = null,
    )

    val FAST = MpvProfile(
        id = "fast",
        name = "Fast",
        description = "Bilinear scaling for lower resource usage",
        videoScale = "bilinear",
        videoDownscale = "bilinear",
    )

    val BALANCED = MpvProfile(
        id = "balanced",
        name = "Balanced",
        description = "Balance performance and video quality using Mitchell scaling",
        videoScale = "mitchell",
        videoDownscale = "mitchell",
    )

    val HIGH_QUALITY = MpvProfile(
        id = "high_quality",
        name = "High Quality",
        description = "High quality scaling using EWA Lanczossharp",
        videoScale = "ewa_lanczossharp",
        videoDownscale = null,
    )

    val ALL: List<MpvProfile> = listOf(
        MPV_DEFAULT,
        FAST,
        BALANCED,
        HIGH_QUALITY,
    )

    fun fromId(id: String?): MpvProfile {
        return ALL.firstOrNull { it.id == id } ?: when (id) {
            "mobile_performance" -> FAST
            "default", "custom" -> MPV_DEFAULT
            else -> MPV_DEFAULT
        }
    }
}
