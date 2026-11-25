package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityExternalPathResolverTest {
    private data class Candidate(val path: String, val name: String)

    @Test
    fun matchesNormalizedArchivePathExactly() {
        val expected = Candidate("Game_Data/sharedassets0.assets", "sharedassets0.assets")
        val other = Candidate("Game_Data/sharedassets1.assets", "sharedassets1.assets")
        val result = UnityExternalPathResolver.resolve(
            "archive:/Game_Data/sharedassets0.assets",
            listOf(expected, other),
            { it.path },
            { it.name }
        )
        assertEquals(expected, result.item)
        assertFalse(result.ambiguous)
    }

    @Test
    fun acceptsOnlyUniqueBasenameWhenArchivePathDiffers() {
        val expected = Candidate("Data/sharedassets0.assets", "sharedassets0.assets")
        val result = UnityExternalPathResolver.resolve(
            "archive:/CAB-123/sharedassets0.assets",
            listOf(expected),
            { it.path },
            { it.name }
        )
        assertEquals(expected, result.item)
        assertFalse(result.ambiguous)
    }

    @Test
    fun rejectsAmbiguousBasenameInsteadOfGuessing() {
        val candidates = listOf(
            Candidate("Scenes/sharedassets0.assets", "sharedassets0.assets"),
            Candidate("Resources/sharedassets0.assets", "sharedassets0.assets")
        )
        val result = UnityExternalPathResolver.resolve(
            "archive:/CAB-123/sharedassets0.assets",
            candidates,
            { it.path },
            { it.name }
        )
        assertNull(result.item)
        assertTrue(result.ambiguous)
    }

    @Test
    fun rejectsAmbiguousNestedPathSuffixesInsteadOfPreferringTheLongestMatch() {
        val candidates = listOf(
            Candidate("Data/sharedassets0.assets", "sharedassets0.assets"),
            Candidate("sharedassets0.assets", "sharedassets0.assets")
        )
        val result = UnityExternalPathResolver.resolve(
            "archive:/CAB-123/Data/sharedassets0.assets",
            candidates,
            { it.path },
            { it.name }
        )

        assertNull(result.item)
        assertTrue(result.ambiguous)
    }

    @Test
    fun reusesPrecomputedPathAndNameLookupsAcrossReferences() {
        val candidates = listOf(
            Candidate("Data/sharedassets0.assets", "sharedassets0.assets"),
            Candidate("Data/sharedassets1.assets", "sharedassets1.assets")
        )
        var pathReads = 0
        var nameReads = 0
        val index = UnityExternalPathResolver.Index.create(
            candidates = candidates,
            pathOf = { pathReads++; it.path },
            nameOf = { nameReads++; it.name }
        )

        assertEquals(candidates[0], index.resolve("archive:/CAB-123/sharedassets0.assets").item)
        assertEquals(candidates[1], index.resolve("archive:/Data/sharedassets1.assets").item)
        assertEquals(candidates[0], index.resolve("sharedassets0.assets").item)
        assertEquals(2, pathReads)
        assertEquals(2, nameReads)
    }

    @Test
    fun checksCancellationWhileScanningCandidatePaths() {
        val candidates = (1..5).map { index ->
            Candidate("Data/file$index.assets", "file$index.assets")
        }
        var checks = 0
        val error = runCatching {
            UnityExternalPathResolver.resolve(
                "archive:/CAB-123/missing.assets",
                candidates,
                { it.path },
                { it.name },
                shouldCancel = { ++checks >= 3 }
            )
        }.exceptionOrNull()

        assertTrue(error is ScanCancelledException)
        assertEquals(3, checks)
    }

    @Test
    fun normalizesWindowsSeparatorsAndCase() {
        val expected = Candidate("Game_Data/resources.assets", "resources.assets")
        val result = UnityExternalPathResolver.resolve(
            "ARCHIVE:\\Game_Data\\RESOURCES.ASSETS",
            listOf(expected),
            { it.path },
            { it.name }
        )
        assertEquals(expected, result.item)
        assertFalse(result.ambiguous)
    }
}
