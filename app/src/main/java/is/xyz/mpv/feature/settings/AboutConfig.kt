package `is`.xyz.mpv.feature.settings

/**
 * Centralized configuration constants for the About screen and project links.
 * All URLs, donation IDs, and developer identity fallbacks are defined here.
 */
object AboutConfig {
    const val GITHUB_REPO_URL = "https://github.com/tapman104/pcx-player"
    const val DONATE_BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/tapman"
    const val DONATE_GITHUB_SPONSORS_URL = "https://github.com/sponsors/tapman104"
    const val DONATE_UPI_ID = "tapman2@ybl"
    const val DEVELOPER_NAME_FALLBACK = "tapman104"

    // Developer name: no repo-configured display name exists in build files,
    // resources, or BuildConfig, so tapman104 is used per the discovery-then-fallback rule.
    const val DEVELOPER_NAME = DEVELOPER_NAME_FALLBACK
    const val APP_NAME = "PCX Player"
}

