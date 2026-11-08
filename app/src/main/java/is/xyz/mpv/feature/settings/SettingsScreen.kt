package `is`.xyz.mpv.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.UserPreferencesRepository

sealed interface SettingsRoute {
    data object Hub : SettingsRoute
    data object General : SettingsRoute
    data object Audio : SettingsRoute
    data object Video : SettingsRoute
    data object Subtitles : SettingsRoute
    data object Decoder : SettingsRoute
    data object AppLogViewer : SettingsRoute
    data object About : SettingsRoute
    data object Libraries : SettingsRoute
}

@Composable
fun SettingsScreen(
    preferencesRepository: UserPreferencesRepository,
    onBackClick: () -> Unit,
) {
    var currentRoute by remember { mutableStateOf<SettingsRoute>(SettingsRoute.Hub) }

    BackHandler(enabled = currentRoute != SettingsRoute.Hub) {
        currentRoute = when (currentRoute) {
            SettingsRoute.Libraries -> SettingsRoute.About
            else -> SettingsRoute.Hub
        }
    }

    when (currentRoute) {
        SettingsRoute.Hub -> {
            SettingsHubContent(
                onNavigate = { route -> currentRoute = route },
                onBackClick = onBackClick
            )
        }
        SettingsRoute.General -> {
            GeneralSettingsScreen(
                preferencesRepository = preferencesRepository,
                onBackClick = { currentRoute = SettingsRoute.Hub }
            )
        }
        SettingsRoute.Audio -> {
            AudioSettingsScreen(
                preferencesRepository = preferencesRepository,
                onBackClick = { currentRoute = SettingsRoute.Hub }
            )
        }
        SettingsRoute.Video -> {
            VideoSettingsScreen(
                preferencesRepository = preferencesRepository,
                onBackClick = { currentRoute = SettingsRoute.Hub }
            )
        }
        SettingsRoute.Subtitles -> {
            SubtitleSettingsScreen(
                preferencesRepository = preferencesRepository,
                onBackClick = { currentRoute = SettingsRoute.Hub }
            )
        }
        SettingsRoute.Decoder -> {
            DecoderSettingsScreen(
                preferencesRepository = preferencesRepository,
                onBackClick = { currentRoute = SettingsRoute.Hub }
            )
        }
        SettingsRoute.AppLogViewer -> {
            AppLogViewerScreen(
                onBackClick = { currentRoute = SettingsRoute.Hub }
            )
        }
        SettingsRoute.About -> {
            AboutScreen(
                onBackClick = { currentRoute = SettingsRoute.Hub },
                onNavigateToLibraries = { currentRoute = SettingsRoute.Libraries }
            )
        }
        SettingsRoute.Libraries -> {
            LibrariesScreen(
                onBackClick = { currentRoute = SettingsRoute.About }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHubContent(
    onNavigate: (SettingsRoute) -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            CategoryRow(
                icon = Icons.Filled.Palette,
                title = "General",
                onClick = { onNavigate(SettingsRoute.General) }
            )
            CategoryRow(
                icon = Icons.Filled.Audiotrack,
                title = "Audio",
                onClick = { onNavigate(SettingsRoute.Audio) }
            )
            CategoryRow(
                icon = Icons.Filled.PlayArrow,
                title = "Video",
                onClick = { onNavigate(SettingsRoute.Video) }
            )
            CategoryRow(
                icon = Icons.Filled.ClosedCaption,
                title = "Subtitles",
                onClick = { onNavigate(SettingsRoute.Subtitles) }
            )
            CategoryRow(
                icon = Icons.Filled.Memory,
                title = "Decoder",
                onClick = { onNavigate(SettingsRoute.Decoder) }
            )
            CategoryRow(
                icon = Icons.AutoMirrored.Filled.Article,
                title = "App Logs",
                onClick = { onNavigate(SettingsRoute.AppLogViewer) }
            )
            CategoryRow(
                icon = Icons.Filled.Info,
                title = "About",
                onClick = { onNavigate(SettingsRoute.About) }
            )
        }
    }
}

@Composable
private fun CategoryRow(
    title: String,
    icon: ImageVector = when (title) {
        "General" -> Icons.Filled.Palette
        "Audio" -> Icons.Filled.Audiotrack
        "Video" -> Icons.Filled.PlayArrow
        "Subtitles" -> Icons.Filled.ClosedCaption
        "Decoder" -> Icons.Filled.Memory
        "App Logs" -> Icons.AutoMirrored.Filled.Article
        "About" -> Icons.Filled.Info
        else -> Icons.Filled.Info
    },
    onClick: () -> Unit,
    subtitle: String = "",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Groups related settings rows under a titled, elevated card so sections
 * read as distinct blocks instead of one continuous list.
 */
@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
fun SettingsRow(
    selected: Boolean,
    role: Role,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = role)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun SettingsLabel(text: String, emphasized: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    )
}

