package `is`.xyz.mpv.feature.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.feature.settings.SettingsLabel

data class LanguageOption(val code: String?, val displayName: String)

val LANGUAGE_CATALOG = listOf(
    LanguageOption(null, "System default"),
    LanguageOption("en", "English"),
    LanguageOption("es", "Spanish"),
    LanguageOption("fr", "French"),
    LanguageOption("de", "German"),
    LanguageOption("it", "Italian"),
    LanguageOption("pt", "Portuguese"),
    LanguageOption("ru", "Russian"),
    LanguageOption("ja", "Japanese"),
    LanguageOption("ko", "Korean"),
    LanguageOption("zh", "Chinese"),
    LanguageOption("ar", "Arabic"),
    LanguageOption("he", "Hebrew"),
    LanguageOption("fa", "Persian"),
    LanguageOption("tr", "Turkish"),
    LanguageOption("nl", "Dutch"),
    LanguageOption("pl", "Polish"),
    LanguageOption("sv", "Swedish"),
    LanguageOption("no", "Norwegian"),
    LanguageOption("da", "Danish"),
    LanguageOption("fi", "Finnish"),
    LanguageOption("el", "Greek"),
    LanguageOption("uk", "Ukrainian"),
    LanguageOption("cs", "Czech"),
    LanguageOption("ro", "Romanian"),
    LanguageOption("hu", "Hungarian"),
    LanguageOption("th", "Thai"),
    LanguageOption("vi", "Vietnamese"),
    LanguageOption("id", "Indonesian"),
    LanguageOption("ms", "Malay"),
    LanguageOption("tl", "Tagalog"),
    LanguageOption("hi", "Hindi"),
    LanguageOption("bn", "Bengali"),
    LanguageOption("pa", "Punjabi"),
    LanguageOption("mr", "Marathi"),
    LanguageOption("gu", "Gujarati"),
    LanguageOption("ur", "Urdu"),
    LanguageOption("ml", "Malayalam"),
    LanguageOption("ta", "Tamil"),
    LanguageOption("te", "Telugu"),
    LanguageOption("kn", "Kannada"),
)

@Composable
fun LanguagePickerDialog(
    title: String,
    currentSelection: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var selectedCode by remember { mutableStateOf(currentSelection) }
    var searchQuery by remember { mutableStateOf("") }

    val systemDefault = LANGUAGE_CATALOG.first()
    val filteredOthers = remember(searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            LANGUAGE_CATALOG.drop(1)
        } else {
            LANGUAGE_CATALOG.drop(1).filter {
                it.displayName.contains(query, ignoreCase = true)
            }
        }
    }
    val displayedOptions = remember(filteredOthers) {
        listOf(systemDefault) + filteredOthers
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search language") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                ) {
                    items(
                        displayedOptions,
                        key = { it.code ?: "system_default" }
                    ) { option ->
                        val isSelected = option.code == selectedCode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    onClick = { selectedCode = option.code },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            SettingsLabel(option.displayName, isSelected)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedCode) }) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

