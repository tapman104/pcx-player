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

    // PLACEHOLDER: Custom is reserved for future individually configurable playback
    // settings (scale, debanding, interpolation, etc). There is no persisted per-field
    // storage for those yet, so CUSTOM currently applies the exact same defaults as
    // MPV_DEFAULT and behaves identically to it. Do not treat this as working
    // customization until individual settings are wired up to real storage.
    val CUSTOM = MpvProfile(
        id = "custom",
        name = "Custom",
        description = "Reserved for individually configured playback settings (not yet implemented — currently behaves like MPV Default)",
        videoScale = null,
        videoDownscale = null,
    )

    val ALL: List<MpvProfile> = listOf(
        MPV_DEFAULT,
        FAST,
        BALANCED,
        HIGH_QUALITY,
        CUSTOM,
    )

    fun fromId(id: String?): MpvProfile {
        return ALL.firstOrNull { it.id == id } ?: when (id) {
            "mobile_performance" -> FAST
            "default" -> MPV_DEFAULT
            else -> MPV_DEFAULT
        }
    }
}
