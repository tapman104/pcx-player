package `is`.xyz.mpv

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Requires the dependency: androidx.datastore:datastore-preferences
// and a single app-level DataStore<Preferences> delegate (declared below).

private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences"
)

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

enum class DecoderMode {
    HW_PLUS,
    HW,
    SW;

    val displayName: String
        get() = when (this) {
            HW_PLUS -> "HW+"
            HW -> "HW"
            SW -> "SW"
        }
}

enum class RendererMode {
    GPU,
    GPU_NEXT;

    val displayName: String
        get() = when (this) {
            GPU -> "GPU"
            GPU_NEXT -> "GPU-next"
        }
}

/**
 * Minimal preferences repository — orientation mode only for now.
 * Expand with additional keys as more persisted settings are added.
 */
class UserPreferencesRepository(private val appContext: Context) {

    private val dataStore get() = appContext.userPreferencesDataStore

    private object Keys {
        val ORIENTATION_MODE = stringPreferencesKey("orientation_mode")
        val SUB_FONT_SIZE = intPreferencesKey("sub_font_size")
        val SUB_POS = intPreferencesKey("sub_pos")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DECODER_MODE = stringPreferencesKey("decoder_mode")
        val RENDERER_MODE = stringPreferencesKey("renderer_mode")
        val FAST_DECODE = booleanPreferencesKey("fast_decode")
        val DEBANDING = booleanPreferencesKey("debanding")
        val AMOLED_ENABLED = booleanPreferencesKey("amoled_enabled")
        val PREFERRED_AUDIO_LANG = stringPreferencesKey("preferred_audio_lang")
        val PREFERRED_SUBTITLE_LANG = stringPreferencesKey("preferred_subtitle_lang")
        val PLAYBACK_PROFILE = stringPreferencesKey("playback_profile")
    }

    val playbackProfile: Flow<MpvProfile> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.PLAYBACK_PROFILE]
        MpvBuiltInProfiles.fromId(raw)
    }

    val playbackProfileId: Flow<String> = playbackProfile.map { it.id }

    /** Defaults to [OrientationMode.AUTO_BY_VIDEO] if unset or unparseable. */
    val orientationMode: Flow<OrientationMode> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.ORIENTATION_MODE]
        raw?.let { stored ->
            runCatching { OrientationMode.valueOf(stored) }.getOrNull()
        } ?: OrientationMode.AUTO_BY_VIDEO
    }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.THEME_MODE]
        raw?.let { stored ->
            runCatching { ThemeMode.valueOf(stored) }.getOrNull()
        } ?: ThemeMode.SYSTEM
    }

    val amoledEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.AMOLED_ENABLED] ?: false }

    val decoderMode: Flow<DecoderMode> = dataStore.data.map { prefs ->
        when (prefs[Keys.DECODER_MODE]) {
            "HW_PLUS" -> DecoderMode.HW_PLUS
            "HW" -> DecoderMode.HW
            "SW" -> DecoderMode.SW
            "AUTO", "HARDWARE" -> DecoderMode.HW_PLUS
            "HARDWARE_COPY" -> DecoderMode.HW
            "SOFTWARE" -> DecoderMode.SW
            else -> DecoderMode.HW_PLUS
        }
    }

    val rendererMode: Flow<RendererMode> = dataStore.data.map { prefs ->
        when (prefs[Keys.RENDERER_MODE]) {
            "GPU" -> RendererMode.GPU
            "GPU_NEXT" -> RendererMode.GPU_NEXT
            else -> RendererMode.GPU
        }
    }

    val fastDecode: Flow<Boolean> = dataStore.data.map { it[Keys.FAST_DECODE] ?: false }
    val debanding: Flow<Boolean> = dataStore.data.map { it[Keys.DEBANDING] ?: false }

    val subFontSize: Flow<Int> = dataStore.data.map { it[Keys.SUB_FONT_SIZE] ?: 55 }
    val subPos: Flow<Int> = dataStore.data.map { it[Keys.SUB_POS] ?: 100 }

    val preferredAudioLang: Flow<String?> = dataStore.data.map { it[Keys.PREFERRED_AUDIO_LANG] }
    val preferredSubtitleLang: Flow<String?> = dataStore.data.map { it[Keys.PREFERRED_SUBTITLE_LANG] }

    suspend fun setOrientationMode(mode: OrientationMode) {
        dataStore.edit { prefs ->
            prefs[Keys.ORIENTATION_MODE] = mode.name
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun setAmoledEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AMOLED_ENABLED] = enabled }
    }

    suspend fun setDecoderMode(mode: DecoderMode) {
        dataStore.edit { prefs ->
            prefs[Keys.DECODER_MODE] = mode.name
        }
    }

    suspend fun setRendererMode(mode: RendererMode) {
        dataStore.edit { prefs ->
            prefs[Keys.RENDERER_MODE] = mode.name
        }
    }

    suspend fun setFastDecode(enabled: Boolean) {
        dataStore.edit { it[Keys.FAST_DECODE] = enabled }
    }

    suspend fun setDebanding(enabled: Boolean) {
        dataStore.edit { it[Keys.DEBANDING] = enabled }
    }

    suspend fun migrateLegacyDecoderModeIfNeeded() {
        dataStore.edit { prefs ->
            val migrated = when (prefs[Keys.DECODER_MODE]) {
                "AUTO", "HARDWARE" -> DecoderMode.HW_PLUS.name
                "HARDWARE_COPY" -> DecoderMode.HW.name
                "SOFTWARE" -> DecoderMode.SW.name
                else -> null
            }
            if (migrated != null) {
                prefs[Keys.DECODER_MODE] = migrated
            }
        }
    }

    suspend fun setSubFontSize(size: Int) {
        dataStore.edit { it[Keys.SUB_FONT_SIZE] = size }
    }

    suspend fun setSubPos(pos: Int) {
        dataStore.edit { it[Keys.SUB_POS] = pos }
    }

    suspend fun setPreferredAudioLang(code: String?) {
        dataStore.edit { prefs ->
            if (code == null) {
                prefs.remove(Keys.PREFERRED_AUDIO_LANG)
            } else {
                prefs[Keys.PREFERRED_AUDIO_LANG] = code
            }
        }
    }

    suspend fun setPreferredSubtitleLang(code: String?) {
        dataStore.edit { prefs ->
            if (code == null) {
                prefs.remove(Keys.PREFERRED_SUBTITLE_LANG)
            } else {
                prefs[Keys.PREFERRED_SUBTITLE_LANG] = code
            }
        }
    }

    suspend fun setPlaybackProfileId(profileId: String) {
        dataStore.edit { prefs ->
            prefs[Keys.PLAYBACK_PROFILE] = profileId
        }
    }
}
