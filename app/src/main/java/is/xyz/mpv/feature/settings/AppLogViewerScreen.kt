package `is`.xyz.mpv.feature.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.AppLogger
import `is`.xyz.mpv.LogEntry
import `is`.xyz.mpv.LogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogFilter(val label: String) {
    ALL("All"),
    APP("App"),
    MPV("MPV"),
    APP_ERRORS("App errors"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogViewerScreen(
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf(LogFilter.ALL) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    val filteredLogs = remember(logs, selectedFilter) {
        when (selectedFilter) {
            LogFilter.ALL -> logs
            LogFilter.APP -> logs.filter { !it.tag.startsWith("mpv:", ignoreCase = true) }
            LogFilter.MPV -> logs.filter { it.tag.startsWith("mpv:", ignoreCase = true) }
            LogFilter.APP_ERRORS -> logs.filter {
                !it.tag.startsWith("mpv:", ignoreCase = true) && it.severity == LogSeverity.ERROR
            }
        }
    }
    var showClearDialog by remember { mutableStateOf(false) }

    // Polling runs only while AppLogViewerScreen is in composition.
    // When the user navigates back to SettingsHub or leaves the screen,
    // the coroutine launched by LaunchedEffect(Unit) is automatically cancelled.
    LaunchedEffect(Unit) {
        while (true) {
            val previewLogs = withContext(Dispatchers.IO) {
                AppLogger.loadLogPreview()
            }
            if (previewLogs != logs) {
                logs = previewLogs
            }
            delay(1000L)
        }
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val content = AppLogger.formatLogsForExport()
                    val outputStream = context.contentResolver.openOutputStream(uri)
                    if (outputStream == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to open file for writing", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    outputStream.use { stream ->
                        stream.bufferedWriter().use { writer ->
                            writer.write(content)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Logs saved successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error saving logs: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear logs") },
            text = { Text("Are you sure you want to clear all logs?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            AppLogger.clear()
                            withContext(Dispatchers.Main) {
                                logs = emptyList()
                                showClearDialog = false
                            }
                        }
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Logs") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val content = AppLogger.formatLogsForExport()
                                withContext(Dispatchers.Main) {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, content)
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, "Share Logs")
                                    context.startActivity(shareIntent)
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Share logs"
                        )
                    }
                    Box {
                        IconButton(onClick = { filterMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.FilterList,
                                contentDescription = "Filter logs"
                            )
                        }
                        DropdownMenu(
                            expanded = filterMenuExpanded,
                            onDismissRequest = { filterMenuExpanded = false }
                        ) {
                            LogFilter.entries.forEach { filter ->
                                DropdownMenuItem(
                                    text = { Text(filter.label) },
                                    onClick = {
                                        selectedFilter = filter
                                        filterMenuExpanded = false
                                    },
                                    leadingIcon = if (selectedFilter == filter) {
                                        {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = null
                                            )
                                        }
                                    } else null
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            saveDocumentLauncher.launch("potato_player_logs_$timeStamp.txt")
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SaveAlt,
                            contentDescription = "Save logs"
                        )
                    }
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Clear logs"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No logs recorded",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No logs match this filter",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredLogs, key = { it.id }) { logEntry ->
                    LogEntryRow(entry = logEntry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val timeStr = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
    }
    val severityColor = when (entry.severity) {
        LogSeverity.ERROR -> MaterialTheme.colorScheme.error
        LogSeverity.WARN -> Color(0xFFE65100)
        LogSeverity.INFO -> MaterialTheme.colorScheme.primary
        LogSeverity.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.severity.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = severityColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = entry.tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

