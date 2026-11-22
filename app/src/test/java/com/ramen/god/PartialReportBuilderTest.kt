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
    fun markdownCapsLargeInventoryButJsonRetainsEveryEntry() {
        val files = (1..20_001).map { record("file-$it", false, 1) }
        val result = PartialReportBuilder.build("Game", files, files.size.toLong())
        assertTrue(result.markdown.contains("пропущено 1 записей"))
        assertTrue(result.json.contains("file-20001"))
        assertTrue(result.json.contains("\"entryCount\":20001"))
    }
}
