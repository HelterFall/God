package com.ramen.god

/**
 * Per-analyzer coverage for a single scan.
 *
 * Counts deliberately distinguish candidates, attempts, successful parses,
 * partial parses, failures, bounded samples and candidates skipped entirely.
 */
internal data class AnalyzerCoverage(
    val id: String,
    val candidateCount: Int,
    val attemptedCount: Int,
    val parsedCount: Int,
    val partialCount: Int = 0,
    val failedCount: Int = 0,
    val limitedCount: Int = 0,
    val skippedCount: Int = 0,
    val notes: List<String> = emptyList(),
    val forcedStatus: String? = null
) {
    val status: String
        get() = forcedStatus ?: deriveAnalyzerStatus(
            candidateCount = candidateCount,
            attemptedCount = attemptedCount,
            parsedCount = parsedCount,
            partialCount = partialCount,
            failedCount = failedCount,
            limitedCount = limitedCount,
            skippedCount = skippedCount
        )
}

internal data class ScanCoverage(
    val inventoryStatus: String,
    val entriesDiscovered: Int,
    val filesDiscovered: Int,
    val directoriesDiscovered: Int,
    val totalFileBytes: Long,
    val inaccessibleDirectoryCount: Int,
    val depthLimitHitCount: Int,
    val entryLimitReached: Boolean,
    val analyzers: List<AnalyzerCoverage>
) {
    val status: String
        get() = if (
            inventoryStatus == "partial" ||
            analyzers.any { it.status == "partial" || it.status == "failed" }
        ) "partial" else "completed"
}

/** Pure status policy, kept separate so it can be tested without Android APIs. */
internal fun deriveAnalyzerStatus(
    candidateCount: Int,
    attemptedCount: Int,
    parsedCount: Int,
    partialCount: Int = 0,
    failedCount: Int = 0,
    limitedCount: Int = 0,
    skippedCount: Int = 0
): String {
    if (candidateCount <= 0) return "not-applicable"
    if (attemptedCount <= 0) return if (limitedCount > 0 || skippedCount > 0) "partial" else "not-run"
    if (
        failedCount >= attemptedCount &&
        parsedCount == 0 &&
        partialCount == 0 &&
        limitedCount == 0 &&
        skippedCount == 0
    ) return "failed"
    if (partialCount > 0 || failedCount > 0 || limitedCount > 0 || skippedCount > 0) return "partial"
    return "completed"
}
