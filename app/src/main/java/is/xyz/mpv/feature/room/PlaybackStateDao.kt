package `is`.xyz.mpv.feature.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PlaybackStateDao {
    @Query("SELECT * FROM playback_state WHERE videoPath = :videoPath")
    suspend fun getById(videoPath: String): PlaybackStateEntity?

    @Upsert
    suspend fun upsert(state: PlaybackStateEntity)
}

