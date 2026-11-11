package `is`.xyz.mpv.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import `is`.xyz.mpv.feature.library.MediaFile
import `is`.xyz.mpv.feature.library.MediaLibraryEvent
import `is`.xyz.mpv.feature.library.MediaLibraryViewModel
import `is`.xyz.mpv.feature.library.PermissionStatus
import kotlinx.coroutines.launch

enum class LibraryViewMode { GRID, LIST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenFileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    viewModel: MediaLibraryViewModel = viewModel(
        factory = MediaLibraryViewModel.factory(LocalContext.current)
    ),
) {
    val uiState by viewModel.uiState.collectAsState()

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    var filterState by rememberSaveable(stateSaver = LibraryFilterStateSaver) {
        mutableStateOf(LibraryFilterState())
    }
    var isFilterMenuOpen by remember { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableStateOf(LibraryViewMode.GRID) }
    var activeMenuVideo by remember { mutableStateOf<MediaFile?>(null) }
    var renameDialogVideoId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteDialogVideoId by rememberSaveable { mutableStateOf<Long?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    val filteredAndSortedVideos by remember {
        derivedStateOf { applyLibraryFilter(uiState.videos, filterState) }
    }

    val displayedVideos by remember {
        derivedStateOf {
            if (!isSearching || searchQuery.isBlank()) {
                filteredAndSortedVideos
            } else {
                filteredAndSortedVideos.filter {
                    it.displayName.contains(searchQuery, ignoreCase = true)
                }
            }
        }
    }

    val renameDialogVideo = renameDialogVideoId?.let { id ->
        uiState.videos.firstOrNull { it.id == id }
    }
    val deleteDialogVideo = deleteDialogVideoId?.let { id ->
        uiState.videos.firstOrNull { it.id == id }
    }

    fun closeSearch() {
        isSearching = false
        searchQuery = ""
    }

    BackHandler(enabled = isSearching) {
        closeSearch()
    }

    val permissionsToRequest = remember {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            }

            else -> {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.loadVideos()
    }

    val copyFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        viewModel.onCopyDestinationSelected(treeUri)
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.onIntentSenderResult(result.resultCode == Activity.RESULT_OK)
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onWriteStoragePermissionResult(granted)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MediaLibraryEvent.LaunchIntentSender -> {
                    try {
                        intentSenderLauncher.launch(
                            IntentSenderRequest.Builder(event.intentSender).build()
                        )
                    } catch (e: Exception) {
                        viewModel.onExternalActionError("Unable to open system permission dialog")
                    }
                }

                is MediaLibraryEvent.RequestWriteStoragePermission -> {
                    writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }

                is MediaLibraryEvent.ShowMessage -> {
                    launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(event.message)
                    }
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 84.dp),
            )
        },
        topBar = {
            if (isSearching) {
                TopAppBar(
                    title = {
                        LibrarySearchField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onClose = { closeSearch() },
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = "Library",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    actions = {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search",
                            )
                        }
                        Box {
                            IconButton(onClick = { isFilterMenuOpen = true }) {
                                Icon(
                                    imageVector = Icons.Filled.FilterList,
                                    contentDescription = "Filter and sort",
                                    tint = if (!filterState.isDefault) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onBackground
                                    },
                                )
                            }
                            LibraryFilterMenu(
                                expanded = isFilterMenuOpen,
                                filterState = filterState,
                                onFilterChange = { filterState = it },
                                onDismiss = { isFilterMenuOpen = false },
                            )
                        }
                        IconButton(
                            onClick = {
                                viewMode = if (viewMode == LibraryViewMode.GRID) {
                                    LibraryViewMode.LIST
                                } else {
                                    LibraryViewMode.GRID
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (viewMode == LibraryViewMode.GRID) {
                                    Icons.AutoMirrored.Filled.ViewList
                                } else {
                                    Icons.Filled.GridView
                                },
                                contentDescription = if (viewMode == LibraryViewMode.GRID) {
                                    "Switch to list view"
                                } else {
                                    "Switch to grid view"
                                },
                            )
                        }
                        IconButton(onClick = onOpenFileClick) {
                            Icon(
                                imageVector = Icons.Filled.FileOpen,
                                contentDescription = "Open video",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.permissionStatus == PermissionStatus.DENIED -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Permission Required",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Allow access to your video library to view and play videos.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { permissionLauncher.launch(permissionsToRequest) }) {
                            Text("Grant Permission")
                        }
                    }
                }

                uiState.permissionStatus == PermissionStatus.PARTIAL && uiState.videos.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VideoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Limited Access Granted",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No videos are currently selected. Grant full access or select videos to display.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { permissionLauncher.launch(permissionsToRequest) }) {
                            Text("Select Videos")
                        }
                    }
                }

                uiState.videos.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VideoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No videos found",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Videos stored on this device will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                uiState.videos.isNotEmpty() && displayedVideos.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "No matching videos",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                else -> {
                    val contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 96.dp,
                    )
                    val handleCopy: (MediaFile) -> Unit = { video ->
                        activeMenuVideo = null
                        if (viewModel.prepareCopy(video)) {
                            try {
                                copyFolderLauncher.launch(null)
                            } catch (e: Exception) {
                                viewModel.onExternalActionError("No folder picker available on this device")
                            }
                        }
                    }
                    val handleRename: (MediaFile) -> Unit = { video ->
                        activeMenuVideo = null
                        renameDialogVideoId = video.id
                    }
                    val handleDelete: (MediaFile) -> Unit = { video ->
                        activeMenuVideo = null
                        // On Android 11+ (API 30+), MediaStore.createDeleteRequest shows the system
                        // confirmation dialog directly, so only show an in-app confirmation on API < 30.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            viewModel.deleteVideo(video)
                        } else {
                            deleteDialogVideoId = video.id
                        }
                    }
                    when (viewMode) {
                        LibraryViewMode.GRID -> VideoGrid(
                            videos = displayedVideos,
                            menuVideo = activeMenuVideo,
                            onVideoClick = onVideoClick,
                            onMenuOpen = { activeMenuVideo = it },
                            onMenuDismiss = { activeMenuVideo = null },
                            onCopy = handleCopy,
                            onRename = handleRename,
                            onDelete = handleDelete,
                            contentPadding = contentPadding,
                        )

                        LibraryViewMode.LIST -> VideoList(
                            videos = displayedVideos,
                            menuVideo = activeMenuVideo,
                            onVideoClick = onVideoClick,
                            onMenuOpen = { activeMenuVideo = it },
                            onMenuDismiss = { activeMenuVideo = null },
                            onCopy = handleCopy,
                            onRename = handleRename,
                            onDelete = handleDelete,
                            contentPadding = contentPadding,
                        )
                    }
                }
            }

            if (uiState.isOperating) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }

            FloatingPillNav(
                selectedTab = 0,
                onHomeClick = { /* Already on Home */ },
                onSettingsClick = onSettingsClick,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (renameDialogVideo != null) {
        RenameVideoDialog(
            mediaFile = renameDialogVideo,
            onDismiss = { renameDialogVideoId = null },
            onConfirm = { newFullName ->
                renameDialogVideoId = null
                viewModel.renameVideo(renameDialogVideo, newFullName)
            },
        )
    }

    if (deleteDialogVideo != null) {
        DeleteConfirmDialog(
            mediaFile = deleteDialogVideo,
            onDismiss = { deleteDialogVideoId = null },
            onConfirm = {
                deleteDialogVideoId = null
                viewModel.deleteVideo(deleteDialogVideo)
            },
        )
    }
}

@Composable
private fun RenameVideoDialog(
    mediaFile: MediaFile,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val (initialBaseName, extension) = remember(mediaFile.id, mediaFile.displayName) {
        splitFileName(mediaFile.displayName)
    }
    var nameInput by remember(mediaFile.id, mediaFile.displayName) {
        mutableStateOf(initialBaseName)
    }

    val trimmed = nameInput.trim()
    val hasInvalidChars = trimmed.any { it == '/' || it == '\\' || it == '\u0000' } ||
        trimmed == "." || trimmed == ".."
    val newFullName = trimmed + extension
    val isUnchanged = newFullName == mediaFile.displayName
    val isError = hasInvalidChars || (nameInput.isNotEmpty() && trimmed.isEmpty())
    val canSubmit = trimmed.isNotEmpty() && !hasInvalidChars && !isUnchanged

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename video") },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("File name") },
                suffix = if (extension.isNotEmpty()) {
                    { Text(extension) }
                } else {
                    null
                },
                singleLine = true,
                isError = isError,
                supportingText = if (hasInvalidChars) {
                    { Text("File name contains invalid characters") }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newFullName) },
                enabled = canSubmit,
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DeleteConfirmDialog(
    mediaFile: MediaFile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete video?") },
        text = {
            Text("Delete \"${mediaFile.displayName}\"? This will permanently remove the video from your device.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun splitFileName(displayName: String): Pair<String, String> {
    val dotIndex = displayName.lastIndexOf('.')
    return if (dotIndex > 0 && dotIndex < displayName.length - 1) {
        displayName.substring(0, dotIndex) to displayName.substring(dotIndex)
    } else {
        displayName to ""
    }
}

@Composable
private fun FloatingPillNav(
    selectedTab: Int,
    onHomeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = if (selectedTab == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                modifier = Modifier.clickable(onClick = onHomeClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "Home",
                        tint = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Home",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = if (selectedTab == 1) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                modifier = Modifier.clickable(onClick = onSettingsClick),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}