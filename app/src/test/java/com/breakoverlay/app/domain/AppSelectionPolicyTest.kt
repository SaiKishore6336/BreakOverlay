package com.breakoverlay.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelectionPolicyTest {

    private data class App(
        val packageName: String,
        val label: String,
    )

    private val alpha = App("pkg.alpha", "Alpha")
    private val beta = App("pkg.beta", "Beta")
    private val zulu = App("pkg.zulu", "Zulu")
    private val youtube = App("com.google.android.youtube", "YouTube")

    @Test
    fun `search filters by label case insensitively`() {
        val result = AppSelectionPolicy.filterAndSort(
            items = listOf(alpha, beta, zulu, youtube),
            selectedPackages = emptySet(),
            query = "yOu",
            packageOf = { it.packageName },
            labelOf = { it.label },
        )

        assertEquals("com.google.android.youtube", result.single().packageName)
    }

    @Test
    fun `package name is not part of label search`() {
        val result = AppSelectionPolicy.filterAndSort(
            items = listOf(youtube),
            selectedPackages = emptySet(),
            query = "com.google",
            packageOf = { it.packageName },
            labelOf = { it.label },
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `selected apps sort first and each group sorts alphabetically`() {
        val result = AppSelectionPolicy.filterAndSort(
            items = listOf(zulu, alpha, beta),
            selectedPackages = setOf(zulu.packageName, beta.packageName),
            query = "",
            packageOf = { it.packageName },
            labelOf = { it.label },
        )

        assertEquals(
            listOf("pkg.beta", "pkg.zulu", "pkg.alpha"),
            result.map { it.packageName },
        )
    }

    @Test
    fun `search retains selected first ordering`() {
        val result = AppSelectionPolicy.filterAndSort(
            items = listOf(alpha, zulu, youtube),
            selectedPackages = setOf(zulu.packageName),
            query = "",
            packageOf = { it.packageName },
            labelOf = { it.label },
        )

        assertEquals(
            listOf("pkg.zulu", "pkg.alpha", "com.google.android.youtube"),
            result.map { it.packageName },
        )
    }

    @Test
    fun `blank or whitespace query returns all apps without changing selection state`() {
        val items = listOf(zulu, alpha, beta)
        val result = AppSelectionPolicy.filterAndSort(
            items = items,
            selectedPackages = setOf(alpha.packageName),
            query = "   ",
            packageOf = { it.packageName },
            labelOf = { it.label },
        )

        assertEquals(3, result.size)
        assertEquals("pkg.alpha", result.first().packageName)
        assertEquals(listOf(zulu, alpha, beta), items)
    }
}
