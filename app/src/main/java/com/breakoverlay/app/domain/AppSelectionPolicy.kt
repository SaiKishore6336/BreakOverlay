package com.breakoverlay.app.domain

import java.util.Locale

/**
 * Pure selection/search/sort policy used by the dashboard.
 *
 * Package names identify apps internally; filtering intentionally looks only at the
 * human-readable label. Selected apps sort first, then both groups sort alphabetically.
 */
object AppSelectionPolicy {
    fun <T> filterAndSort(
        items: List<T>,
        selectedPackages: Set<String>,
        query: String,
        packageOf: (T) -> String,
        labelOf: (T) -> String,
    ): List<T> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        return items
            .asSequence()
            .filter { item ->
                normalizedQuery.isEmpty() ||
                    labelOf(item).lowercase(Locale.ROOT).contains(normalizedQuery)
            }
            .sortedWith(
                compareBy<T>(
                    { if (packageOf(it) in selectedPackages) 0 else 1 },
                    { labelOf(it).lowercase(Locale.ROOT) },
                    { packageOf(it) },
                ),
            )
            .toList()
    }
}
