package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnityVersionResolverTest {
    @Test
    fun recognizesOlderAndUnitySixVersionFormats() {
        assertEquals("5.6.7f1", UnityVersionResolver.extractVersions("engine 5.6.7f1").single())
        assertEquals("2021.3.15f1", UnityVersionResolver.extractVersions("Unity 2021.3.15f1").single())
        assertEquals("6000.0.23f1", UnityVersionResolver.extractVersions("6000.0.23f1").single())
    }

    @Test
    fun prefersGlobalGameManagersMetadataOverDifferentBundleVersions() {
        val result = UnityVersionResolver.resolve(listOf(
            UnityVersionEvidence("2021.3.15f1", "globalgamemanagers-metadata", "globalgamemanagers", "high"),
            UnityVersionEvidence("2020.3.42f1", "unityfs-engine-version", "StreamingAssets/content.bundle", "medium")
        ))
        assertEquals("2021.3.15f1", result.version)
        assertEquals("root-metadata", result.status)
        assertEquals("high", result.confidence)
    }

    @Test
    fun reportsConflictingRootMetadataInsteadOfGuessing() {
        val result = UnityVersionResolver.resolve(listOf(
            UnityVersionEvidence("2021.3.15f1", "globalgamemanagers-metadata", "globalgamemanagers", "high"),
            UnityVersionEvidence("2020.3.42f1", "globalgamemanagers-metadata", "copy/globalgamemanagers", "high")
        ))
        assertNull(result.version)
        assertEquals("conflicting-root-evidence", result.status)
    }

    @Test
    fun acceptsOneConsistentSupportingVersion() {
        val result = UnityVersionResolver.resolve(listOf(
            UnityVersionEvidence("2022.3.1f1", "serializedfile-metadata", "level0", "medium"),
            UnityVersionEvidence("2022.3.1f1", "unityfs-engine-version", "data.bundle", "medium")
        ))
        assertEquals("2022.3.1f1", result.version)
        assertEquals("supporting-evidence", result.status)
    }

    @Test
    fun refusesToChooseBetweenConflictingSupportingVersions() {
        val result = UnityVersionResolver.resolve(listOf(
            UnityVersionEvidence("2022.3.1f1", "serializedfile-metadata", "level0", "medium"),
            UnityVersionEvidence("2021.3.9f1", "unityfs-engine-version", "data.bundle", "medium")
        ))
        assertNull(result.version)
        assertEquals("conflicting-supporting-evidence", result.status)
    }

    @Test
    fun returnsNotFoundWhenNoVersionEvidenceExists() {
        val result = UnityVersionResolver.resolve(emptyList())
        assertNull(result.version)
        assertEquals("not-found", result.status)
    }
}
