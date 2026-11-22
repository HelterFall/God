package com.ramen.god

import org.json.JSONArray

/** Builds an inventory-only report when analysis is cancelled. */
internal object PartialReportBuilder {
    private const val MAX_MARKDOWN_FILES = 20_000

    fun build(rootName: String, files: List<FileRecord>, totalBytes: Long): ScanResult {
        val fileCount = files.count { !it.directory }
        val directoryCount = files.size - fileCount
        val warning = "Анализ отменён пользователем. Отчёт содержит только инвентаризацию; результаты анализаторов не завершены."
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
        }
        val jsonFiles = JSONArray().apply {
            files.forEach { put(it.toJsonObject()) }
        }
        val json = buildString {
            append("{")
            append("\"schemaVersion\":24,")
            append("\"rootName\":${jsonString(rootName)},")
            append("\"generatedAt\":${jsonString(java.time.Instant.now().toString())},")
            append("\"scanStatus\":\"cancelled\",")
            append("\"partial\":true,")
            append("\"entryCount\":${files.size},")
            append("\"totalFileBytes\":$totalBytes,")
            append("\"scanCoverage\":{")
            append("\"status\":\"partial\",")
            append("\"inventory\":{")
            append("\"status\":\"partial\",")
            append("\"entriesDiscovered\":${files.size},")
            append("\"filesDiscovered\":$fileCount,")
            append("\"directoriesDiscovered\":$directoryCount,")
            append("\"totalFileBytes\":$totalBytes,")
            append("\"inaccessibleDirectoryCount\":0,")
            append("\"depthLimitHitCount\":0,")
            append("\"entryLimitReached\":false},")
            append("\"analyzers\":[]},")
            append("\"files\":").append(jsonFiles).append(",")
            append("\"issues\":[{")
            append("\"code\":\"scan.cancelled\",")
            append("\"stage\":\"scan\",")
            append("\"severity\":\"warning\",")
            append("\"path\":null,")
            append("\"message\":${jsonString(warning)}}],")
            append("\"warnings\":[${jsonString(warning)}]")
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
