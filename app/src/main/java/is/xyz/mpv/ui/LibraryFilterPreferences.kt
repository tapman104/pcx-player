package `is`.xyz.mpv.ui

import android.content.Context

/** Persists the library-wide filter/sort selection across app launches. */
internal class LibraryFilterPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): LibraryFilterState {
        val sortBy = preferences.getString(KEY_SORT_BY, null)
            ?.let { runCatching { SortBy.valueOf(it) }.getOrNull() }
            ?: SortBy.DATE_ADDED

        val sortOrder = preferences.getString(KEY_SORT_ORDER, null)
            ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
            ?: SortOrder.DESCENDING

        val durationFilter = preferences.getString(KEY_DURATION_FILTER, null)
            ?.let { runCatching { DurationFilter.valueOf(it) }.getOrNull() }
            ?: DurationFilter.ALL

        return LibraryFilterState(
            sortBy = sortBy,
            sortOrder = sortOrder,
            durationFilter = durationFilter,
        )
    }

    fun save(state: LibraryFilterState) {
        preferences.edit()
            .putString(KEY_SORT_BY, state.sortBy.name)
            .putString(KEY_SORT_ORDER, state.sortOrder.name)
            .putString(KEY_DURATION_FILTER, state.durationFilter.name)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "library_filter_preferences"
        const val KEY_SORT_BY = "sort_by"
        const val KEY_SORT_ORDER = "sort_order"
        const val KEY_DURATION_FILTER = "duration_filter"
    }
}
