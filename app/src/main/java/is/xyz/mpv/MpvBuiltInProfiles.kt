package `is`.xyz.mpv

data class MpvProfile(
    val id: String,
    val name: String,
    val description: String,
    val options: MpvOptions,
)

object MpvBuiltInProfiles {
    val DEFAULT = MpvProfile(
        id = "default",
        name = "Default",
        description = "Preserve current application playback behaviour",
        options = MpvOptions(),
    )

    val MOBILE_PERFORMANCE = MpvProfile(
        id = "mobile_performance",
        name = "Mobile Performance",
        description = "Prioritize performance and lower resource usage",
        options = MpvOptions(
            fastDecode = true,
        ),
    )

    val BALANCED = MpvProfile(
        id = "balanced",
        name = "Balanced",
        description = "Balance performance and video quality",
        options = MpvOptions(
            videoScale = "spline36",
        ),
    )

    val HIGH_QUALITY = MpvProfile(
        id = "high_quality",
        name = "High Quality",
        description = "Prioritize video quality where supported",
        options = MpvOptions(
            videoScale = "ewa_lanczos",
            debanding = Debanding.GPU,
        ),
    )

    val ALL: List<MpvProfile> = listOf(
        DEFAULT,
        MOBILE_PERFORMANCE,
        BALANCED,
        HIGH_QUALITY,
    )

    fun fromId(id: String?): MpvProfile {
        return ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}

