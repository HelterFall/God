package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanIssueTest {
    @Test
    fun serializesStableDiagnosticFieldsAndNullPath() {
        val issue = ScanIssue(
            code = "inventory.directory_listing_failed",
            stage = ScanIssueStage.INVENTORY,
            severity = ScanIssueSeverity.ERROR,
            message = "Не удалось прочитать каталог Data.",
            path = "Data"
        )

        val json = issue.toJsonObject()
        assertEquals("inventory.directory_listing_failed", json.getString("code"))
        assertEquals("inventory", json.getString("stage"))
        assertEquals("error", json.getString("severity"))
        assertEquals("Data", json.getString("path"))
        assertEquals("Не удалось прочитать каталог Data.", json.getString("message"))

        val globalIssue = issue.copy(path = null).toJsonObject()
        assertTrue(globalIssue.isNull("path"))
    }
}
