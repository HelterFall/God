package com.ramen.god

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialReportBuilderTest {
    @Test
    fun marksReportAsPartialAndCancelled() {
        val result = PartialReportBuilder.build("Game", emptyList(), 0)
        assertTrue(result.isPartial)
        assertTrue(result.markdown.contains("CANCELLED — PARTIAL REPORT"))
        assertTrue(result.json.contains("\"scanStatus\":\"cancelled\""))
        assertTrue(result.json.contains("\"partial\":true"))
        assertTrue(result.json.contains("\"schemaVersion\":24"))
    }

    @Test
    fun preservesAllInventoryEntriesInJson() {
        val files = listOf(PartialFileEntry("Data", true, 0), PartialFileEntry("Data/sharedassets0.assets", false, 2048))
        val result = PartialReportBuilder.build("Game", files, 2048)
        assertTrue(result.json.contains("\"entryCount\":2"))
        assertTrue(result.json.contains("\"filesDiscovered\":1"))
        assertTrue(result.json.contains("\"directoriesDiscovered\":1"))
        assertTrue(result.json.contains("Data/sharedassets0.assets"))
    }

    @Test
    fun escapesJsonControlCharactersAndQuotes() {
        val result = PartialReportBuilder.build("A\"B", listOf(PartialFileEntry("folder\\name\nfile.json", false, 1)), 1)
        assertTrue(result.json.contains("A\\\"B"))
        assertTrue(result.json.contains("folder\\\\name\\nfile.json"))
    }

    @Test
    fun markdownListsInventoryAndExplainsAnalyzerLimit() {
        val result = PartialReportBuilder.build("Game", listOf(PartialFileEntry("Data/file.assets", false, 42)), 42)
        assertTrue(result.markdown.contains("Data/file.assets"))
        assertTrue(result.markdown.contains("только инвентаризацию"))
        assertFalse(result.markdown.contains("PE analysis completed"))
    }

    @Test
    fun markdownCapsLargeInventoryButJsonRetainsEveryEntry() {
        val files = (1..20_001).map { PartialFileEntry("file-$it", false, 1) }
        val result = PartialReportBuilder.build("Game", files, files.size.toLong())
        assertTrue(result.markdown.contains("пропущено 1 записей"))
        assertTrue(result.json.contains("file-20001"))
        assertTrue(result.json.contains("\"entryCount\":20001"))
    }
}
