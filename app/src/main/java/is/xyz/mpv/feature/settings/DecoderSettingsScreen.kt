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
import `is`.xyz.mpv.DecoderMode
import `is`.xyz.mpv.UserPreferencesRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecoderSettingsScreen(
    preferencesRepository: UserPreferencesRepository,
    onBackClick: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val decoderMode by preferencesRepository.decoderMode.collectAsState(initial = DecoderMode.AUTO)

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
            SettingsSection(title = "Decoder") {
                DecoderMode.entries.forEachIndexed { index, mode ->
                    val isSelected = mode == decoderMode
                    val label = when (mode) {
                        DecoderMode.AUTO -> "Auto"
                        DecoderMode.HARDWARE -> "Hardware"
                        DecoderMode.SOFTWARE -> "Software"
                    }

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
                        SettingsLabel(label, isSelected)
                    }

                    if (index != DecoderMode.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

