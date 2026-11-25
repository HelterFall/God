package com.ramen.god

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReportBuilderTest {
    private fun record(path: String, directory: Boolean, size: Long, parentId: String? = null): FileRecord =
        FileRecord.create(path, path.substringAfterLast('/'), directory, size, parentId, providerKey = null)

    @Test
    fun marksReportAsPartialAndCancelled() {
        val result = PartialReportBuilder.build("Game", emptyList(), 0)
        assertTrue(result.isPartial)
        assertTrue(result.markdown.contains("CANCELLED — PARTIAL REPORT"))
        assertTrue(result.json.contains("\"scanStatus\":\"cancelled\""))
        assertTrue(result.json.contains("\"partial\":true"))
        assertTrue(result.json.contains("\"schemaVersion\":24"))

        val report = JSONObject(result.json)
        val issues = report.getJSONArray("issues")
        assertEquals(1, issues.length())
        val issue = issues.getJSONObject(0)
        assertEquals("scan.cancelled", issue.getString("code"))
        assertEquals("scan", issue.getString("stage"))
        assertEquals("warning", issue.getString("severity"))
        assertTrue(issue.isNull("path"))
        assertEquals(report.getJSONArray("warnings").getString(0), issue.getString("message"))
    }

    @Test
    fun preservesAllInventoryEntriesInJson() {
        val data = record("Data", true, 0).copy(inventoryStatus = FileInventoryStatus.ENUMERATED)
        val asset = record("Data/sharedassets0.assets", false, 2048, parentId = data.id)
        val files = listOf(data, asset)
        val result = PartialReportBuilder.build("Game", files, 2048)
        assertTrue(result.json.contains("\"entryCount\":2"))
        assertTrue(result.json.contains("\"filesDiscovered\":1"))
        assertTrue(result.json.contains("\"directoriesDiscovered\":1"))
        val inventoryFiles = JSONObject(result.json).getJSONArray("files")
        assertEquals(data.id, inventoryFiles.getJSONObject(0).getString("id"))
        assertEquals("enumerated", inventoryFiles.getJSONObject(0).getString("inventoryStatus"))
        assertEquals(data.id, inventoryFiles.getJSONObject(1).getString("parentId"))
        assertEquals("Data/sharedassets0.assets", inventoryFiles.getJSONObject(1).getString("path"))
    }

    @Test
    fun escapesJsonControlCharactersAndQuotes() {
        val result = PartialReportBuilder.build("A\"B", listOf(record("folder\\name\nfile.json", false, 1)), 1)
        assertTrue(result.json.contains("A\\\"B"))
        assertTrue(result.json.contains("folder\\\\name\\nfile.json"))
    }

    @Test
    fun markdownListsInventoryAndExplainsAnalyzerLimit() {
        val result = PartialReportBuilder.build("Game", listOf(record("Data/file.assets", false, 42)), 42)
        assertTrue(result.markdown.contains("Data/file.assets"))
        assertTrue(result.markdown.contains("только инвентаризацию"))
        assertFalse(result.markdown.contains("PE analysis completed"))
    }

    @Test
    fun preservesAnalyzerRunsAndTheirStatusesWhenBuildingPartialReport() {
        val file = record("Managed/Assembly-CSharp.dll", false, 100)
        val completed = AnalyzerRun.create(
            "scan-123",
            "pe",
            file.id,
            AnalyzerRunStatus.ANALYZED
        )
        val queued = AnalyzerRun.create(
            "scan-123",
            "clr_metadata",
            file.id,
            AnalyzerRunStatus.QUEUED,
            reason = "Waiting for the next analysis stage."
        )

        val result = PartialReportBuilder.build(
            rootName = "Game",
            files = listOf(file),
            totalBytes = 100,
            scanId = "scan-123",
            analyzerRuns = listOf(completed, queued)
        )

        val report = JSONObject(result.json)
        assertEquals("scan-123", report.getString("scanId"))
        val runs = report.getJSONArray("analyzerRuns")
        assertEquals(2, runs.length())
        assertEquals(completed.id, runs.getJSONObject(0).getString("id"))
        assertEquals(file.id, runs.getJSONObject(0).getString("inputFileId"))
        assertEquals("analyzed", runs.getJSONObject(0).getString("status"))
        assertEquals("queued", runs.getJSONObject(1).getString("status"))
        assertEquals("Waiting for the next analysis stage.", runs.getJSONObject(1).getString("reason"))
        assertTrue(result.markdown.contains("Analyzer outcomes before cancellation"))
        assertTrue(result.markdown.contains("queued"))
        assertTrue(result.markdown.contains("queued-записи не завершены"))
    }

    @Test
    fun preservesFindingsEvidenceAndGraphWhenBuildingCancelledReport() {
        val file = record("Data/globalgamemanagers", false, 512)
        val scanId = "partial-evidence-scan"
        val run = AnalyzerRun.create(
            scanId = scanId,
            analyzerId = "unity_monobehaviour_script_bindings",
            inputFileId = file.id,
            status = AnalyzerRunStatus.PARTIAL,
            reason = "One external script target was unresolved."
        )
        val findingId = Finding.create(
            scanId = scanId,
            findingKey = "monobehaviour-script:10:1:77",
            analyzerRunId = run.id,
            sourceFileId = file.id,
            category = "unity.monobehaviour-script-binding",
            title = "MonoBehaviour m_Script binding",
            summary = "A sampled external script pointer was not resolved.",
            confidence = AnalysisConfidence.UNKNOWN,
            status = FindingStatus.UNRESOLVED,
            reason = "Target file was not found."
        ).id
        val evidence = Evidence.create(
            scanId = scanId,
            evidenceKey = "m-script-pointer:10:1:77",
            findingId = findingId,
            analyzerRunId = run.id,
            sourceFileId = file.id,
            method = EvidenceMethod.PARSED,
            confidence = AnalysisConfidence.UNKNOWN,
            description = "Decoded m_Script PPtr.",
            locator = SourceLocator(unityFileId = 1, pathId = 77)
        )
        val node = GraphNode.create(
            scanId = scanId,
            nodeType = "unity-monobehaviour",
            key = "PathID:10",
            label = "MonoBehaviour PathID 10",
            fileRecordId = file.id,
            locator = SourceLocator(pathId = 10),
            findingIds = listOf(findingId),
            evidenceIds = listOf(evidence.id)
        )
        val edge = GraphEdge.create(
            scanId = scanId,
            edgeKey = "monobehaviour-script:10:1:77",
            sourceNodeId = node.id,
            targetNodeId = null,
            relationType = "attaches-script",
            confidence = AnalysisConfidence.UNKNOWN,
            status = FindingStatus.UNRESOLVED,
            evidenceIds = listOf(evidence.id),
            findingIds = listOf(findingId),
            analyzerRunId = run.id,
            targetHint = "fileID=1, PathID=77 (target-missing)"
        )
        val finding = Finding.create(
            scanId = scanId,
            findingKey = "monobehaviour-script:10:1:77",
            analyzerRunId = run.id,
            sourceFileId = file.id,
            category = "unity.monobehaviour-script-binding",
            title = "MonoBehaviour m_Script binding",
            summary = "A sampled external script pointer was not resolved.",
            confidence = AnalysisConfidence.UNKNOWN,
            status = FindingStatus.UNRESOLVED,
            reason = "Target file was not found.",
            evidenceIds = listOf(evidence.id),
            graphNodeIds = listOf(node.id),
            graphEdgeIds = listOf(edge.id)
        )

        val result = PartialReportBuilder.build(
            rootName = "Game",
            files = listOf(file),
            totalBytes = 512,
            scanId = scanId,
            analyzerRuns = listOf(run),
            findings = listOf(finding),
            evidence = listOf(evidence),
            graphNodes = listOf(node),
            graphEdges = listOf(edge)
        )

        val report = JSONObject(result.json)
        assertEquals(24, report.getInt("schemaVersion"))
        assertEquals(1, report.getJSONArray("analyzerRuns").length())
        assertEquals(finding.id, report.getJSONArray("findings").getJSONObject(0).getString("id"))
        assertEquals(evidence.id, report.getJSONArray("evidence").getJSONObject(0).getString("id"))
        assertEquals(node.id, report.getJSONArray("graphNodes").getJSONObject(0).getString("id"))
        val restoredEdge = report.getJSONArray("graphEdges").getJSONObject(0)
        assertEquals(edge.id, restoredEdge.getString("id"))
        assertTrue(restoredEdge.isNull("targetNodeId"))
        assertEquals("fileID=1, PathID=77 (target-missing)", restoredEdge.getString("targetHint"))
        assertTrue(result.markdown.contains("Evidence and graph results before cancellation"))
        assertTrue(result.markdown.contains("MonoBehaviour m_Script binding"))
    }

    @Test
    fun preservesExternalUnityPointerEvidenceInCancelledReport() {
        val file = record("Data/globalgamemanagers", false, 512)
        val scanId = "partial-external-pointer-scan"
        val run = AnalyzerRun.create(
            scanId = scanId,
            analyzerId = "unity_external_pointers",
            inputFileId = file.id,
            status = AnalyzerRunStatus.PARTIAL,
            reason = "One target PathID was outside the bounded object sample."
        )
        val findingId = Finding.create(
            scanId = scanId,
            findingKey = "external-pointer:10:components:1:99",
            analyzerRunId = run.id,
            sourceFileId = file.id,
            category = "unity.external-pointer",
            title = "GameObject has-component external object",
            summary = "GameObject PathID=10 field=components points to fileID=1, PathID=99.",
            confidence = AnalysisConfidence.WEAK,
            status = FindingStatus.PARTIAL,
            reason = "Target was outside the bounded object sample."
        ).id
        val evidence = Evidence.create(
            scanId = scanId,
            evidenceKey = "external-pptr:10:components:1:99",
            findingId = findingId,
            analyzerRunId = run.id,
            sourceFileId = file.id,
            method = EvidenceMethod.PARSED,
            confidence = AnalysisConfidence.WEAK,
            description = "Decoded GameObject.components external PPtr.",
            locator = SourceLocator(unityFileId = 1, pathId = 99)
        )
        val sourceNode = GraphNode.create(
            scanId = scanId,
            nodeType = "unity-gameobject",
            key = "PathID:10",
            label = "GameObject PathID 10",
            fileRecordId = file.id,
            locator = SourceLocator(pathId = 10),
            findingIds = listOf(findingId),
            evidenceIds = listOf(evidence.id)
        )
        val edge = GraphEdge.create(
            scanId = scanId,
            edgeKey = "external-pointer:10:components:1:99",
            sourceNodeId = sourceNode.id,
            targetNodeId = null,
            relationType = "has-component",
            confidence = AnalysisConfidence.WEAK,
            status = FindingStatus.PARTIAL,
            evidenceIds = listOf(evidence.id),
            findingIds = listOf(findingId),
            analyzerRunId = run.id,
            targetHint = "fileID=1, PathID=99 (pathID-not-in-object-sample)"
        )
        val finding = Finding.create(
            scanId = scanId,
            findingKey = "external-pointer:10:components:1:99",
            analyzerRunId = run.id,
            sourceFileId = file.id,
            category = "unity.external-pointer",
            title = "GameObject has-component external object",
            summary = "GameObject PathID=10 field=components points to fileID=1, PathID=99.",
            confidence = AnalysisConfidence.WEAK,
            status = FindingStatus.PARTIAL,
            reason = "Target was outside the bounded object sample.",
            evidenceIds = listOf(evidence.id),
            graphNodeIds = listOf(sourceNode.id),
            graphEdgeIds = listOf(edge.id)
        )

        val result = PartialReportBuilder.build(
            rootName = "Game",
            files = listOf(file),
            totalBytes = 512,
            scanId = scanId,
            analyzerRuns = listOf(run),
            findings = listOf(finding),
            evidence = listOf(evidence),
            graphNodes = listOf(sourceNode),
            graphEdges = listOf(edge)
        )

        val report = JSONObject(result.json)
        assertEquals("unity.external-pointer", report.getJSONArray("findings")
            .getJSONObject(0).getString("category"))
        assertEquals(evidence.id, report.getJSONArray("evidence").getJSONObject(0).getString("id"))
        assertEquals(sourceNode.id, report.getJSONArray("graphNodes").getJSONObject(0).getString("id"))
        val restoredEdge = report.getJSONArray("graphEdges").getJSONObject(0)
        assertEquals("has-component", restoredEdge.getString("relationType"))
        assertEquals("partial", restoredEdge.getString("status"))
        assertTrue(restoredEdge.getString("targetHint").contains("PathID=99"))
        assertTrue(result.markdown.contains("GameObject has-component external object"))
    }

    @Test
    fun preservesLocalUnityScenePointerEvidenceInCancelledReport() {
        val file = record("Data/globalgamemanagers", false, 512)
        val scanId = "partial-local-scene-pointer-scan"
        val run = AnalyzerRun.create(
            scanId = scanId,
            analyzerId = "unity_scene_graph",
            inputFileId = file.id,
            status = AnalyzerRunStatus.ANALYZED
        )
        val findingId = Finding.create(
            scanId = scanId,
            findingKey = "local-scene-pointer:100:components:0:200",
            analyzerRunId = run.id,
            sourceFileId = file.id,
            category = "unity.local-scene-pointer",
            title = "Root has-component Transform",
            summary = "GameObject PathID=100 field=components points to local PPtr fileID=0, PathID=200.",
            confidence = AnalysisConfidence.CONFIRMED,
            status = FindingStatus.DECODED
        ).id
        val evidence = Evidence.create(
            scanId = scanId,
            evidenceKey = "local-scene-pptr:100:components:200",
            findingId = findingId,
            analyzerRunId = run.id,
            sourceFileId = file.id,
            method = EvidenceMethod.RESOLVED_POINTER,
            confidence = AnalysisConfidence.CONFIRMED,
            description = "Decoded local GameObject.components PPtr.",
            locator = SourceLocator(unityFileId = 0, pathId = 200)
        )
        val sourceNode = GraphNode.create(
            scanId = scanId,
            nodeType = "unity-gameobject",
            key = "PathID:100",
            label = "Root",
            fileRecordId = file.id,
            locator = SourceLocator(pathId = 100),
            findingIds = listOf(findingId),
            evidenceIds = listOf(evidence.id)
        )
        val edge = GraphEdge.create(
            scanId = scanId,
            edgeKey = "local-scene-pointer:100:components:0:200",
            sourceNodeId = sourceNode.id,
            targetNodeId = null,
            relationType = "has-component",
            confidence = AnalysisConfidence.WEAK,
            status = FindingStatus.PARTIAL,
            evidenceIds = listOf(evidence.id),
            findingIds = listOf(findingId),
            analyzerRunId = run.id,
            targetHint = "fileID=0, PathID=200 (pathID-not-in-object-sample)"
        )
        val finding = Finding.create(
            scanId = scanId,
            findingKey = "local-scene-pointer:100:components:0:200",
            analyzerRunId = run.id,
            sourceFileId = file.id,
            category = "unity.local-scene-pointer",
            title = "Root has-component Transform",
            summary = "GameObject PathID=100 field=components points to local PPtr fileID=0, PathID=200.",
            confidence = AnalysisConfidence.WEAK,
            status = FindingStatus.PARTIAL,
            reason = "The local target was outside the bounded object sample.",
            evidenceIds = listOf(evidence.id),
            graphNodeIds = listOf(sourceNode.id),
            graphEdgeIds = listOf(edge.id)
        )

        val result = PartialReportBuilder.build(
            rootName = "Game",
            files = listOf(file),
            totalBytes = 512,
            scanId = scanId,
            analyzerRuns = listOf(run),
            findings = listOf(finding),
            evidence = listOf(evidence),
            graphNodes = listOf(sourceNode),
            graphEdges = listOf(edge)
        )

        val report = JSONObject(result.json)
        assertEquals("unity.local-scene-pointer",
            report.getJSONArray("findings").getJSONObject(0).getString("category"))
        assertEquals(evidence.id, report.getJSONArray("evidence").getJSONObject(0).getString("id"))
        assertEquals(sourceNode.id, report.getJSONArray("graphNodes").getJSONObject(0).getString("id"))
        val restoredEdge = report.getJSONArray("graphEdges").getJSONObject(0)
        assertEquals("has-component", restoredEdge.getString("relationType"))
        assertEquals("partial", restoredEdge.getString("status"))
        assertTrue(restoredEdge.getString("targetHint").contains("PathID=200"))
        assertTrue(result.markdown.contains("Root has-component Transform"))
    }

    @Test
    fun preservesScanCoverageCountersAndExistingIssuesWhenBuildingCancelledReport() {
        val data = record("Data", true, 0).copy(inventoryStatus = FileInventoryStatus.INACCESSIBLE)
        val asset = record("Data/sharedassets0.assets", false, 512, parentId = data.id)
        val issue = ScanIssue(
            code = "inventory.directory_listing_failed",
            stage = ScanIssueStage.INVENTORY,
            severity = ScanIssueSeverity.ERROR,
            message = "Data could not be enumerated.",
            path = "Data"
        )
        val coverage = ScanCoverage(
            inventoryStatus = "partial",
            entriesDiscovered = 2,
            filesDiscovered = 1,
            directoriesDiscovered = 1,
            totalFileBytes = 512,
            inaccessibleDirectoryCount = 1,
            depthLimitHitCount = 2,
            entryLimitReached = true,
            analyzers = listOf(
                AnalyzerCoverage(
                    id = "pe",
                    candidateCount = 2,
                    attemptedCount = 1,
                    parsedCount = 1,
                    failedCount = 0,
                    skippedCount = 1,
                    notes = listOf("One remaining PE candidate was not processed.")
                )
            )
        )

        val result = PartialReportBuilder.build(
            rootName = "Game",
            files = listOf(data, asset),
            totalBytes = 512,
            scanId = "coverage-preservation-scan",
            scanCoverage = coverage,
            issues = listOf(issue)
        )

        val report = JSONObject(result.json)
        val restoredCoverage = report.getJSONObject("scanCoverage")
        assertEquals("partial", restoredCoverage.getString("status"))
        val inventory = restoredCoverage.getJSONObject("inventory")
        assertEquals(1, inventory.getInt("inaccessibleDirectoryCount"))
        assertEquals(2, inventory.getInt("depthLimitHitCount"))
        assertTrue(inventory.getBoolean("entryLimitReached"))

        val analyzers = restoredCoverage.getJSONArray("analyzers")
        assertEquals(1, analyzers.length())
        assertEquals("pe", analyzers.getJSONObject(0).getString("id"))
        assertEquals(2, analyzers.getJSONObject(0).getInt("candidateCount"))
        assertEquals(1, analyzers.getJSONObject(0).getInt("attemptedCount"))
        assertTrue(analyzers.getJSONObject(0).getJSONArray("notes").getString(0)
            .contains("remaining PE candidate"))

        val issues = report.getJSONArray("issues")
        assertEquals(2, issues.length())
        assertEquals("inventory.directory_listing_failed", issues.getJSONObject(0).getString("code"))
        assertEquals("Data", issues.getJSONObject(0).getString("path"))
        assertEquals("scan.cancelled", issues.getJSONObject(1).getString("code"))
        val warnings = report.getJSONArray("warnings")
        assertEquals(2, warnings.length())
        assertEquals("Data could not be enumerated.", warnings.getString(0))
        assertEquals(issues.getJSONObject(1).getString("message"), warnings.getString(1))
    }

    @Test
    fun keepsCompletedInventoryStatusWhenAnalysisWasCancelledAfterTraversal() {
        val file = record("UnityPlayer.dll", false, 100)
        val coverage = ScanCoverage(
            inventoryStatus = "completed",
            entriesDiscovered = 1,
            filesDiscovered = 1,
            directoriesDiscovered = 0,
            totalFileBytes = 100,
            inaccessibleDirectoryCount = 0,
            depthLimitHitCount = 0,
            entryLimitReached = false,
            analyzers = listOf(
                AnalyzerCoverage(
                    id = "pe",
                    candidateCount = 1,
                    attemptedCount = 0,
                    parsedCount = 0
                )
            )
        )

        val result = PartialReportBuilder.build(
            rootName = "Game",
            files = listOf(file),
            totalBytes = 100,
            scanCoverage = coverage
        )

        val report = JSONObject(result.json)
        assertEquals("partial", report.getJSONObject("scanCoverage").getString("status"))
        assertEquals(
            "completed",
            report.getJSONObject("scanCoverage").getJSONObject("inventory").getString("status")
        )
    }

    @Test
    fun markdownCapsLargeInventoryButJsonRetainsEveryEntry() {
        val files = (1..20_001).map { record("file-$it", false, 1) }
        val result = PartialReportBuilder.build("Game", files, files.size.toLong())
        assertTrue(result.markdown.contains("пропущено 1 записей"))
        assertTrue(result.json.contains("file-20001"))
        assertTrue(result.json.contains("\"entryCount\":20001"))
    }
}
