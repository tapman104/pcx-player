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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.DecoderMode
import `is`.xyz.mpv.MpvBuiltInProfiles
import `is`.xyz.mpv.RendererMode
import `is`.xyz.mpv.UserPreferencesRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecoderSettingsScreen(
    preferencesRepository: UserPreferencesRepository,
    onBackClick: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val playbackProfileId by preferencesRepository.playbackProfileId.collectAsState(initial = MpvBuiltInProfiles.MPV_DEFAULT.id)
    val rendererMode by preferencesRepository.rendererMode.collectAsState(initial = RendererMode.GPU)
    val decoderMode by preferencesRepository.decoderMode.collectAsState(initial = DecoderMode.HW_PLUS)
    val fastDecode by preferencesRepository.fastDecode.collectAsState(initial = false)
    val debanding by preferencesRepository.debanding.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Decoder") },
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
            SettingsSection(title = "Playback Profile") {
                MpvBuiltInProfiles.ALL.forEachIndexed { index, profile ->
                    val isSelected = profile.id == playbackProfileId

                    SettingsRow(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = {
                            coroutineScope.launch {
                                preferencesRepository.setPlaybackProfileId(profile.id)
                            }
                        }
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            SettingsLabel(profile.name, isSelected)
                            Text(
                                text = profile.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (index != MpvBuiltInProfiles.ALL.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            Text(
                text = "Profile selection takes effect on the next player initialization or restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 16.dp)
            )

            SettingsSection(title = "Video Renderer") {
                RendererMode.entries.forEachIndexed { index, mode ->
                    val isSelected = mode == rendererMode

                    SettingsRow(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = {
                            coroutineScope.launch {
                                preferencesRepository.setRendererMode(mode)
                            }
                        }
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        SettingsLabel(mode.displayName, isSelected)
                    }

                    if (index != RendererMode.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            Text(
                text = "Renderer selection takes effect on the next player initialization or restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 16.dp)
            )

            SettingsSection(title = "Decoder") {
                DecoderMode.entries.forEachIndexed { index, mode ->
                    val isSelected = mode == decoderMode

                    SettingsRow(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = {
                            coroutineScope.launch {
                                preferencesRepository.setDecoderMode(mode)
                            }
                        }
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        SettingsLabel(mode.displayName, isSelected)
                    }

                    if (index != DecoderMode.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            Text(
                text = "Decoder selection takes effect on the next player initialization or restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 16.dp)
            )

            SettingsSection(title = "Fast Decode") {
                SettingsRow(
                    selected = fastDecode,
                    role = Role.Switch,
                    onClick = {
                        coroutineScope.launch {
                            preferencesRepository.setFastDecode(!fastDecode)
                        }
                    }
                ) {
                    Switch(checked = fastDecode, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        SettingsLabel("Enable fast decode", fastDecode)
                        Text(
                            text = "Reduces decoding precision and skips loop filters for faster decoding (vd-lavc-fast)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = "Fast decode takes effect on the next player initialization or restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 16.dp)
            )

            SettingsSection(title = "Debanding") {
                SettingsRow(
                    selected = debanding,
                    role = Role.Switch,
                    onClick = {
                        coroutineScope.launch {
                            preferencesRepository.setDebanding(!debanding)
                        }
                    }
                ) {
                    Switch(checked = debanding, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        SettingsLabel("Enable debanding (GPU)", debanding)
                        Text(
                            text = "Reduces video color banding artifacts on gradients (deband=yes)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = "Debanding takes effect on the next player initialization or restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 16.dp)
            )
        }
    }
}
