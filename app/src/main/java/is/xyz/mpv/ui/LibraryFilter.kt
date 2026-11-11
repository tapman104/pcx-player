package `is`.xyz.mpv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.feature.library.MediaFile

enum class SortBy(val label: String) {
    NAME("Name"),
    DATE_ADDED("Date added"),
    DURATION("Duration"),
}

enum class SortOrder(val label: String) {
    ASCENDING("Ascending"),
    DESCENDING("Descending"),
}

enum class DurationFilter(val label: String) {
    ALL("All videos"),
    UNDER_5_MIN("Under 5 minutes"),
    FIVE_TO_30_MIN("5–30 minutes"),
    OVER_30_MIN("Over 30 minutes"),
}

/**
 * Current filter/sort selection. Defaults are the "Reset" state.
 */
data class LibraryFilterState(
    val sortBy: SortBy = SortBy.DATE_ADDED,
    val sortOrder: SortOrder = SortOrder.DESCENDING,
    val durationFilter: DurationFilter = DurationFilter.ALL,
) {
    val isDefault: Boolean
        get() = this == LibraryFilterState()
}

/**
 * Saves [LibraryFilterState] as a 3-string list so `rememberSaveable` can
 * survive config changes (rotation, process death) without needing
 * Parcelable.
 */
val LibraryFilterStateSaver: Saver<LibraryFilterState, List<String>> = Saver(
    save = { listOf(it.sortBy.name, it.sortOrder.name, it.durationFilter.name) },
    restore = { saved ->
        LibraryFilterState(
            sortBy = SortBy.valueOf(saved[0]),
            sortOrder = SortOrder.valueOf(saved[1]),
            durationFilter = DurationFilter.valueOf(saved[2]),
        )
    },
)

private const val FIVE_MIN_MS = 5 * 60 * 1000L
private const val THIRTY_MIN_MS = 30 * 60 * 1000L

/**
 * Pure transform: applies duration filter then sort. Does not touch search —
 * callers run search on top of this result.
 * Videos with missing/zero duration (durationMs <= 0) are treated as
 * "unknown duration": excluded from the two bucketed duration filters
 * (Under 5 min / 5–30 min / Over 30 min) since we can't know where they
 * belong, but always included under "All videos", and sorted to the end
 * when sorting by Duration (ascending or descending).
 */
fun applyLibraryFilter(videos: List<MediaFile>, filter: LibraryFilterState): List<MediaFile> {
    val durationFiltered = when (filter.durationFilter) {
        DurationFilter.ALL -> videos
        DurationFilter.UNDER_5_MIN -> videos.filter { it.durationMs > 0 && it.durationMs < FIVE_MIN_MS }
        DurationFilter.FIVE_TO_30_MIN -> videos.filter { it.durationMs in FIVE_MIN_MS..THIRTY_MIN_MS }
        DurationFilter.OVER_30_MIN -> videos.filter { it.durationMs > THIRTY_MIN_MS }
    }

    val comparator = when (filter.sortBy) {
        SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { video: MediaFile -> video.displayName }
        SortBy.DATE_ADDED -> compareBy { video: MediaFile -> video.dateAdded }
        SortBy.DURATION -> compareBy { video: MediaFile -> video.durationMs <= 0 }
            .thenBy { video: MediaFile -> video.durationMs }
    }

    val sorted = durationFiltered.sortedWith(comparator)
    return if (filter.sortOrder == SortOrder.DESCENDING) sorted.reversed() else sorted
}

@Composable
fun LibraryFilterMenu(
    expanded: Boolean,
    filterState: LibraryFilterState,
    onFilterChange: (LibraryFilterState) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        Text(
            text = "Sort by",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SortBy.entries.forEach { option ->
            FilterMenuRow(
                label = option.label,
                selected = filterState.sortBy == option,
                onClick = { onFilterChange(filterState.copy(sortBy = option)) },
            )
        }

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        Text(
            text = "Sort order",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SortOrder.entries.forEach { option ->
            FilterMenuRow(
                label = option.label,
                selected = filterState.sortOrder == option,
                onClick = { onFilterChange(filterState.copy(sortOrder = option)) },
            )
        }

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        Text(
            text = "Duration",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        DurationFilter.entries.forEach { option ->
            FilterMenuRow(
                label = option.label,
                selected = filterState.durationFilter == option,
                onClick = { onFilterChange(filterState.copy(durationFilter = option)) },
            )
        }

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        DropdownMenuItem(
            text = {
                Text(
                    text = "Reset",
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            onClick = { onFilterChange(LibraryFilterState()) },
        )
    }
}

@Composable
private fun FilterMenuRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.width(20.dp)) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(18.dp),
                        )
                    }
                }
                Text(
                    text = label,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        },
        onClick = onClick,
    )
}