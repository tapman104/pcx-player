package `is`.xyz.mpv.feature.library

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PermissionStatus {
    GRANTED,
    PARTIAL,
    DENIED,
}

data class MediaLibraryUiState(
    val isLoading: Boolean = false,
    val isOperating: Boolean = false,
    val videos: List<MediaFile> = emptyList(),
    val permissionStatus: PermissionStatus = PermissionStatus.DENIED,
)

sealed interface MediaLibraryEvent {
    data class LaunchIntentSender(val intentSender: IntentSender) : MediaLibraryEvent
    data object RequestWriteStoragePermission : MediaLibraryEvent
    data class ShowMessage(val message: String) : MediaLibraryEvent
}

class MediaLibraryViewModel(
    application: Application,
    private val repository: MediaStoreRepository = MediaStoreRepository(application),
) : AndroidViewModel(application) {

    private sealed interface PendingOperation {
        data class Rename(val mediaFile: MediaFile, val newDisplayName: String) : PendingOperation
        data class Delete(val mediaFile: MediaFile, val isSystemHandled: Boolean) : PendingOperation
    }

    private val _uiState = MutableStateFlow(MediaLibraryUiState())
    val uiState: StateFlow<MediaLibraryUiState> = _uiState.asStateFlow()

    private val _events = Channel<MediaLibraryEvent>(Channel.BUFFERED)
    val events: Flow<MediaLibraryEvent> = _events.receiveAsFlow()

    private var queryJob: Job? = null
    private var pendingCopyVideo: MediaFile? = null
    private var pendingOperation: PendingOperation? = null

    init {
        loadVideos()
    }

    fun checkPermission(): PermissionStatus {
        val context = getApplication<Application>()
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                when {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_MEDIA_VIDEO
                    ) == PackageManager.PERMISSION_GRANTED -> PermissionStatus.GRANTED

                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    ) == PackageManager.PERMISSION_GRANTED -> PermissionStatus.PARTIAL

                    else -> PermissionStatus.DENIED
                }
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_MEDIA_VIDEO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    PermissionStatus.GRANTED
                } else {
                    PermissionStatus.DENIED
                }
            }

            else -> {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    PermissionStatus.GRANTED
                } else {
                    PermissionStatus.DENIED
                }
            }
        }
    }

    fun onResume() {
        loadVideos()
    }

    fun refreshVideos() {
        loadVideos(forceRefresh = true)
    }

    fun loadVideos(forceRefresh: Boolean = false) {
        val permission = checkPermission()
        if (permission == PermissionStatus.DENIED) {
            queryJob?.cancel()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    videos = emptyList(),
                    permissionStatus = PermissionStatus.DENIED,
                )
            }
            return
        }

        if (!forceRefresh && queryJob?.isActive == true) return
        if (forceRefresh) {
            queryJob?.cancel()
        }

        queryJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = it.videos.isEmpty(),
                    permissionStatus = permission,
                )
            }
            val list = withContext(Dispatchers.IO) {
                repository.getVideos()
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    videos = list,
                    permissionStatus = permission,
                )
            }
        }
    }

    fun prepareCopy(mediaFile: MediaFile): Boolean {
        if (_uiState.value.isOperating) {
            viewModelScope.launch {
                _events.send(MediaLibraryEvent.ShowMessage("Another operation is in progress"))
            }
            return false
        }
        pendingCopyVideo = mediaFile
        return true
    }

    fun onCopyDestinationSelected(treeUri: Uri?) {
        val mediaFile = pendingCopyVideo
        pendingCopyVideo = null
        if (mediaFile == null || treeUri == null) return
        if (_uiState.value.isOperating) return

        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            val result = try {
                repository.copyVideoToTree(mediaFile, treeUri)
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }

            when (result) {
                is MediaOperationResult.Success -> {
                    refreshVideos()
                    _events.send(MediaLibraryEvent.ShowMessage("Copied \"${mediaFile.displayName}\""))
                }
                is MediaOperationResult.Failure -> {
                    _events.send(MediaLibraryEvent.ShowMessage(result.message))
                }
                is MediaOperationResult.WritePermissionRequired,
                is MediaOperationResult.DeleteConfirmationRequired,
                is MediaOperationResult.StoragePermissionRequired -> Unit
            }
        }
    }

    fun renameVideo(mediaFile: MediaFile, newDisplayName: String) {
        if (_uiState.value.isOperating) {
            viewModelScope.launch {
                _events.send(MediaLibraryEvent.ShowMessage("Another operation is in progress"))
            }
            return
        }
        executeRename(mediaFile, newDisplayName, allowPermissionPrompt = true)
    }

    fun deleteVideo(mediaFile: MediaFile) {
        if (_uiState.value.isOperating) {
            viewModelScope.launch {
                _events.send(MediaLibraryEvent.ShowMessage("Another operation is in progress"))
            }
            return
        }
        executeDelete(mediaFile, allowPermissionPrompt = true)
    }

    fun onIntentSenderResult(granted: Boolean) {
        val pending = pendingOperation
        pendingOperation = null
        if (pending == null) return

        if (!granted) {
            if (pending is PendingOperation.Rename ||
                (pending is PendingOperation.Delete && !pending.isSystemHandled)
            ) {
                viewModelScope.launch {
                    _events.send(MediaLibraryEvent.ShowMessage("Permission denied"))
                }
            }
            return
        }

        when (pending) {
            is PendingOperation.Rename -> {
                executeRename(
                    mediaFile = pending.mediaFile,
                    newDisplayName = pending.newDisplayName,
                    allowPermissionPrompt = false,
                )
            }

            is PendingOperation.Delete -> {
                if (pending.isSystemHandled) {
                    refreshVideos()
                    viewModelScope.launch {
                        _events.send(
                            MediaLibraryEvent.ShowMessage("Deleted \"${pending.mediaFile.displayName}\"")
                        )
                    }
                } else {
                    executeDelete(
                        mediaFile = pending.mediaFile,
                        allowPermissionPrompt = false,
                    )
                }
            }
        }
    }

    fun onWriteStoragePermissionResult(granted: Boolean) {
        val pending = pendingOperation
        pendingOperation = null
        if (pending == null) return

        if (!granted) {
            viewModelScope.launch {
                _events.send(
                    MediaLibraryEvent.ShowMessage("Storage write permission is required")
                )
            }
            return
        }

        when (pending) {
            is PendingOperation.Rename -> {
                executeRename(
                    mediaFile = pending.mediaFile,
                    newDisplayName = pending.newDisplayName,
                    allowPermissionPrompt = false,
                )
            }

            is PendingOperation.Delete -> {
                executeDelete(
                    mediaFile = pending.mediaFile,
                    allowPermissionPrompt = false,
                )
            }
        }
    }

    fun onExternalActionError(message: String) {
        pendingCopyVideo = null
        pendingOperation = null
        viewModelScope.launch {
            _events.send(MediaLibraryEvent.ShowMessage(message))
        }
    }

    private fun executeRename(
        mediaFile: MediaFile,
        newDisplayName: String,
        allowPermissionPrompt: Boolean,
    ) {
        val trimmedName = newDisplayName.trim()
        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            val result = try {
                repository.renameVideo(
                    mediaFile = mediaFile,
                    newDisplayName = trimmedName,
                    allowPermissionPrompt = allowPermissionPrompt,
                )
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }

            when (result) {
                is MediaOperationResult.Success -> {
                    refreshVideos()
                    _events.send(MediaLibraryEvent.ShowMessage("Renamed to \"$trimmedName\""))
                }

                is MediaOperationResult.WritePermissionRequired -> {
                    pendingOperation = PendingOperation.Rename(mediaFile, trimmedName)
                    _events.send(MediaLibraryEvent.LaunchIntentSender(result.intentSender))
                }

                is MediaOperationResult.StoragePermissionRequired -> {
                    pendingOperation = PendingOperation.Rename(mediaFile, trimmedName)
                    _events.send(MediaLibraryEvent.RequestWriteStoragePermission)
                }

                is MediaOperationResult.Failure -> {
                    _events.send(MediaLibraryEvent.ShowMessage(result.message))
                }

                is MediaOperationResult.DeleteConfirmationRequired -> Unit
            }
        }
    }

    private fun executeDelete(
        mediaFile: MediaFile,
        allowPermissionPrompt: Boolean,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            val result = try {
                repository.deleteVideo(
                    mediaFile = mediaFile,
                    allowPermissionPrompt = allowPermissionPrompt,
                )
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }

            when (result) {
                is MediaOperationResult.Success -> {
                    refreshVideos()
                    _events.send(MediaLibraryEvent.ShowMessage("Deleted \"${mediaFile.displayName}\""))
                }

                is MediaOperationResult.DeleteConfirmationRequired -> {
                    pendingOperation = PendingOperation.Delete(mediaFile, isSystemHandled = true)
                    _events.send(MediaLibraryEvent.LaunchIntentSender(result.intentSender))
                }

                is MediaOperationResult.WritePermissionRequired -> {
                    pendingOperation = PendingOperation.Delete(mediaFile, isSystemHandled = false)
                    _events.send(MediaLibraryEvent.LaunchIntentSender(result.intentSender))
                }

                is MediaOperationResult.StoragePermissionRequired -> {
                    pendingOperation = PendingOperation.Delete(mediaFile, isSystemHandled = false)
                    _events.send(MediaLibraryEvent.RequestWriteStoragePermission)
                }

                is MediaOperationResult.Failure -> {
                    _events.send(MediaLibraryEvent.ShowMessage(result.message))
                }
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MediaLibraryViewModel(
                        application = context.applicationContext as Application,
                        repository = MediaStoreRepository(context.applicationContext),
                    ) as T
            }
    }
}
