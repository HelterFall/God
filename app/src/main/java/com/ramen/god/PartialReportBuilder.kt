package com.ramen.god

import org.json.JSONArray
import org.json.JSONObject

/** Builds a partial report with inventory and any analyzer outcomes collected before cancellation. */
internal object PartialReportBuilder {
    private const val MAX_MARKDOWN_FILES = 20_000

    fun build(
        rootName: String,
        files: List<FileRecord>,
        totalBytes: Long,
        scanId: String = java.util.UUID.randomUUID().toString(),
        analyzerRuns: List<AnalyzerRun> = emptyList(),
        findings: List<Finding> = emptyList(),
        evidence: List<Evidence> = emptyList(),
        graphNodes: List<GraphNode> = emptyList(),
        graphEdges: List<GraphEdge> = emptyList(),
        scanCoverage: ScanCoverage? = null,
        issues: List<ScanIssue> = emptyList()
    ): ScanResult {
        val fileCount = files.count { !it.directory }
        val directoryCount = files.size - fileCount
        val coverage = (scanCoverage ?: ScanCoverage(
            inventoryStatus = "partial",
            entriesDiscovered = files.size,
            filesDiscovered = fileCount,
            directoriesDiscovered = directoryCount,
            totalFileBytes = totalBytes,
            inaccessibleDirectoryCount = 0,
            depthLimitHitCount = 0,
            entryLimitReached = false,
            analyzers = emptyList()
        ))
        val warning = if (
            analyzerRuns.isEmpty() &&
            coverage.analyzers.none { it.candidateCount > 0 || it.attemptedCount > 0 } &&
            findings.isEmpty() && evidence.isEmpty() && graphNodes.isEmpty() && graphEdges.isEmpty()
        ) {
            "Анализ отменён пользователем. Отчёт содержит только инвентаризацию; результаты анализаторов не завершены."
        } else {
            "Анализ отменён пользователем. Отчёт сохраняет инвентаризацию и уже известные результаты анализаторов; queued-записи не завершены."
        }
        val reportIssues = issues + ScanIssue(
            code = "scan.cancelled",
            stage = ScanIssueStage.SCAN,
            severity = ScanIssueSeverity.WARNING,
            message = warning
        )
        val markdown = buildString {
            appendLine("# Unity Game Architecture Report")
            appendLine()
            appendLine("- Root: $rootName")
            appendLine("- Scan status: CANCELLED — PARTIAL REPORT")
            appendLine("- Indexed entries: ${files.size}")
            appendLine("- Files: $fileCount")
            appendLine("- Directories: $directoryCount")
            appendLine("- Total file bytes: $totalBytes")
            appendLine()
            appendLine("## Важно")
            appendLine(warning)
            appendLine()
            appendLine("## Inventory collected before cancellation")
            files.take(MAX_MARKDOWN_FILES).forEach { entry ->
                appendLine("- ${if (entry.directory) "[DIR]" else "[FILE]"} ${entry.path} (${entry.size} bytes)")
            }
            if (files.size > MAX_MARKDOWN_FILES) {
                appendLine("- Список Markdown ограничен: пропущено ${files.size - MAX_MARKDOWN_FILES} записей. Полный список находится в JSON.")
            }
            if (analyzerRuns.isNotEmpty()) {
                appendLine()
                appendLine("## Analyzer outcomes before cancellation")
                analyzerRuns.forEach { run ->
                    appendLine("- ${run.analyzerId}: ${run.inputFileId} — ${run.status.id}" +
                        (run.reason?.let { "; ${it}" } ?: ""))
                }
            }
            appendLine()
            appendLine("## Scan coverage before cancellation")
            appendLine("- Overall status: ${coverage.status}")
            appendLine("- Inventory status: ${coverage.inventoryStatus}")
            appendLine("- Entries: ${coverage.entriesDiscovered} (files=${coverage.filesDiscovered}, directories=${coverage.directoriesDiscovered})")
            appendLine("- Total file bytes: ${coverage.totalFileBytes}")
            appendLine("- Inaccessible directories: ${coverage.inaccessibleDirectoryCount}")
            appendLine("- Depth-limit hits: ${coverage.depthLimitHitCount}")
            appendLine("- Entry limit reached: ${coverage.entryLimitReached}")
            coverage.analyzers.forEach { analyzer ->
                appendLine("- ${analyzer.id}: ${analyzer.status}; candidates=${analyzer.candidateCount}; attempted=${analyzer.attemptedCount}; parsed=${analyzer.parsedCount}; partial=${analyzer.partialCount}; failed=${analyzer.failedCount}; limited=${analyzer.limitedCount}; skipped=${analyzer.skippedCount}")
                analyzer.notes.forEach { note -> appendLine("  - $note") }
            }
            if (issues.isNotEmpty()) {
                appendLine()
                appendLine("## Issues before cancellation")
                issues.forEach { issue ->
                    appendLine("- [${issue.severity.id}/${issue.stage.id}] ${issue.code}: ${issue.message}" +
                        (issue.path?.let { " (path=$it)" } ?: ""))
                }
            }
            if (findings.isNotEmpty() || evidence.isNotEmpty() ||
                graphNodes.isNotEmpty() || graphEdges.isNotEmpty()
            ) {
                appendLine()
                appendLine("## Evidence and graph results before cancellation")
                appendLine("- Findings: ${findings.size}; evidence: ${evidence.size}; " +
                    "graph nodes: ${graphNodes.size}; graph edges: ${graphEdges.size}")
                findings.forEach { finding ->
                    appendLine("- [${finding.status.id}/${finding.confidence.id}] " +
                        "${finding.title}: ${finding.summary}")
                }
            }
        }
        val jsonFiles = JSONArray().apply {
            files.forEach { put(it.toJsonObject()) }
        }
        val json = buildString {
            append("{")
            append("\"schemaVersion\":24,")
            append("\"rootName\":${jsonString(rootName)},")
            append("\"generatedAt\":${jsonString(java.time.Instant.now().toString())},")
            append("\"scanId\":${jsonString(scanId)},")
            append("\"scanStatus\":\"cancelled\",")
            append("\"partial\":true,")
            append("\"entryCount\":${files.size},")
            append("\"totalFileBytes\":$totalBytes,")
            append("\"scanCoverage\":{")
            append("\"status\":${jsonString(coverage.status)},")
            append("\"inventory\":{")
            append("\"status\":${jsonString(coverage.inventoryStatus)},")
            append("\"entriesDiscovered\":${coverage.entriesDiscovered},")
            append("\"filesDiscovered\":${coverage.filesDiscovered},")
            append("\"directoriesDiscovered\":${coverage.directoriesDiscovered},")
            append("\"totalFileBytes\":${coverage.totalFileBytes},")
            append("\"inaccessibleDirectoryCount\":${coverage.inaccessibleDirectoryCount},")
            append("\"depthLimitHitCount\":${coverage.depthLimitHitCount},")
            append("\"entryLimitReached\":${coverage.entryLimitReached}},")
            append("\"analyzers\":").append(JSONArray(coverage.analyzers.map { analyzer ->
                JSONObject()
                    .put("id", analyzer.id)
                    .put("status", analyzer.status)
                    .put("candidateCount", analyzer.candidateCount)
                    .put("attemptedCount", analyzer.attemptedCount)
                    .put("parsedCount", analyzer.parsedCount)
                    .put("partialCount", analyzer.partialCount)
                    .put("failedCount", analyzer.failedCount)
                    .put("limitedCount", analyzer.limitedCount)
                    .put("skippedCount", analyzer.skippedCount)
                    .put("notes", JSONArray(analyzer.notes))
            })).append("},")
            append("\"analyzerRuns\":").append(JSONArray(analyzerRuns.map { it.toJsonObject() })).append(",")
            append("\"findings\":").append(JSONArray(findings.map { it.toJsonObject() })).append(",")
            append("\"evidence\":").append(JSONArray(evidence.map { it.toJsonObject() })).append(",")
            append("\"graphNodes\":").append(JSONArray(graphNodes.map { it.toJsonObject() })).append(",")
            append("\"graphEdges\":").append(JSONArray(graphEdges.map { it.toJsonObject() })).append(",")
            append("\"files\":").append(jsonFiles).append(",")
            append("\"issues\":").append(JSONArray(reportIssues.map { it.toJsonObject() })).append(",")
            append("\"warnings\":").append(JSONArray(reportIssues.map { it.message }))
            append("}")
        }
        return ScanResult(markdown, json, isPartial = true)
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
