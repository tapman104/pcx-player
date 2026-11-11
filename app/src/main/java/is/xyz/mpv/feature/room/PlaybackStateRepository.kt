package `is`.xyz.mpv.feature.room

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repository for per-video playback state (resume position and track overrides).
 * Mirrors [UserPreferencesRepository]'s Context-backed construction and suspend write API.
 */
class PlaybackStateRepository internal constructor(
    private val appContext: Context,
    private val daoOverride: PlaybackStateDao?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(appContext: Context) : this(appContext, null, Dispatchers.IO)

    private val writeMutex = Mutex()

    private fun resolveDao(): PlaybackStateDao =
        daoOverride ?: AppDatabase.getInstance(appContext).playbackStateDao()

    suspend fun getPlaybackState(videoPath: String): PlaybackStateEntity? = withContext(ioDispatcher) {
        try {
            resolveDao().getById(videoPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read playback state", e)
            null
        }
    }

    suspend fun updatePosition(videoPath: String, positionSec: Double) {
        updateField(videoPath) { current ->
            current.copy(positionSec = positionSec)
        }
    }

    suspend fun updateAudioTrack(videoPath: String, audioTrackId: Int?) {
        updateField(videoPath) { current ->
            current.copy(audioTrackId = audioTrackId)
        }
    }

    suspend fun updateSubtitleTrack(videoPath: String, subtitleTrackId: Int?) {
        updateField(videoPath) { current ->
            current.copy(subtitleTrackId = subtitleTrackId)
        }
    }

    private suspend fun updateField(
        videoPath: String,
        transform: (PlaybackStateEntity) -> PlaybackStateEntity,
    ) = withContext(ioDispatcher) {
        writeMutex.withLock {
            try {
                val dao = resolveDao()
                val existing = dao.getById(videoPath) ?: PlaybackStateEntity(videoPath = videoPath)
                val updated = transform(existing).copy(lastUpdated = System.currentTimeMillis())
                dao.upsert(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist playback state", e)
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackStateRepository"
    }
}
