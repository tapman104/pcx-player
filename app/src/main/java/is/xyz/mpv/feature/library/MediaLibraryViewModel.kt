package `is`.xyz.mpv.feature.library

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val videos: List<MediaFile> = emptyList(),
    val permissionStatus: PermissionStatus = PermissionStatus.DENIED,
)

class MediaLibraryViewModel(
    application: Application,
    private val repository: MediaStoreRepository = MediaStoreRepository(application),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MediaLibraryUiState())
    val uiState: StateFlow<MediaLibraryUiState> = _uiState.asStateFlow()

    private var queryJob: Job? = null

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

    fun loadVideos() {
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

        if (queryJob?.isActive == true) return

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

