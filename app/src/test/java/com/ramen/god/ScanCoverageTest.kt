package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanCoverageTest {
    @Test
    fun reportsAnalyzerStatusesFromCoverageCounts() {
        assertEquals("not-applicable", deriveAnalyzerStatus(0, 0, 0))
        assertEquals("not-run", deriveAnalyzerStatus(3, 0, 0))
        assertEquals("completed", deriveAnalyzerStatus(3, 3, 3))
        assertEquals("partial", deriveAnalyzerStatus(3, 3, 2, failedCount = 1))
        assertEquals("partial", deriveAnalyzerStatus(3, 2, 2, skippedCount = 1))
        assertEquals("partial", deriveAnalyzerStatus(5, 2, 2, limitedCount = 3))
        assertEquals("failed", deriveAnalyzerStatus(2, 2, 0, failedCount = 2))
        assertEquals("partial", deriveAnalyzerStatus(0, 0, 0, limitedCount = 2))
    }

    @Test
    fun marksScanPartialWhenInventoryOrAnAnalyzerIsPartial() {
        val complete = ScanCoverage(
            inventoryStatus = "completed",
            entriesDiscovered = 5,
            filesDiscovered = 3,
            directoriesDiscovered = 2,
            totalFileBytes = 100L,
            inaccessibleDirectoryCount = 0,
            depthLimitHitCount = 0,
            entryLimitReached = false,
            analyzers = listOf(AnalyzerCoverage("pe", 1, 1, 1))
        )
        assertEquals("completed", complete.status)

        val partial = complete.copy(
            analyzers = listOf(AnalyzerCoverage("pe", 2, 1, 1, skippedCount = 1))
        )
        assertEquals("partial", partial.status)

        val incompleteInventory = complete.copy(inventoryStatus = "partial")
        assertEquals("partial", incompleteInventory.status)

        val unsupported = complete.copy(
            analyzers = listOf(
                AnalyzerCoverage("unityfs_contents", 1, 0, 0, forcedStatus = "unsupported")
            )
        )
        assertEquals("partial", unsupported.status)

        val notRun = complete.copy(
            analyzers = listOf(AnalyzerCoverage("config", 1, 0, 0))
        )
        assertEquals("partial", notRun.status)
    }
}
