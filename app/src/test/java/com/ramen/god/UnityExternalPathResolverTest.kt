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
    fun normalizesWindowsSeparatorsAndCase() {
        val expected = Candidate("Game_Data/resources.assets", "resources.assets")
        val result = UnityExternalPathResolver.resolve(
            "ARCHIVE:\\\\Game_Data\\\\RESOURCES.ASSETS",
            listOf(expected),
            { it.path },
            { it.name }
        )
        assertEquals(expected, result.item)
        assertFalse(result.ambiguous)
    }
}
