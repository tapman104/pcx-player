package `is`.xyz.mpv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import `is`.xyz.mpv.feature.library.MediaFile

@Composable
fun VideoActionsMenu(
    mediaFile: MediaFile,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: (MediaFile) -> Unit,
    onRename: (MediaFile) -> Unit,
    onDelete: (MediaFile) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text("Copy") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                )
            },
            onClick = {
                onDismiss()
                onCopy(mediaFile)
            },
        )
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                )
            },
            onClick = {
                onDismiss()
                onRename(mediaFile)
            },
        )
        DropdownMenuItem(
            text = {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                onDismiss()
                onDelete(mediaFile)
            },
        )
    }
}

