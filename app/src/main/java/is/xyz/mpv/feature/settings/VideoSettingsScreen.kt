package `is`.xyz.mpv.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.OrientationMode
import `is`.xyz.mpv.UserPreferencesRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSettingsScreen(
    preferencesRepository: UserPreferencesRepository,
    onBackClick: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val orientationMode by preferencesRepository.orientationMode.collectAsState(initial = OrientationMode.AUTO_BY_VIDEO)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video") },
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
                .padding(16.dp)
        ) {
            SettingsSection(title = "Orientation") {
                OrientationMode.entries.forEachIndexed { index, mode ->
                    val isSelected = mode == orientationMode
                    val label = when (mode) {
                        OrientationMode.AUTO_BY_VIDEO -> "Auto by video"
                        OrientationMode.SYSTEM -> "System"
                        OrientationMode.PORTRAIT -> "Portrait"
                        OrientationMode.LANDSCAPE -> "Landscape"
                    }

                    SettingsRow(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = {
                            coroutineScope.launch {
                                preferencesRepository.setOrientationMode(mode)
                            }
                        }
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        SettingsLabel(label, isSelected)
                    }

                    if (index != OrientationMode.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

