package `is`.xyz.mpv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFilterSheet(
    visible: Boolean,
    filterState: LibraryFilterState,
    onFilterChange: (LibraryFilterState) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.62f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Filter & sort", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Choose how your library is organized",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close filter")
                }
            }

            FilterSectionTitle("Sort by")
            SortBy.entries.forEach { option ->
                FilterChoiceRow(option.label, filterState.sortBy == option) {
                    onFilterChange(filterState.copy(sortBy = option))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            FilterSectionTitle("Order")
            SortOrder.entries.forEach { option ->
                FilterChoiceRow(option.label, filterState.sortOrder == option) {
                    onFilterChange(filterState.copy(sortOrder = option))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            FilterSectionTitle("Duration")
            DurationFilter.entries.forEach { option ->
                FilterChoiceRow(option.label, filterState.durationFilter == option) {
                    onFilterChange(filterState.copy(durationFilter = option))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onFilterChange(LibraryFilterState()) }, enabled = !filterState.isDefault) {
                    Text("Reset")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun FilterChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
