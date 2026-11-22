package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerRunTest {
    @Test
    fun identityIsStableWithinScanAndChangesForAnotherScanOrInput() {
        val first = AnalyzerRun.create("scan-a", "pe", "file:one")
        val repeated = AnalyzerRun.create("scan-a", "pe", "file:one", AnalyzerRunStatus.ANALYZED)
        val anotherScan = AnalyzerRun.create("scan-b", "pe", "file:one")
        val anotherFile = AnalyzerRun.create("scan-a", "pe", "file:two")

        assertEquals(first.id, repeated.id)
        assertNotEquals(first.id, anotherScan.id)
        assertNotEquals(first.id, anotherFile.id)
        assertTrue(first.id.startsWith("run:"))
        assertEquals(68, first.id.length)
    }

    @Test
    fun serializesFileLinkStatusAndReason() {
        val run = AnalyzerRun.create(
            scanId = "scan-a",
            analyzerId = "clr_metadata",
            inputFileId = "file:assembly",
            status = AnalyzerRunStatus.PARTIAL,
            reason = "metadata window was incomplete"
        )

        val json = run.toJsonObject()
        assertEquals(run.id, json.getString("id"))
        assertEquals("clr_metadata", json.getString("analyzerId"))
        assertEquals("file:assembly", json.getString("inputFileId"))
        assertEquals("partial", json.getString("status"))
        assertEquals("metadata window was incomplete", json.getString("reason"))
    }

    @Test
    fun exposesAllTerminalAndQueuedStatusesAsStableStrings() {
        assertEquals(
            listOf("queued", "analyzed", "partial", "skipped", "unsupported", "failed"),
            AnalyzerRunStatus.values().map { it.id }
        )
    }
}
