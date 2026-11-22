package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisModelsTest {
    @Test
    fun sourceLocatorRoundTripsDistinctAddressSpaces() {
        val locator = SourceLocator(
            byteOffset = 4096,
            byteLength = 128,
            rva = 0x1_0000,
            metadataToken = 0x0600_0042,
            pathId = 9_223_372_036_854_000L,
            ilOffset = 0x2a
        )

        val restored = SourceLocator.fromJsonObject(locator.toJsonObject())

        assertEquals(locator, restored)
        assertEquals(4096L, restored.byteOffset)
        assertEquals(0x1_0000L, restored.rva)
        assertEquals(0x0600_0042L, restored.metadataToken)
        assertEquals(9_223_372_036_854_000L, restored.pathId)
        assertEquals(0x2aL, restored.ilOffset)
    }

    @Test
    fun sourceLocatorAllowsFileLevelEvidenceWithoutInventingOffsets() {
        val locator = SourceLocator()
        val json = locator.toJsonObject()
        val restored = SourceLocator.fromJsonObject(json)

        assertEquals(locator, restored)
        assertTrue(json.isNull("byteOffset"))
        assertTrue(json.isNull("byteLength"))
        assertTrue(json.isNull("rva"))
        assertTrue(json.isNull("metadataToken"))
        assertTrue(json.isNull("pathId"))
        assertTrue(json.isNull("ilOffset"))
    }

    @Test
    fun sourceLocatorRejectsInvalidByteRangesAndAddressValues() {
        assertTrue(runCatching { SourceLocator(byteOffset = -1) }.isFailure)
        assertTrue(runCatching { SourceLocator(byteLength = 4) }.isFailure)
        assertTrue(runCatching { SourceLocator(byteOffset = 4, byteLength = 0) }.isFailure)
        assertTrue(runCatching { SourceLocator(byteOffset = Long.MAX_VALUE, byteLength = 1) }.isFailure)
        assertTrue(runCatching { SourceLocator(rva = 0x1_0000_0000L) }.isFailure)
        assertTrue(runCatching { SourceLocator(metadataToken = -1) }.isFailure)
        assertTrue(runCatching { SourceLocator(metadataToken = 0x1_0000_0000L) }.isFailure)
        assertTrue(runCatching { SourceLocator(ilOffset = -1) }.isFailure)
    }

    @Test
    fun findingRoundTripsAllFieldsAndReferences() {
        val finding = Finding.create(
            scanId = "scan-42",
            findingKey = "unity.script:Data/Managed/Assembly-CSharp.dll:HeroController",
            analyzerRunId = "run:unity",
            sourceFileId = "file:assembly",
            category = "managed-type",
            title = "HeroController",
            summary = "A managed gameplay type was identified.",
            confidence = AnalysisConfidence.CONFIRMED,
            status = FindingStatus.DECODED,
            evidenceIds = listOf("evidence:1", "evidence:2"),
            graphNodeIds = listOf("node:script"),
            graphEdgeIds = listOf("edge:assembly-contains-script"),
            parserVersion = "god-clr-1",
            supportedFormatRange = "CLI metadata v2.0-v2.5"
        )

        val restored = Finding.fromJsonObject(finding.toJsonObject())

        assertEquals(finding, restored)
        assertTrue(finding.id.startsWith("finding:"))
        assertEquals(71, finding.id.length)
    }

    @Test
    fun findingIdentityIsStableForSameScanAndKeyButScopedByScan() {
        val arguments = mapOf(
            "scanId" to "scan-a",
            "findingKey" to "native.import:UnityPlayer.dll:LoadLibraryW",
            "analyzerRunId" to "run:pe",
            "sourceFileId" to "file:unity-player",
            "category" to "native-import",
            "title" to "LoadLibraryW",
            "summary" to "Static import observed."
        )
        fun create(scanId: String, key: String) = Finding.create(
            scanId = scanId,
            findingKey = key,
            analyzerRunId = arguments.getValue("analyzerRunId"),
            sourceFileId = arguments.getValue("sourceFileId"),
            category = arguments.getValue("category"),
            title = arguments.getValue("title"),
            summary = arguments.getValue("summary")
        )

        val first = create("scan-a", "native.import:UnityPlayer.dll:LoadLibraryW")
        val repeated = create("scan-a", "native.import:UnityPlayer.dll:LoadLibraryW")
        val anotherScan = create("scan-b", "native.import:UnityPlayer.dll:LoadLibraryW")
        val anotherKey = create("scan-a", "native.import:UnityPlayer.dll:GetProcAddress")

        assertEquals(first.id, repeated.id)
        assertNotEquals(first.id, anotherScan.id)
        assertNotEquals(first.id, anotherKey.id)
    }

    @Test
    fun findingRejectsBlankReferencesAndDuplicateLinks() {
        assertTrue(runCatching {
            Finding(
                id = "finding:1",
                analyzerRunId = "run:1",
                sourceFileId = "file:1",
                category = "unity",
                title = "Object",
                summary = "Summary",
                confidence = AnalysisConfidence.UNKNOWN,
                status = FindingStatus.METADATA_ONLY,
                evidenceIds = listOf("evidence:1", "")
            )
        }.isFailure)
        assertTrue(runCatching {
            Finding(
                id = "finding:1",
                analyzerRunId = "run:1",
                sourceFileId = "file:1",
                category = "unity",
                title = "Object",
                summary = "Summary",
                confidence = AnalysisConfidence.UNKNOWN,
                status = FindingStatus.METADATA_ONLY,
                graphEdgeIds = listOf("edge:1", "edge:1")
            )
        }.isFailure)
    }

    @Test
    fun evidenceRoundTripsFindingRunFileAndExactLocator() {
        val evidence = Evidence.create(
            scanId = "scan-42",
            evidenceKey = "script-type:Assembly-CSharp:HeroController:token-0x0200002a",
            findingId = "finding:hero-controller",
            analyzerRunId = "run:clr",
            sourceFileId = "file:assembly",
            method = EvidenceMethod.PARSED,
            confidence = AnalysisConfidence.CONFIRMED,
            description = "TypeDef row decoded from the CLR metadata tables.",
            locator = SourceLocator(byteOffset = 0x1200, byteLength = 0x28, metadataToken = 0x0200_002a)
        )

        val restored = Evidence.fromJsonObject(evidence.toJsonObject())

        assertEquals(evidence, restored)
        assertEquals("finding:hero-controller", restored.findingId)
        assertEquals("file:assembly", restored.sourceFileId)
        assertEquals(0x0200_002aL, restored.locator?.metadataToken)
    }

    @Test
    fun evidenceSupportsFileLevelSourceAndDeterministicIdentity() {
        fun create(scanId: String) = Evidence.create(
            scanId = scanId,
            evidenceKey = "unity-build-version:globalgamemanagers",
            findingId = "finding:unity-version",
            analyzerRunId = "run:unity-header",
            sourceFileId = "file:globalgamemanagers",
            method = EvidenceMethod.PARSED,
            confidence = AnalysisConfidence.STRONG,
            description = "Unity version string decoded from the header."
        )

        val first = create("scan-a")
        val repeated = create("scan-a")
        val anotherScan = create("scan-b")
        val restored = Evidence.fromJsonObject(first.toJsonObject())

        assertEquals(first.id, repeated.id)
        assertNotEquals(first.id, anotherScan.id)
        assertEquals(first, restored)
        assertNull(restored.locator)
        assertTrue(first.toJsonObject().isNull("locator"))
    }

    @Test
    fun graphNodeRoundTripsFileAndFindingLinks() {
        val node = GraphNode.create(
            scanId = "scan-42",
            nodeType = "unity-monoscript",
            key = "file:assets:PathID:77",
            label = "HeroController",
            fileRecordId = "file:sharedassets0",
            locator = SourceLocator(pathId = 77),
            findingIds = listOf("finding:hero-controller"),
            evidenceIds = listOf("evidence:script-record")
        )

        val restored = GraphNode.fromJsonObject(node.toJsonObject())

        assertEquals(node, restored)
        assertTrue(node.id.startsWith("node:"))
    }

    @Test
    fun graphNodeCanRepresentVirtualEntitiesWithoutAFileRecord() {
        val node = GraphNode.create(
            scanId = "scan-a",
            nodeType = "external-api",
            key = "kernel32!LoadLibraryW",
            label = "LoadLibraryW"
        )

        val restored = GraphNode.fromJsonObject(node.toJsonObject())

        assertEquals(node, restored)
        assertNull(restored.fileRecordId)
        assertNull(restored.locator)
    }

    @Test
    fun graphEdgeRoundTripsResolvedRelationshipAndProvenance() {
        val edge = GraphEdge.create(
            scanId = "scan-42",
            edgeKey = "assembly-contains:Assembly-CSharp:HeroController",
            sourceNodeId = "node:assembly",
            targetNodeId = "node:hero-controller",
            relationType = "contains-type",
            confidence = AnalysisConfidence.CONFIRMED,
            status = FindingStatus.DECODED,
            evidenceIds = listOf("evidence:type-definition"),
            findingIds = listOf("finding:hero-controller"),
            analyzerRunId = "run:clr"
        )

        val restored = GraphEdge.fromJsonObject(edge.toJsonObject())

        assertEquals(edge, restored)
        assertNull(restored.targetHint)
        assertTrue(edge.id.startsWith("edge:"))
    }

    @Test
    fun graphEdgePreservesUnresolvedTargetHintWithoutInventingNode() {
        val edge = GraphEdge.create(
            scanId = "scan-a",
            edgeKey = "external-pptr:globalgamemanagers:10:77",
            sourceNodeId = "node:game-object-10",
            targetNodeId = null,
            relationType = "references-object",
            confidence = AnalysisConfidence.STRONG,
            status = FindingStatus.UNRESOLVED,
            analyzerRunId = "run:unity_external_pointers",
            targetHint = "fileID=2, PathID=77"
        )

        val restored = GraphEdge.fromJsonObject(edge.toJsonObject())

        assertEquals(edge, restored)
        assertNull(restored.targetNodeId)
        assertEquals("fileID=2, PathID=77", restored.targetHint)
    }

    @Test
    fun graphEdgeRequiresResolvedNodeOrExplicitTargetHint() {
        assertTrue(runCatching {
            GraphEdge(
                id = "edge:1",
                sourceNodeId = "node:source",
                targetNodeId = null,
                relationType = "references",
                confidence = AnalysisConfidence.UNKNOWN,
                status = FindingStatus.UNRESOLVED
            )
        }.isFailure)
    }

    @Test
    fun enumIdsAreStableAndUnknownValuesAreRejected() {
        assertEquals(
            listOf("confirmed", "strong", "probable", "weak", "unknown"),
            AnalysisConfidence.values().map { it.id }
        )
        assertEquals(
            listOf("decoded", "partial", "metadata-only", "skipped", "unsupported", "failed", "inaccessible", "unresolved"),
            FindingStatus.values().map { it.id }
        )
        assertEquals(
            listOf("parsed", "signature", "string-heuristic", "static-reference", "call-site", "resolved-pointer", "inference", "manual"),
            EvidenceMethod.values().map { it.id }
        )
        assertTrue(runCatching { AnalysisConfidence.fromId("guess") }.isFailure)
        assertTrue(runCatching { FindingStatus.fromId("complete") }.isFailure)
        assertTrue(runCatching { EvidenceMethod.fromId("unknown-method") }.isFailure)
    }

    @Test
    fun sourceLocatorRejectsFractionalJsonNumbers() {
        val json = SourceLocator(byteOffset = 10).toJsonObject()
        json.put("byteOffset", 10.5)
        assertTrue(runCatching { SourceLocator.fromJsonObject(json) }.isFailure)
    }
}
