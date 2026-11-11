package `is`.xyz.mpv.feature.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted per-video playback state keyed by the resolved playable path/URI string
 * handed to [PlayerViewModel.loadFile].
 *
 * Nullable track IDs represent "no manual override; use language-based auto-selection".
 */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey
    val videoPath: String,
    val positionSec: Double = 0.0,
    val audioTrackId: Int? = null,
    val subtitleTrackId: Int? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
)

