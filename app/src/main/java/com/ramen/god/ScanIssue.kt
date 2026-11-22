package com.ramen.god

import org.json.JSONObject

/** Stable stage identifiers for machine-readable scan diagnostics. */
internal enum class ScanIssueStage(val id: String) {
    SCAN("scan"),
    INVENTORY("inventory"),
    PE("pe"),
    CLR_METADATA("clr_metadata"),
    UNITY_FILE_HEADERS("unity_file_headers"),
    SERIALIZED_FILE_METADATA("serialized_file_metadata"),
    UNITY_VERSION("unity_version")
}

/** Severity is about the diagnostic, not a verdict about the game being scanned. */
internal enum class ScanIssueSeverity(val id: String) {
    INFO("info"),
    WARNING("warning"),
    ERROR("error")
}

/**
 * A structured diagnostic that can be searched and grouped without parsing
 * the human-readable legacy warning text.
 */
internal data class ScanIssue(
    val code: String,
    val stage: ScanIssueStage,
    val severity: ScanIssueSeverity,
    val message: String,
    val path: String? = null
) {
    fun toJsonObject(): JSONObject = JSONObject()
        .put("code", code)
        .put("stage", stage.id)
        .put("severity", severity.id)
        .put("path", path ?: JSONObject.NULL)
        .put("message", message)
}
