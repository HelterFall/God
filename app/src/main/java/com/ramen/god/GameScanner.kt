package com.ramen.god

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

private data class ScanEntry(var record: FileRecord, val node: ScanNode) {
    val path: String get() = record.path
    val name: String get() = record.name
    val directory: Boolean get() = record.directory
    val size: Long get() = record.size

    var inventoryStatus: FileInventoryStatus
        get() = record.inventoryStatus
        set(value) {
            record = record.copy(inventoryStatus = value)
        }
}
data class ScanResult(val markdown: String, val json: String, val isPartial: Boolean = false)
class ScanCancelledException : Exception()

internal class GameScanner(private val fileAccess: ScanFileAccess) {
    private val entries = mutableListOf<ScanEntry>()
    private val issues = mutableListOf<ScanIssue>()
    private val analyzerRuns = mutableListOf<AnalyzerRun>()
    private val findings = mutableListOf<Finding>()
    private val evidenceRecords = mutableListOf<Evidence>()
    private val graphNodes = mutableListOf<GraphNode>()
    private val graphEdges = mutableListOf<GraphEdge>()
    private var scanId: String = ""
    private val warnings: List<String>
        get() = issues.map { it.message }
    private val peResults = linkedMapOf<String, PeInfo>()
    private val clrResults = linkedMapOf<String, ClrAssemblySummary>()
    private val managedTypeResolutions = mutableListOf<UnityManagedTypeResolution>()
    private val unityHeaders = linkedMapOf<String, UnityFileHeader>()
    private val unityMetadata = linkedMapOf<String, UnityMetadataSummary>()
    private var totalBytes = 0L
    private var inaccessibleDirectoryCount = 0
    private var depthLimitHitCount = 0
    private var entryLimitReached = false
    private var peAttemptedCount = 0
    private var peFailedCount = 0
    private var clrAttemptedCount = 0
    private var clrFailedCount = 0
    private var unityHeaderAttemptedCount = 0
    private var unityHeaderFailedCount = 0
    private var unityMetadataCandidateCount = 0
    private var unityMetadataAttemptedCount = 0
    private var unityMetadataFailedCount = 0
    private var unityMetadataPrerequisiteSkippedCount = 0
    private var unityMetadataWindowLimitedCount = 0
    private val unityHeaderCandidatePaths = mutableSetOf<String>()
    private val unityHeaderAttemptedPaths = mutableSetOf<String>()
    private val unityHeaderFailedPaths = mutableSetOf<String>()
    private val unityMetadataCandidatePaths = mutableSetOf<String>()
    private val unityMetadataAttemptedPaths = mutableSetOf<String>()
    private val unityMetadataWindowLimitedPaths = mutableSetOf<String>()
    private val unityMetadataPrerequisiteSkippedPaths = mutableSetOf<String>()
    private val unityMetadataFailedPaths = mutableSetOf<String>()

    companion object {
        private const val MAX_ENTRIES = 100_000
        private const val MAX_DEPTH = 40
        private const val MAX_PE_FILES = 150
        private const val MAX_PE_READ = 1024 * 1024
        private const val MAX_CLR_PE_READ = 4 * 1024 * 1024
        private const val MAX_UNITY_HEADER_FILES = 500
        private const val MAX_UNITY_HEADER_READ = 256
        private const val MAX_UNITY_METADATA_FILES = 40
        private const val MAX_UNITY_METADATA_READ = 8 * 1024 * 1024
        private const val MAX_EXTERNAL_METADATA_FILES = 24
        private const val MAX_GGM_READ = 16 * 1024 * 1024
        private const val MAX_TREE_LINES = 20_000
    }

    fun scan(
        root: ScanNode,
        shouldCancel: () -> Boolean,
        onProgress: (Int, String) -> Unit
    ): ScanResult {
        entries.clear()
        issues.clear()
        analyzerRuns.clear()
        findings.clear()
        evidenceRecords.clear()
        graphNodes.clear()
        graphEdges.clear()
        scanId = UUID.randomUUID().toString()
        peResults.clear()
        clrResults.clear()
        managedTypeResolutions.clear()
        unityHeaders.clear()
        unityMetadata.clear()
        totalBytes = 0L
        inaccessibleDirectoryCount = 0
        depthLimitHitCount = 0
        entryLimitReached = false
        peAttemptedCount = 0
        peFailedCount = 0
        clrAttemptedCount = 0
        clrFailedCount = 0
        unityHeaderAttemptedCount = 0
        unityHeaderFailedCount = 0
        unityMetadataCandidateCount = 0
        unityMetadataAttemptedCount = 0
        unityMetadataFailedCount = 0
        unityMetadataPrerequisiteSkippedCount = 0
        unityMetadataWindowLimitedCount = 0
        unityHeaderCandidatePaths.clear()
        unityHeaderAttemptedPaths.clear()
        unityHeaderFailedPaths.clear()
        unityMetadataCandidatePaths.clear()
        unityMetadataAttemptedPaths.clear()
        unityMetadataWindowLimitedPaths.clear()
        unityMetadataPrerequisiteSkippedPaths.clear()
        unityMetadataFailedPaths.clear()
        try {
            walk(root, "", 0, null, shouldCancel, onProgress)
        } catch (_: ScanCancelledException) {
            return buildPartialResult(root.name ?: "(unnamed)")
        }
        if (shouldCancel()) return buildPartialResult(root.name ?: "(unnamed)")
        onProgress(entries.size, "Инвентаризация завершена; анализирую PE-файлы")

        val files = entries.filter { !it.directory }
        val dlls = files.filter { it.name.endsWith(".dll", true) }
        val lowerPaths = entries.map { it.path.lowercase(Locale.ROOT) }
        val monoFolder = lowerPaths.any { it.contains("monobleedingedge") }
        val managedDir = lowerPaths.any { it.endsWith("/managed") || it.contains("/managed/") }
        val hasGameAssembly = files.any { it.name.equals("GameAssembly.dll", true) }
        val hasMetadata = files.any { it.name.equals("global-metadata.dat", true) }
        val hasIl2CppDir = lowerPaths.any { it.contains("/il2cpp_data/") }
        val hasUnityPlayer = files.any { it.name.equals("UnityPlayer.dll", true) }
        val gameAssemblies = dlls.filter {
            it.name.equals("Assembly-CSharp.dll", true) || it.name.equals("Assembly-CSharp-firstpass.dll", true)
        }
        val unityModules = dlls.filter { it.name.startsWith("UnityEngine", true) }
        val il2cppStrong = hasGameAssembly && hasMetadata && hasIl2CppDir
        val monoEvidence = monoFolder || managedDir || gameAssemblies.isNotEmpty()
        val backend = when {
            il2cppStrong -> "IL2CPP — сильные структурные признаки"
            monoEvidence && !hasGameAssembly -> "Mono — сильные структурные признаки"
            monoEvidence -> "Смешанные признаки Mono и IL2CPP; нужна ручная проверка"
            else -> "Не определён по структуре"
        }

        val peCandidates = files.filter { it.name.endsWith(".exe", true) || it.name.endsWith(".dll", true) }
        if (peCandidates.size > MAX_PE_FILES) addIssue(
            code = "pe.candidate_limit_reached",
            stage = ScanIssueStage.PE,
            severity = ScanIssueSeverity.WARNING,
            message = "PE-анализ ограничен первыми " + MAX_PE_FILES + " EXE/DLL."
        )
        val peRunIds = mutableMapOf<String, String>()
        peCandidates.forEachIndexed { index, entry ->
            val limited = index >= MAX_PE_FILES
            val run = AnalyzerRun.create(
                scanId = scanId,
                analyzerId = "pe",
                inputFileId = entry.record.id,
                status = if (limited) AnalyzerRunStatus.SKIPPED else AnalyzerRunStatus.QUEUED,
                reason = if (limited) "Candidate limit reached ($MAX_PE_FILES)." else null
            )
            analyzerRuns.add(run)
            peRunIds[entry.record.id] = run.id
        }
        try {
            peCandidates.take(MAX_PE_FILES).forEach { entry ->
                if (shouldCancel()) throw ScanCancelledException()
                peAttemptedCount++
                val info = readPeInfo(entry)
                if (info == null) {
                    peFailedCount++
                    updateAnalyzerRun(
                        peRunIds.getValue(entry.record.id),
                        AnalyzerRunStatus.FAILED,
                        "PE header was not recognized or the file could not be read."
                    )
                } else {
                    peResults[entry.path] = info
                    updateAnalyzerRun(
                        peRunIds.getValue(entry.record.id),
                        if (info.note == null) AnalyzerRunStatus.ANALYZED else AnalyzerRunStatus.PARTIAL,
                        info.note
                    )
                }
            }
        } catch (_: ScanCancelledException) {
            return buildPartialResult(root.name ?: "(unnamed)")
        }

        val unityCandidates = files.filter { isUnityHeaderCandidate(it.name) }.sortedBy { entry ->
            when {
                entry.name.equals("globalgamemanagers", true) -> 0
                entry.name.equals("globalgamemanagers.assets", true) -> 1
                entry.name.startsWith("level", true) -> 2
                else -> 3
            }
        }
        unityHeaderCandidatePaths.addAll(unityCandidates.map { it.path })
        if (unityCandidates.size > MAX_UNITY_HEADER_FILES) addIssue(
            code = "unity.header_candidate_limit_reached",
            stage = ScanIssueStage.UNITY_FILE_HEADERS,
            severity = ScanIssueSeverity.WARNING,
            message = "Проверка Unity-заголовков ограничена первыми " + MAX_UNITY_HEADER_FILES + " файлами-кандидатами."
        )

        val unityHeaderRunIds = mutableMapOf<String, String>()
        unityCandidates.forEachIndexed { index, entry ->
            val limited = index >= MAX_UNITY_HEADER_FILES
            val reason = if (limited) {
                "Unity header candidate limit reached ($MAX_UNITY_HEADER_FILES); file kind and SerializedFile metadata were not inspected."
            } else {
                null
            }
            unityHeaderRunIds[entry.record.id] = addAnalyzerRun(
                analyzerId = "unity_file_headers",
                entry = entry,
                status = if (limited) AnalyzerRunStatus.SKIPPED else AnalyzerRunStatus.QUEUED,
                reason = reason
            )
            if (limited) {
                addAnalyzerRun(
                    analyzerId = "serialized_file_metadata",
                    entry = entry,
                    status = AnalyzerRunStatus.SKIPPED,
                    reason = reason
                )
            }
        }

        try {
            unityCandidates.take(MAX_UNITY_HEADER_FILES).forEachIndexed { index, entry ->
                if (shouldCancel()) throw ScanCancelledException()
                val deepMetadataRead = index < MAX_UNITY_METADATA_FILES
                if (unityHeaderAttemptedPaths.add(entry.path)) {
                    unityHeaderAttemptedCount = unityHeaderAttemptedPaths.size
                }
                val headerRunId = unityHeaderRunIds.getValue(entry.record.id)

                var prefix: ByteArray? = null
                var header: UnityFileHeader? = null
                var headerDiagnostic: String? = null
                try {
                    val readLimit = if (deepMetadataRead) MAX_UNITY_METADATA_READ else MAX_UNITY_HEADER_READ
                    val readPrefix = readPrefix(entry.node, readLimit)
                    prefix = readPrefix
                    if (readPrefix == null) {
                        headerDiagnostic = "не удалось открыть файл для чтения"
                    } else {
                        val parsed = UnityFileHeaderParser.parse(readPrefix, entry.size)
                        if (parsed == null) {
                            headerDiagnostic = "заголовок не распознан в прочитанном диапазоне"
                        } else {
                            header = parsed
                            unityHeaders[entry.path] = parsed
                        }
                    }
                } catch (e: Exception) {
                    headerDiagnostic = e.message ?: e.javaClass.simpleName
                }

                val parsedHeader = header
                if (parsedHeader == null) {
                    if (unityHeaderFailedPaths.add(entry.path)) {
                        unityHeaderFailedCount = unityHeaderFailedPaths.size
                    }
                    if (unityMetadataCandidatePaths.add(entry.path)) {
                        unityMetadataCandidateCount = unityMetadataCandidatePaths.size
                    }
                    if (unityMetadataPrerequisiteSkippedPaths.add(entry.path)) {
                        unityMetadataPrerequisiteSkippedCount = unityMetadataPrerequisiteSkippedPaths.size
                    }
                    val reason = headerDiagnostic ?: "Unity header prerequisite did not produce a recognized format."
                    updateAnalyzerRun(headerRunId, AnalyzerRunStatus.FAILED, reason)
                    addAnalyzerRun(
                        analyzerId = "serialized_file_metadata",
                        entry = entry,
                        status = AnalyzerRunStatus.SKIPPED,
                        reason = "SerializedFile metadata was not attempted because the Unity header was not recognized: $reason"
                    )
                    addIssue(
                        code = "unity.header_analysis_failed",
                        stage = ScanIssueStage.UNITY_FILE_HEADERS,
                        severity = ScanIssueSeverity.WARNING,
                        message = "Unity-анализ " + entry.path + ": " + reason,
                        path = entry.path
                    )
                    return@forEachIndexed
                }

                unityHeaderFailedPaths.remove(entry.path)
                unityHeaderFailedCount = unityHeaderFailedPaths.size
                updateAnalyzerRun(headerRunId, AnalyzerRunStatus.ANALYZED, parsedHeader.note)
                if (parsedHeader.kind != "SerializedFile") return@forEachIndexed

                if (unityMetadataCandidatePaths.add(entry.path)) {
                    unityMetadataCandidateCount = unityMetadataCandidatePaths.size
                }
                if (!deepMetadataRead) {
                    if (unityMetadataWindowLimitedPaths.add(entry.path)) {
                        unityMetadataWindowLimitedCount = unityMetadataWindowLimitedPaths.size
                    }
                    addAnalyzerRun(
                        analyzerId = "serialized_file_metadata",
                        entry = entry,
                        status = AnalyzerRunStatus.SKIPPED,
                        reason = "Deep SerializedFile metadata reads are limited to the first $MAX_UNITY_METADATA_FILES candidates; only the Unity header was read."
                    )
                    return@forEachIndexed
                }

                if (unityMetadataAttemptedPaths.add(entry.path)) {
                    unityMetadataAttemptedCount = unityMetadataAttemptedPaths.size
                }
                val metadataRunId = addAnalyzerRun("serialized_file_metadata", entry)
                var metadataSummary: UnityMetadataSummary? = null
                var metadataDiagnostic: String? = null
                try {
                    val metadataPrefix = requireNotNull(prefix)
                    val parsedMetadata = UnityMetadataParser.parse(metadataPrefix, parsedHeader)
                    if (parsedMetadata == null) {
                        metadataDiagnostic = "metadata не разобрана в доступном диапазоне"
                    } else {
                        val enrichedMetadata = readMonoScriptRecords(entry, parsedHeader, parsedMetadata)
                        metadataSummary = enrichedMetadata
                        unityMetadata[entry.path] = enrichedMetadata
                    }
                } catch (e: Exception) {
                    metadataDiagnostic = e.message ?: e.javaClass.simpleName
                }

                val summary = metadataSummary
                if (summary == null) {
                    if (unityMetadataFailedPaths.add(entry.path)) {
                        unityMetadataFailedCount = unityMetadataFailedPaths.size
                    }
                    val reason = metadataDiagnostic ?: "SerializedFile metadata parser returned no result."
                    updateAnalyzerRun(metadataRunId, AnalyzerRunStatus.FAILED, reason)
                    addIssue(
                        code = "unity.serialized_metadata_analysis_failed",
                        stage = ScanIssueStage.SERIALIZED_FILE_METADATA,
                        severity = ScanIssueSeverity.WARNING,
                        message = "Unity-анализ " + entry.path + ": " + reason,
                        path = entry.path
                    )
                } else {
                    unityMetadataFailedPaths.remove(entry.path)
                    unityMetadataFailedCount = unityMetadataFailedPaths.size
                    val summaryNote = summary.note
                    val isCompletedSummaryNote = summaryNote?.startsWith(
                        "Прочитаны таблицы объектов, MonoScript-ссылок, внешних файлов и SerializeReference types."
                    ) == true && !summaryNote.contains("shared TypeTree subtree payloads пока не разбираются.")
                    val metadataStatus = when {
                        (parsedHeader.formatVersion ?: -1L) !in 19L..26L -> AnalyzerRunStatus.UNSUPPORTED
                        summaryNote != null && !isCompletedSummaryNote -> AnalyzerRunStatus.PARTIAL
                        else -> AnalyzerRunStatus.ANALYZED
                    }
                    updateAnalyzerRun(
                        metadataRunId,
                        metadataStatus,
                        summaryNote?.takeUnless { isCompletedSummaryNote } ?:
                            if (metadataStatus == AnalyzerRunStatus.UNSUPPORTED) {
                                "SerializedFile format ${parsedHeader.formatVersion} is outside the supported metadata range 19-26."
                            } else {
                                null
                            }
                    )
                }
            }
        } catch (_: ScanCancelledException) {
            return buildPartialResult(root.name ?: "(unnamed)")
        }

        try {
            resolveExternalMonoScriptRecords(files, shouldCancel)
        } catch (_: ScanCancelledException) {
            return buildPartialResult(root.name ?: "(unnamed)")
        }
        if (shouldCancel()) return buildPartialResult(root.name ?: "(unnamed)")
        try {
            resolveExternalScenePointers(files, shouldCancel)
        } catch (_: ScanCancelledException) {
            return buildPartialResult(root.name ?: "(unnamed)")
        }
        if (shouldCancel()) return buildPartialResult(root.name ?: "(unnamed)")
        try {
            resolveMonoBehaviourScriptBindings(files, shouldCancel)
        } catch (_: ScanCancelledException) {
            return buildPartialResult(root.name ?: "(unnamed)")
        }
        if (shouldCancel()) return buildPartialResult(root.name ?: "(unnamed)")
        managedTypeResolutions.addAll(UnityManagedTypeResolver.resolve(unityMetadata, clrResults))
        if (shouldCancel()) return buildPartialResult(root.name ?: "(unnamed)")
        val unityVersion = collectUnityVersion(files)

        val managedBinaries = peResults.filterValues { it.managed }.keys
        val nativeBinaries = peResults.filterValues { !it.managed }.keys
        val importSummary = peResults.values.flatMap { it.imports }
            .groupingBy { it.lowercase(Locale.ROOT) }.eachCount().entries.sortedByDescending { it.value }
        val coverage = buildScanCoverage(
            files.size,
            entries.size - files.size,
            peCandidates.size,
            unityHeaderCandidatePaths.size,
            hasGameAssembly || hasMetadata || hasIl2CppDir
        )

        val md = buildMarkdown(
            root.name ?: "(unnamed)", backend, unityVersion, gameAssemblies, unityModules,
            monoFolder, managedDir, hasGameAssembly, hasMetadata, hasIl2CppDir, hasUnityPlayer,
            managedBinaries, nativeBinaries, importSummary, unityHeaders, unityMetadata, coverage
        )
        val json = buildJson(
            root.name ?: "(unnamed)", backend, unityVersion, gameAssemblies, unityModules,
            managedBinaries, nativeBinaries, importSummary, unityHeaders, unityMetadata, coverage
        )
        return ScanResult(md, json)
    }

    private fun addIssue(
        code: String,
        stage: ScanIssueStage,
        severity: ScanIssueSeverity,
        message: String,
        path: String? = null
    ) {
        issues.add(ScanIssue(code, stage, severity, message, path))
    }

    private fun buildPartialResult(rootName: String): ScanResult =
        PartialReportBuilder.build(
            rootName,
            entries.map { it.record },
            totalBytes,
            scanId,
            analyzerRuns.toList(),
            findings.toList(),
            evidenceRecords.toList(),
            graphNodes.toList(),
            graphEdges.toList()
        )

    private fun addAnalyzerRun(
        analyzerId: String,
        entry: ScanEntry,
        status: AnalyzerRunStatus = AnalyzerRunStatus.QUEUED,
        reason: String? = null
    ): String {
        val run = AnalyzerRun.create(scanId, analyzerId, entry.record.id, status, reason)
        analyzerRuns.add(run)
        return run.id
    }

    /** Updates the deterministic run for one analyzer/file pair, never adding a duplicate. */
    private fun setAnalyzerRun(
        analyzerId: String,
        entry: ScanEntry,
        status: AnalyzerRunStatus,
        reason: String? = null
    ): String {
        val existing = analyzerRuns.firstOrNull {
            it.analyzerId == analyzerId && it.inputFileId == entry.record.id
        }
        if (existing != null) {
            updateAnalyzerRun(existing.id, status, reason)
            return existing.id
        }
        return addAnalyzerRun(analyzerId, entry, status, reason)
    }

    private fun serializedMetadataOutcome(
        header: UnityFileHeader,
        summary: UnityMetadataSummary
    ): Pair<AnalyzerRunStatus, String?> {
        val note = summary.note
        val complete = note?.startsWith(
            "Прочитаны таблицы объектов, MonoScript-ссылок, внешних файлов и SerializeReference types."
        ) == true && !note.contains("shared TypeTree subtree payloads пока не разбираются.")
        val status = when {
            (header.formatVersion ?: -1L) !in 19L..26L -> AnalyzerRunStatus.UNSUPPORTED
            note != null && !complete -> AnalyzerRunStatus.PARTIAL
            else -> AnalyzerRunStatus.ANALYZED
        }
        val reason = note?.takeUnless { complete } ?: if (status == AnalyzerRunStatus.UNSUPPORTED) {
            "SerializedFile format " + header.formatVersion + " is outside the supported metadata range 19-26."
        } else null
        return status to reason
    }

    private fun externalPointers(metadata: UnityMetadataSummary): List<UnityObjectPointerSummary> =
        buildList {
            metadata.decodedObjects.forEach { source ->
                source.gameObject?.let { add(it) }
                source.father?.let { add(it) }
                source.children.forEach { add(it) }
                source.components.forEach { add(it) }
                source.script?.let { add(it) }
            }
        }

    private fun externalPointerCandidateCount(metadata: UnityMetadataSummary): Int =
        externalPointers(metadata).count { it.fileId != 0 && it.pathId != 0L }

    private fun externalMonoScriptCandidateCount(metadata: UnityMetadataSummary): Int =
        metadata.scriptTypeBindings.count {
            (it.fileId ?: 0) > 0 && it.pathId != null && !it.externalPath.isNullOrBlank()
        }

    private fun monoBehaviourScriptCandidateCount(metadata: UnityMetadataSummary): Int =
        metadata.decodedObjects.count { it.classId == 114 && it.script != null }

    private fun updateAnalyzerRun(
        runId: String,
        status: AnalyzerRunStatus,
        reason: String? = null
    ) {
        val index = analyzerRuns.indexOfFirst { it.id == runId }
        check(index >= 0) { "Unknown analyzer run: $runId" }
        analyzerRuns[index] = analyzerRuns[index].copy(status = status, reason = reason)
    }

    private fun walk(
        dir: ScanNode,
        parent: String,
        depth: Int,
        directoryEntry: ScanEntry?,
        shouldCancel: () -> Boolean,
        onProgress: (Int, String) -> Unit
    ) {
        if (shouldCancel()) {
            directoryEntry?.inventoryStatus = FileInventoryStatus.PARTIAL
            throw ScanCancelledException()
        }
        if (depth > MAX_DEPTH) {
            directoryEntry?.inventoryStatus = FileInventoryStatus.PARTIAL
            depthLimitHitCount++
            addIssue(
                code = "inventory.depth_limit_reached",
                stage = ScanIssueStage.INVENTORY,
                severity = ScanIssueSeverity.WARNING,
                message = "Достигнут лимит глубины " + MAX_DEPTH + " в " + parent,
                path = parent.ifBlank { null }
            )
            return
        }
        if (entries.size >= MAX_ENTRIES) {
            directoryEntry?.inventoryStatus = FileInventoryStatus.PARTIAL
            entryLimitReached = true
            addIssue(
                code = "inventory.entry_limit_reached",
                stage = ScanIssueStage.INVENTORY,
                severity = ScanIssueSeverity.WARNING,
                message = "Достигнут лимит " + MAX_ENTRIES + " записей; дерево неполное.",
                path = parent.ifBlank { null }
            )
            return
        }
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            directoryEntry?.inventoryStatus = FileInventoryStatus.INACCESSIBLE
            inaccessibleDirectoryCount++
            addIssue(
                code = "inventory.directory_listing_failed",
                stage = ScanIssueStage.INVENTORY,
                severity = ScanIssueSeverity.ERROR,
                message = "Не удалось прочитать каталог " + parent + ": " + e.message,
                path = parent.ifBlank { null }
            )
            return
        }
        directoryEntry?.inventoryStatus = FileInventoryStatus.ENUMERATED
        val siblingOccurrences = mutableMapOf<String, Int>()
        for (child in children) {
            if (shouldCancel()) {
                directoryEntry?.inventoryStatus = FileInventoryStatus.PARTIAL
                throw ScanCancelledException()
            }
            if (entries.size >= MAX_ENTRIES) {
                directoryEntry?.inventoryStatus = FileInventoryStatus.PARTIAL
                entryLimitReached = true
                addIssue(
                    code = "inventory.entry_limit_reached",
                    stage = ScanIssueStage.INVENTORY,
                    severity = ScanIssueSeverity.WARNING,
                    message = "Достигнут лимит " + MAX_ENTRIES + " записей; дерево неполное.",
                    path = parent.ifBlank { null }
                )
                break
            }
            val name = child.name ?: "(unnamed)"
            val path = if (parent.isEmpty()) name else parent + "/" + name
            val siblingOccurrence = siblingOccurrences[name] ?: 0
            siblingOccurrences[name] = siblingOccurrence + 1
            val isDir = child.isDirectory
            val size = if (isDir) 0L else child.size.coerceAtLeast(0L)
            val record = FileRecord.create(
                path = path,
                name = name,
                directory = isDir,
                size = size,
                parentId = directoryEntry?.record?.id,
                providerKey = child.stableKey,
                siblingOccurrence = siblingOccurrence
            )
            val entry = ScanEntry(record, child)
            entries.add(entry)
            onProgress(entries.size, path)
            if (!isDir) totalBytes += size
            if (isDir) {
                try {
                    walk(child, path, depth + 1, entry, shouldCancel, onProgress)
                } catch (cancelled: ScanCancelledException) {
                    directoryEntry?.inventoryStatus = FileInventoryStatus.PARTIAL
                    throw cancelled
                }
            }
        }
    }

    private fun buildScanCoverage(
        fileCount: Int,
        directoryCount: Int,
        peCandidateCount: Int,
        unityCandidateCount: Int,
        hasIl2CppEvidence: Boolean
    ): ScanCoverage {
        val peSkipped = (peCandidateCount - peAttemptedCount).coerceAtLeast(0)
        val unityHeaderSkipped = (unityCandidateCount - unityHeaderAttemptedCount).coerceAtLeast(0)
        val unityCandidatesNotInspected = (unityCandidateCount - unityHeaderAttemptedCount).coerceAtLeast(0)
        val metadataSkipped = unityMetadataPrerequisiteSkippedCount + unityCandidatesNotInspected
        val metadataCandidates = unityMetadataCandidateCount + unityCandidatesNotInspected

        val clrPartial = clrResults.values.count { it.status.startsWith("partial-") }
        val clrParsed = clrResults.values.count { it.status == "parsed" }
        val clrOtherStatus = clrResults.size - clrPartial - clrParsed
        val clrFailed = clrFailedCount + clrOtherStatus

        val unityFsCount = unityHeaders.values.count { it.kind == "UnityFS bundle" }
        val analyzers = listOf(
            AnalyzerCoverage(
                id = "pe",
                candidateCount = peCandidateCount,
                attemptedCount = peAttemptedCount,
                parsedCount = peResults.size,
                failedCount = peFailedCount,
                skippedCount = peSkipped,
                notes = listOf("PE inspection is limited to the first $MAX_PE_FILES EXE/DLL candidates.")
            ),
            AnalyzerCoverage(
                id = "clr_metadata",
                candidateCount = clrAttemptedCount,
                attemptedCount = clrAttemptedCount,
                parsedCount = clrParsed,
                partialCount = clrPartial,
                failedCount = clrFailed,
                limitedCount = peSkipped,
                notes = listOf(
                    "Only managed PE files detected among inspected PE candidates are counted; managed files beyond the PE limit are unknown."
                )
            ),
            AnalyzerCoverage(
                id = "unity_file_headers",
                candidateCount = unityCandidateCount,
                attemptedCount = unityHeaderAttemptedCount,
                parsedCount = unityHeaders.size,
                failedCount = unityHeaderFailedCount,
                skippedCount = unityHeaderSkipped,
                notes = listOf("Unity header inspection is limited to the first $MAX_UNITY_HEADER_FILES filename candidates.")
            ),
            AnalyzerCoverage(
                id = "serialized_file_metadata",
                candidateCount = metadataCandidates,
                attemptedCount = unityMetadataAttemptedCount,
                parsedCount = unityMetadata.size,
                failedCount = unityMetadataFailedCount,
                limitedCount = unityMetadataWindowLimitedCount,
                skippedCount = metadataSkipped,
                notes = listOf(
                    "Deep metadata reads (up to $MAX_UNITY_METADATA_READ bytes) are limited to the first $MAX_UNITY_METADATA_FILES candidates; later candidates receive header-only reads."
                )
            ),
            AnalyzerCoverage(
                id = "unityfs_contents",
                candidateCount = unityFsCount,
                attemptedCount = 0,
                parsedCount = 0,
                notes = listOf("UnityFS headers are recognized, but block data and bundle entries are not unpacked."),
                forcedStatus = if (unityFsCount > 0) "unsupported" else "not-applicable"
            ),
            AnalyzerCoverage(
                id = "il2cpp_metadata",
                candidateCount = if (hasIl2CppEvidence) 1 else 0,
                attemptedCount = 0,
                parsedCount = 0,
                notes = listOf("IL2CPP is identified from structural markers only; global-metadata.dat is not parsed."),
                forcedStatus = if (hasIl2CppEvidence) "unsupported" else "not-applicable"
            )
        )
        val inventoryStatus = if (
            inaccessibleDirectoryCount > 0 || depthLimitHitCount > 0 || entryLimitReached
        ) "partial" else "completed"
        return ScanCoverage(
            inventoryStatus = inventoryStatus,
            entriesDiscovered = entries.size,
            filesDiscovered = fileCount,
            directoriesDiscovered = directoryCount,
            totalFileBytes = totalBytes,
            inaccessibleDirectoryCount = inaccessibleDirectoryCount,
            depthLimitHitCount = depthLimitHitCount,
            entryLimitReached = entryLimitReached,
            analyzers = analyzers
        )
    }

    private fun isUnityHeaderCandidate(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".assets") || lower.endsWith(".bundle") ||
            lower.endsWith(".unity3d") || lower.endsWith(".assetbundle") ||
            lower == "globalgamemanagers" || lower.startsWith("level")
    }

    private fun collectUnityVersion(files: List<ScanEntry>): UnityVersionResult {
        val evidence = mutableListOf<UnityVersionEvidence>()

        unityMetadata.forEach { (path, metadata) ->
            val value = metadata.unityVersion ?: return@forEach
            val fileName = path.substringAfterLast('/')
            val isRootFile = fileName.equals("globalgamemanagers", true) ||
                fileName.equals("globalgamemanagers.assets", true)
            UnityVersionResolver.extractVersions(value).forEach { version ->
                evidence.add(UnityVersionEvidence(
                    version,
                    if (isRootFile) "globalgamemanagers-metadata" else "serializedfile-metadata",
                    path,
                    if (isRootFile) "high" else "medium"
                ))
            }
        }

        unityHeaders.forEach { (path, header) ->
            if (header.kind == "UnityFS bundle") {
                header.engineVersion?.let { engineVersion ->
                    UnityVersionResolver.extractVersions(engineVersion).forEach { version ->
                        evidence.add(UnityVersionEvidence(
                            version, "unityfs-engine-version", path, "medium"
                        ))
                    }
                }
            }
        }

        // Text fallback is used only when the root SerializedFile metadata
        // did not provide an exact version string.
        if (evidence.none { it.source == "globalgamemanagers-metadata" }) {
            files.filter {
                it.name.equals("globalgamemanagers", true) ||
                    it.name.equals("globalgamemanagers.assets", true)
            }.take(2).forEach { entry ->
                try {
                    val bytes = readPrefix(entry.node, MAX_GGM_READ) ?: return@forEach
                    val text = String(bytes, Charsets.ISO_8859_1)
                    UnityVersionResolver.extractVersions(text).take(8).forEach { version ->
                        evidence.add(UnityVersionEvidence(
                            version, "globalgamemanagers-raw", entry.path, "medium"
                        ))
                    }
                } catch (e: Exception) {
                    addIssue(
                        code = "unity.version_read_failed",
                        stage = ScanIssueStage.UNITY_VERSION,
                        severity = ScanIssueSeverity.WARNING,
                        message = "Не удалось прочитать " + entry.path + " для поиска версии Unity: " + e.message,
                        path = entry.path
                    )
                }
            }
        }

        return UnityVersionResolver.resolve(evidence)
    }

    private data class ExternalMonoScriptRequest(
        val ownerPath: String,
        val bindingIndex: Int,
        val targetEntry: ScanEntry,
        val targetHeader: UnityFileHeader,
        val target: MonoScriptObjectTarget
    )

    private fun resolveExternalMonoScriptRecords(
        files: List<ScanEntry>,
        shouldCancel: () -> Boolean
    ) {
        val metadataSnapshot = unityMetadata.toMap()
        val updatedBindings = metadataSnapshot.mapValues { it.value.scriptTypeBindings.toMutableList() }.toMutableMap()
        val changedOwners = mutableSetOf<String>()
        val requests = mutableListOf<ExternalMonoScriptRequest>()
        val discoveredMetadata = linkedMapOf<String, UnityMetadataSummary>()
        val attemptedMetadata = mutableSetOf<String>()
        val ownerRuns = linkedMapOf<String, String>()
        var extraMetadataReads = 0

        metadataSnapshot.forEach { (ownerPath, metadata) ->
            val owner = files.firstOrNull { it.path == ownerPath } ?: return@forEach
            val count = externalMonoScriptCandidateCount(metadata)
            ownerRuns[ownerPath] = addAnalyzerRun(
                "unity_external_monoscript", owner,
                if (count == 0) AnalyzerRunStatus.SKIPPED else AnalyzerRunStatus.QUEUED,
                if (count == 0) "No external MonoScript references were present in the bounded metadata sample." else null
            )
        }
        if (shouldCancel()) throw ScanCancelledException()

        metadataSnapshot.forEach { (ownerPath, metadata) ->
            if (shouldCancel()) throw ScanCancelledException()
            metadata.scriptTypeBindings.forEachIndexed { index, binding ->
                if (shouldCancel()) throw ScanCancelledException()
                val fileId = binding.fileId ?: return@forEachIndexed
                if (fileId <= 0 || binding.pathId == null || binding.externalPath.isNullOrBlank()) {
                    return@forEachIndexed
                }
                val match = resolveExternalFile(binding.externalPath, files)
                val current = updatedBindings[ownerPath]?.getOrNull(index) ?: binding
                if (match.entry == null) {
                    val resolution = if (match.ambiguous) "external-file-name-ambiguous" else "external-file-not-matched"
                    updatedBindings[ownerPath]?.set(index, current.copy(resolution = resolution))
                    changedOwners.add(ownerPath)
                    return@forEachIndexed
                }

                val targetEntry = match.entry
                var targetHeader = unityHeaders[targetEntry.path]
                var targetMetadata = metadataSnapshot[targetEntry.path] ?: discoveredMetadata[targetEntry.path]

                if (targetMetadata == null && targetHeader != null && targetHeader.kind != "SerializedFile") {
                    val reason = "External MonoScript target is not a SerializedFile; kind=" + targetHeader.kind + "."
                    setAnalyzerRun("serialized_file_metadata", targetEntry, AnalyzerRunStatus.SKIPPED, reason)
                    updatedBindings[ownerPath]?.set(index, current.copy(
                        matchedExternalFilePath = targetEntry.path,
                        resolution = "external-file-found-not-parsed"
                    ))
                    changedOwners.add(ownerPath)
                    return@forEachIndexed
                }

                if (targetMetadata == null && targetEntry.path !in attemptedMetadata) {
                    if (extraMetadataReads >= MAX_EXTERNAL_METADATA_FILES) {
                        updatedBindings[ownerPath]?.set(index, current.copy(
                            matchedExternalFilePath = targetEntry.path,
                            resolution = "external-metadata-limit-reached"
                        ))
                        changedOwners.add(ownerPath)
                        return@forEachIndexed
                    }
                    attemptedMetadata.add(targetEntry.path)
                    extraMetadataReads++
                    unityHeaderCandidatePaths.add(targetEntry.path)
                    if (unityHeaderAttemptedPaths.add(targetEntry.path)) {
                        unityHeaderAttemptedCount = unityHeaderAttemptedPaths.size
                    }
                    try {
                        val prefix = readPrefix(targetEntry.node, MAX_UNITY_METADATA_READ)
                        if (prefix == null) {
                            val reason = "External MonoScript target provider returned no input stream."
                            if (targetHeader == null) {
                                if (unityHeaderFailedPaths.add(targetEntry.path)) {
                                    unityHeaderFailedCount = unityHeaderFailedPaths.size
                                }
                                setAnalyzerRun("unity_file_headers", targetEntry, AnalyzerRunStatus.FAILED, reason)
                            }
                            if (unityMetadataCandidatePaths.add(targetEntry.path)) {
                                unityMetadataCandidateCount = unityMetadataCandidatePaths.size
                            }
                            if (unityMetadataAttemptedPaths.add(targetEntry.path)) {
                                unityMetadataAttemptedCount = unityMetadataAttemptedPaths.size
                            }
                            if (unityMetadataFailedPaths.add(targetEntry.path)) {
                                unityMetadataFailedCount = unityMetadataFailedPaths.size
                            }
                            setAnalyzerRun("serialized_file_metadata", targetEntry, AnalyzerRunStatus.FAILED, reason)
                            updatedBindings[ownerPath]?.set(index, current.copy(
                                matchedExternalFilePath = targetEntry.path,
                                resolution = "external-file-found-not-parsed"
                            ))
                            changedOwners.add(ownerPath)
                            return@forEachIndexed
                        }

                        if (targetHeader == null) {
                            targetHeader = UnityFileHeaderParser.parse(prefix, targetEntry.size)
                            if (targetHeader != null) unityHeaders[targetEntry.path] = targetHeader
                        }
                        if (targetHeader == null) {
                            val reason = "External MonoScript target did not contain a recognized Unity file header."
                            if (unityHeaderFailedPaths.add(targetEntry.path)) {
                                unityHeaderFailedCount = unityHeaderFailedPaths.size
                            }
                            setAnalyzerRun("unity_file_headers", targetEntry, AnalyzerRunStatus.FAILED, reason)
                            if (unityMetadataCandidatePaths.add(targetEntry.path)) {
                                unityMetadataCandidateCount = unityMetadataCandidatePaths.size
                            }
                            if (unityMetadataAttemptedPaths.add(targetEntry.path)) {
                                unityMetadataAttemptedCount = unityMetadataAttemptedPaths.size
                            }
                            if (unityMetadataFailedPaths.add(targetEntry.path)) {
                                unityMetadataFailedCount = unityMetadataFailedPaths.size
                            }
                            setAnalyzerRun("serialized_file_metadata", targetEntry, AnalyzerRunStatus.FAILED, reason)
                            updatedBindings[ownerPath]?.set(index, current.copy(
                                matchedExternalFilePath = targetEntry.path,
                                resolution = "external-file-found-not-parsed"
                            ))
                            changedOwners.add(ownerPath)
                            return@forEachIndexed
                        }

                        unityHeaderFailedPaths.remove(targetEntry.path)
                        unityHeaderFailedCount = unityHeaderFailedPaths.size
                        setAnalyzerRun("unity_file_headers", targetEntry, AnalyzerRunStatus.ANALYZED, targetHeader.note)
                        if (targetHeader.kind != "SerializedFile") {
                            if (unityMetadataCandidatePaths.remove(targetEntry.path)) {
                                unityMetadataCandidateCount = unityMetadataCandidatePaths.size
                            }
                            if (unityMetadataPrerequisiteSkippedPaths.remove(targetEntry.path)) {
                                unityMetadataPrerequisiteSkippedCount = unityMetadataPrerequisiteSkippedPaths.size
                            }
                            if (unityMetadataWindowLimitedPaths.remove(targetEntry.path)) {
                                unityMetadataWindowLimitedCount = unityMetadataWindowLimitedPaths.size
                            }
                            val reason = "External MonoScript target is not a SerializedFile; kind=" + targetHeader.kind + "."
                            setAnalyzerRun("serialized_file_metadata", targetEntry, AnalyzerRunStatus.SKIPPED, reason)
                            updatedBindings[ownerPath]?.set(index, current.copy(
                                matchedExternalFilePath = targetEntry.path,
                                resolution = "external-file-found-not-parsed"
                            ))
                            changedOwners.add(ownerPath)
                            return@forEachIndexed
                        }

                        if (unityMetadataCandidatePaths.add(targetEntry.path)) {
                            unityMetadataCandidateCount = unityMetadataCandidatePaths.size
                        }
                        if (unityMetadataPrerequisiteSkippedPaths.remove(targetEntry.path)) {
                            unityMetadataPrerequisiteSkippedCount = unityMetadataPrerequisiteSkippedPaths.size
                        }
                        if (unityMetadataWindowLimitedPaths.remove(targetEntry.path)) {
                            unityMetadataWindowLimitedCount = unityMetadataWindowLimitedPaths.size
                        }
                        if (unityMetadataAttemptedPaths.add(targetEntry.path)) {
                            unityMetadataAttemptedCount = unityMetadataAttemptedPaths.size
                        }
                        val parsedMetadata = UnityMetadataParser.parse(prefix, targetHeader)
                        if (parsedMetadata == null) {
                            val reason = "External MonoScript target SerializedFile metadata could not be parsed in the bounded window."
                            if (unityMetadataFailedPaths.add(targetEntry.path)) {
                                unityMetadataFailedCount = unityMetadataFailedPaths.size
                            }
                            setAnalyzerRun("serialized_file_metadata", targetEntry, AnalyzerRunStatus.FAILED, reason)
                            updatedBindings[ownerPath]?.set(index, current.copy(
                                matchedExternalFilePath = targetEntry.path,
                                resolution = "external-file-found-not-parsed"
                            ))
                            changedOwners.add(ownerPath)
                            return@forEachIndexed
                        }
                        unityMetadataFailedPaths.remove(targetEntry.path)
                        unityMetadataFailedCount = unityMetadataFailedPaths.size
                        val enriched = readMonoScriptRecords(targetEntry, targetHeader, parsedMetadata)
                        targetMetadata = enriched
                        discoveredMetadata[targetEntry.path] = enriched
                        val outcome = serializedMetadataOutcome(targetHeader, enriched)
                        setAnalyzerRun("serialized_file_metadata", targetEntry, outcome.first, outcome.second)
                    } catch (e: ScanCancelledException) {
                        throw e
                    } catch (e: Exception) {
                        val reason = e.message ?: e.javaClass.simpleName
                        if (targetHeader == null) {
                            setAnalyzerRun("unity_file_headers", targetEntry, AnalyzerRunStatus.FAILED, reason)
                        }
                        if (unityMetadataAttemptedPaths.add(targetEntry.path)) {
                            unityMetadataAttemptedCount = unityMetadataAttemptedPaths.size
                        }
                        if (unityMetadataFailedPaths.add(targetEntry.path)) {
                            unityMetadataFailedCount = unityMetadataFailedPaths.size
                        }
                        setAnalyzerRun("serialized_file_metadata", targetEntry, AnalyzerRunStatus.FAILED, reason)
                        updatedBindings[ownerPath]?.set(index, current.copy(
                            matchedExternalFilePath = targetEntry.path,
                            resolution = "external-file-found-not-parsed"
                        ))
                        changedOwners.add(ownerPath)
                        return@forEachIndexed
                    }
                }

                if (targetMetadata == null) {
                    updatedBindings[ownerPath]?.set(index, current.copy(
                        matchedExternalFilePath = targetEntry.path,
                        resolution = "external-file-found-not-parsed"
                    ))
                    changedOwners.add(ownerPath)
                    return@forEachIndexed
                }
                val targetObject = targetMetadata.monoScriptObjects.firstOrNull { it.pathId == binding.pathId }
                if (targetObject == null) {
                    updatedBindings[ownerPath]?.set(index, current.copy(
                        matchedExternalFilePath = targetEntry.path,
                        resolution = "external-monoscript-not-in-object-sample"
                    ))
                    changedOwners.add(ownerPath)
                    return@forEachIndexed
                }
                if (targetHeader == null || targetHeader.dataOffset == null || targetHeader.fileSize == null) {
                    updatedBindings[ownerPath]?.set(index, current.copy(
                        matchedExternalFilePath = targetEntry.path,
                        resolution = "external-file-header-unavailable"
                    ))
                    changedOwners.add(ownerPath)
                    return@forEachIndexed
                }
                updatedBindings[ownerPath]?.set(index, current.copy(
                    matchedExternalFilePath = targetEntry.path,
                    targetObjectClassId = targetObject.classId,
                    objectByteStart = targetObject.byteStart,
                    objectByteSize = targetObject.byteSize,
                    resolution = "external-monoscript-object-found"
                ))
                changedOwners.add(ownerPath)
                requests.add(ExternalMonoScriptRequest(
                    ownerPath, index, targetEntry, targetHeader,
                    MonoScriptObjectTarget(targetObject.pathId, targetObject.byteStart, targetObject.byteSize)
                ))
            }
        }

        discoveredMetadata.forEach { (path, metadata) ->
            if (!unityMetadata.containsKey(path)) unityMetadata[path] = metadata
        }

        val extraScripts = mutableMapOf<String, MutableList<UnityMonoScriptSummary>>()
        val extraTargetScripts = mutableMapOf<String, MutableList<UnityMonoScriptSummary>>()
        val groupedRequests = requests.groupBy { it.targetEntry.path }
        val payloadRunIds = groupedRequests.mapValues { (_, group) ->
            setAnalyzerRun("unity_external_monoscript_payload", group.first().targetEntry, AnalyzerRunStatus.QUEUED)
        }
        groupedRequests.forEach { (targetPath, group) ->
            if (shouldCancel()) throw ScanCancelledException()
            val first = group.first()
            val runId = payloadRunIds.getValue(targetPath)
            val littleEndian = when (first.targetHeader.endianness) {
                "Little Endian" -> true
                "Big Endian" -> false
                else -> null
            }
            if (littleEndian == null) {
                updateAnalyzerRun(runId, AnalyzerRunStatus.SKIPPED, "External MonoScript target endianness is unknown.")
                group.forEach { request ->
                    val bindings = updatedBindings[request.ownerPath] ?: return@forEach
                    val current = bindings.getOrNull(request.bindingIndex) ?: return@forEach
                    bindings[request.bindingIndex] = current.copy(resolution = "external-file-endianness-unknown")
                    changedOwners.add(request.ownerPath)
                }
                return@forEach
            }
            try {
                val input = fileAccess.openInputStream(first.targetEntry.node)
                if (input == null) {
                    updateAnalyzerRun(runId, AnalyzerRunStatus.FAILED, "External MonoScript target provider returned no input stream.")
                    group.forEach { request ->
                        val bindings = updatedBindings[request.ownerPath] ?: return@forEach
                        val current = bindings.getOrNull(request.bindingIndex) ?: return@forEach
                        bindings[request.bindingIndex] = current.copy(resolution = "external-file-unreadable")
                        changedOwners.add(request.ownerPath)
                    }
                    return@forEach
                }
                val readResult = input.use { stream ->
                    MonoScriptParser.readObjects(
                        stream, first.targetHeader.dataOffset!!, first.targetHeader.fileSize!!,
                        group.map { it.target }, littleEndian
                    )
                }
                val parsedByPath = readResult.scripts.associateBy { it.pathId }
                val expectedIds = group.map { it.target.pathId }.toSet()
                val foundIds = parsedByPath.keys
                val payloadStatus = when {
                    expectedIds.isNotEmpty() && foundIds.containsAll(expectedIds) &&
                        readResult.note == null && readResult.skippedCount == 0 -> AnalyzerRunStatus.ANALYZED
                    readResult.scripts.isNotEmpty() -> AnalyzerRunStatus.PARTIAL
                    else -> AnalyzerRunStatus.FAILED
                }
                val payloadReason = if (payloadStatus == AnalyzerRunStatus.ANALYZED) null else
                    readResult.note ?: ("Decoded " + foundIds.size + " of " + expectedIds.size +
                        " requested external MonoScript payloads.")
                updateAnalyzerRun(runId, payloadStatus, payloadReason)
                group.forEach { request ->
                    val bindings = updatedBindings[request.ownerPath] ?: return@forEach
                    val current = bindings.getOrNull(request.bindingIndex) ?: return@forEach
                    val script = parsedByPath[request.target.pathId]
                    if (script == null) {
                        bindings[request.bindingIndex] = current.copy(resolution = "external-monoscript-unreadable")
                    } else {
                        bindings[request.bindingIndex] = current.copy(
                            monoScriptName = script.name, className = script.className,
                            namespace = script.namespace, assemblyName = script.assemblyName,
                            executionOrder = script.executionOrder, resolution = "external-monoscript-decoded"
                        )
                        extraScripts.getOrPut(request.ownerPath) { mutableListOf() }
                            .add(script.copy(sourceFilePath = request.targetEntry.path))
                        extraTargetScripts.getOrPut(request.targetEntry.path) { mutableListOf() }
                            .add(script.copy(sourceFilePath = request.targetEntry.path))
                    }
                    changedOwners.add(request.ownerPath)
                }
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                updateAnalyzerRun(runId, AnalyzerRunStatus.FAILED, reason)
                group.forEach { request ->
                    val bindings = updatedBindings[request.ownerPath] ?: return@forEach
                    val current = bindings.getOrNull(request.bindingIndex) ?: return@forEach
                    bindings[request.bindingIndex] = current.copy(resolution = "external-monoscript-read-failed")
                    changedOwners.add(request.ownerPath)
                }
            }
        }

        changedOwners.forEach { ownerPath ->
            val old = unityMetadata[ownerPath] ?: return@forEach
            val bindings = updatedBindings[ownerPath] ?: old.scriptTypeBindings
            val scripts = (old.monoScripts + extraScripts[ownerPath].orEmpty())
                .distinctBy { it.sourceFilePath to it.pathId }
            unityMetadata[ownerPath] = old.copy(scriptTypeBindings = bindings, monoScripts = scripts)
        }
        extraTargetScripts.forEach { (targetPath, scripts) ->
            val old = unityMetadata[targetPath] ?: return@forEach
            unityMetadata[targetPath] = old.copy(
                monoScripts = (old.monoScripts + scripts).distinctBy { it.sourceFilePath to it.pathId }
            )
        }
        metadataSnapshot.forEach { (ownerPath, _) ->
            val runId = ownerRuns[ownerPath] ?: return@forEach
            val candidates = updatedBindings[ownerPath].orEmpty().filter {
                (it.fileId ?: 0) > 0 && it.pathId != null && !it.externalPath.isNullOrBlank()
            }
            when {
                candidates.isEmpty() -> updateAnalyzerRun(
                    runId, AnalyzerRunStatus.SKIPPED,
                    "No external MonoScript references were present in the bounded metadata sample."
                )
                candidates.all { it.resolution == "external-monoscript-decoded" } ->
                    updateAnalyzerRun(runId, AnalyzerRunStatus.ANALYZED)
                else -> updateAnalyzerRun(
                    runId, AnalyzerRunStatus.PARTIAL,
                    "External MonoScript resolution completed with unresolved, ambiguous, limited or unreadable references."
                )
            }
        }
    }

    private fun resolveExternalScenePointers(
        files: List<ScanEntry>,
        shouldCancel: () -> Boolean
    ) {
        val snapshot = unityMetadata.toMap()
        val runs = linkedMapOf<String, String>()
        snapshot.forEach { (path, metadata) ->
            val owner = files.firstOrNull { it.path == path } ?: return@forEach
            val count = externalPointerCandidateCount(metadata)
            runs[path] = addAnalyzerRun(
                "unity_external_pointers", owner,
                if (count == 0) AnalyzerRunStatus.SKIPPED else AnalyzerRunStatus.QUEUED,
                if (count == 0) "No sampled external PPtrs were present in decoded scene objects." else null
            )
        }
        if (shouldCancel()) throw ScanCancelledException()
        val resolutions = try {
            UnityExternalPointerResolver.resolve(snapshot) { externalPath ->
                val match = resolveExternalFile(externalPath, files)
                if (match.ambiguous) null else match.entry?.path
            }
        } catch (e: Exception) {
            runs.forEach { (path, runId) ->
                val metadata = snapshot[path] ?: return@forEach
                if (externalPointerCandidateCount(metadata) > 0) {
                    updateAnalyzerRun(runId, AnalyzerRunStatus.FAILED, e.message ?: e.javaClass.simpleName)
                }
            }
            return
        }
        resolutions.forEach { (path, links) ->
            if (shouldCancel()) throw ScanCancelledException()
            val old = unityMetadata[path] ?: return@forEach
            unityMetadata[path] = old.copy(externalPointerResolutions = links)
            val count = externalPointerCandidateCount(old)
            val runId = runs[path] ?: return@forEach
            when {
                count == 0 -> updateAnalyzerRun(runId, AnalyzerRunStatus.SKIPPED, "No sampled external PPtrs were present in decoded scene objects.")
                links.size == count && count <= 2048 && links.all {
                    it.status == "resolved-decoded-object" || it.status == "resolved-object-record-not-decoded"
                } -> updateAnalyzerRun(runId, AnalyzerRunStatus.ANALYZED)
                else -> updateAnalyzerRun(
                    runId, AnalyzerRunStatus.PARTIAL,
                    if (count > 2048) "External PPtr resolution was capped at 2048 references for this file."
                    else "One or more external PPtrs were unresolved, ambiguous, or outside the sampled target metadata."
                )
            }
            val ownerEntry = files.firstOrNull { it.path == path } ?: return@forEach
            links.forEach { link ->
                if (shouldCancel()) throw ScanCancelledException()
                recordExternalPointerResolution(
                    ownerPath = path,
                    owner = ownerEntry,
                    metadata = old,
                    link = link,
                    analyzerRunId = runId,
                    files = files
                )
            }
        }
    }

    private fun resolveMonoBehaviourScriptBindings(
        files: List<ScanEntry>,
        shouldCancel: () -> Boolean
    ) {
        val snapshot = unityMetadata.toMap()
        val runs = linkedMapOf<String, String>()
        snapshot.forEach { (path, metadata) ->
            val owner = files.firstOrNull { it.path == path } ?: return@forEach
            val count = monoBehaviourScriptCandidateCount(metadata)
            runs[path] = addAnalyzerRun(
                "unity_monobehaviour_script_bindings", owner,
                if (count == 0) AnalyzerRunStatus.SKIPPED else AnalyzerRunStatus.QUEUED,
                if (count == 0) "No decoded MonoBehaviour m_Script pointers were present in the bounded object sample." else null
            )
        }
        if (shouldCancel()) throw ScanCancelledException()
        val bindings = try {
            UnityMonoBehaviourScriptResolver.resolve(snapshot) { externalPath ->
                val match = resolveExternalFile(externalPath, files)
                if (match.ambiguous) null else match.entry?.path
            }
        } catch (e: Exception) {
            runs.forEach { (path, runId) ->
                val metadata = snapshot[path] ?: return@forEach
                if (monoBehaviourScriptCandidateCount(metadata) > 0) {
                    updateAnalyzerRun(runId, AnalyzerRunStatus.FAILED, e.message ?: e.javaClass.simpleName)
                }
            }
            return
        }
        bindings.forEach { (path, items) ->
            if (shouldCancel()) throw ScanCancelledException()
            val old = unityMetadata[path] ?: return@forEach
            unityMetadata[path] = old.copy(monoBehaviourScriptBindings = items)
            val count = monoBehaviourScriptCandidateCount(old)
            val runId = runs[path] ?: return@forEach
            when {
                count == 0 -> updateAnalyzerRun(runId, AnalyzerRunStatus.SKIPPED, "No decoded MonoBehaviour m_Script pointers were present in the bounded object sample.")
                items.size == count && items.all { it.status == "resolved-monoscript-class" } && count <= 1024 ->
                    updateAnalyzerRun(runId, AnalyzerRunStatus.ANALYZED)
                else -> updateAnalyzerRun(
                    runId, AnalyzerRunStatus.PARTIAL,
                    if (count > 1024) "MonoBehaviour m_Script binding was capped at 1024 objects for this file."
                    else "One or more MonoBehaviour m_Script references were null, unresolved, ambiguous or lacked decoded MonoScript payload."
                )
            }

            val ownerEntry = files.firstOrNull { it.path == path } ?: return@forEach
            items.forEach { binding ->
                if (shouldCancel()) throw ScanCancelledException()
                recordMonoBehaviourScriptBinding(
                    ownerPath = path,
                    owner = ownerEntry,
                    metadata = old,
                    binding = binding,
                    analyzerRunId = runId,
                    files = files
                )
            }
        }
    }

    private fun recordExternalPointerResolution(
        ownerPath: String,
        owner: ScanEntry,
        metadata: UnityMetadataSummary,
        link: UnityExternalPointerResolution,
        analyzerRunId: String,
        files: List<ScanEntry>
    ) {
        // MonoBehaviour.m_Script is already published by EVIDENCE-02.
        if (link.field == "m_Script") return

        val relationType = when (link.field) {
            "gameObject" -> "belongs-to-gameobject"
            "father" -> "has-parent-transform"
            "children" -> "has-child-transform"
            "components" -> "has-component"
            else -> return
        }
        val sourceObject = metadata.decodedObjects.firstOrNull {
            it.pathId == link.sourcePathId
        } ?: return
        val targetPath = link.targetFilePath
        val targetEntry = targetPath?.let { path -> files.firstOrNull { it.path == path } }
        val targetMetadata = targetPath?.let { unityMetadata[it] }
        val targetDecodedObject = targetMetadata?.decodedObjects?.firstOrNull {
            it.pathId == link.pathId
        }
        val targetObjectRecord = targetMetadata?.objectSamples?.firstOrNull {
            it.pathId == link.pathId
        }
        val targetClassId = targetDecodedObject?.classId ?: targetObjectRecord?.classId ?: link.targetClassId
        val targetFileRecordId = targetEntry?.record?.id
        val isDecodedTarget = link.status == "resolved-decoded-object" &&
            targetDecodedObject != null && targetFileRecordId != null
        val isMetadataOnlyTarget = link.status == "resolved-object-record-not-decoded" &&
            targetObjectRecord != null && targetDecodedObject == null && targetFileRecordId != null

        val findingStatus = when {
            isDecodedTarget -> FindingStatus.DECODED
            isMetadataOnlyTarget -> FindingStatus.METADATA_ONLY
            link.status == "external-file-not-parsed" ||
                link.status == "pathID-not-in-object-sample" ||
                (link.status.startsWith("resolved-") && targetFileRecordId == null) -> FindingStatus.PARTIAL
            else -> FindingStatus.UNRESOLVED
        }
        val confidence = when {
            isDecodedTarget -> AnalysisConfidence.CONFIRMED
            isMetadataOnlyTarget -> AnalysisConfidence.STRONG
            findingStatus == FindingStatus.PARTIAL -> AnalysisConfidence.WEAK
            else -> AnalysisConfidence.UNKNOWN
        }
        val externalReferencePath = metadata.externalReferences.firstOrNull {
            it.fileId == link.fileId
        }?.path
        val reason = when {
            isDecodedTarget -> null
            isMetadataOnlyTarget ->
                "The target object record exists in the bounded SerializedFile sample, but its payload was not decoded."
            link.status == "external-fileID-not-in-reference-table" ->
                "The PPtr fileID is absent from the owner's external reference table."
            link.status == "external-file-path-unmatched-or-ambiguous" ->
                "The external SerializedFile path did not resolve to one unique inventory entry."
            link.status == "external-file-not-parsed" ->
                "The external target file was identified but its SerializedFile metadata was not parsed."
            link.status == "pathID-not-in-object-sample" ->
                "The target SerializedFile was parsed, but this PathID is outside its bounded object sample."
            targetFileRecordId == null && targetPath != null ->
                "The resolved target path has no matching FileRecord in this scan inventory."
            else -> "The external PPtr was not fully resolved: " + link.status + "."
        }

        val sourceLabel = unityObjectGraphLabel(
            sourceObject.classId,
            sourceObject.pathId,
            sourceObject.name
        )
        val targetLabel = if (isDecodedTarget || isMetadataOnlyTarget) {
            unityObjectGraphLabel(targetClassId, link.pathId, link.targetName)
        } else {
            "External Unity object PathID " + link.pathId
        }
        val title = sourceLabel + " " + relationType + " " + targetLabel
        val summary = buildString {
            append(ownerPath)
            append(" source ClassID=").append(sourceObject.classId)
            append(", PathID=").append(link.sourcePathId)
            append(" field=").append(link.field)
            append(" points to external fileID=").append(link.fileId)
            append(", PathID=").append(link.pathId)
            append("; resolver status=").append(link.status).append('.')
            targetPath?.let { append(" Target file=").append(it).append('.') }
        }
        val findingKey = "external-pointer:" + link.sourcePathId + ":" +
            link.field + ":" + link.fileId + ":" + link.pathId
        val findingId = Finding.create(
            scanId = scanId,
            findingKey = findingKey,
            analyzerRunId = analyzerRunId,
            sourceFileId = owner.record.id,
            category = "unity.external-pointer",
            title = title,
            summary = summary,
            confidence = confidence,
            status = findingStatus,
            reason = reason,
            supportedFormatRange = "SerializedFile metadata v19-v26"
        ).id
        val evidenceItem = Evidence.create(
            scanId = scanId,
            evidenceKey = "external-pptr:" + link.sourcePathId + ":" +
                link.field + ":" + link.fileId + ":" + link.pathId,
            findingId = findingId,
            analyzerRunId = analyzerRunId,
            sourceFileId = owner.record.id,
            method = if (isDecodedTarget || isMetadataOnlyTarget) {
                EvidenceMethod.RESOLVED_POINTER
            } else {
                EvidenceMethod.PARSED
            },
            confidence = confidence,
            description = "Decoded Unity object PathID=" + link.sourcePathId +
                " field=" + link.field + " contains external PPtr fileID=" +
                link.fileId + ", PathID=" + link.pathId +
                "; resolver status=" + link.status + "." +
                (targetPath?.let { " Target file=" + it + "." } ?: ""),
            locator = SourceLocator(unityFileId = link.fileId, pathId = link.pathId)
        )
        val sourceNode = GraphNode.create(
            scanId = scanId,
            nodeType = unityObjectGraphNodeType(sourceObject.classId),
            key = "PathID:" + sourceObject.pathId,
            label = sourceLabel,
            fileRecordId = owner.record.id,
            locator = SourceLocator(pathId = sourceObject.pathId),
            findingIds = listOf(findingId),
            evidenceIds = listOf(evidenceItem.id)
        )
        val targetNode = if ((isDecodedTarget || isMetadataOnlyTarget) && targetFileRecordId != null) {
            GraphNode.create(
                scanId = scanId,
                nodeType = unityObjectGraphNodeType(targetClassId),
                key = "PathID:" + link.pathId,
                label = targetLabel,
                fileRecordId = targetFileRecordId,
                locator = SourceLocator(pathId = link.pathId),
                findingIds = listOf(findingId),
                evidenceIds = listOf(evidenceItem.id)
            )
        } else {
            null
        }
        val targetHint = if (targetNode == null) {
            buildString {
                append("fileID=").append(link.fileId)
                append(", PathID=").append(link.pathId)
                (targetPath ?: externalReferencePath)?.let { append(", path=").append(it) }
                append(" (").append(link.status).append(')')
            }
        } else {
            null
        }
        val edge = GraphEdge.create(
            scanId = scanId,
            edgeKey = "external-pointer:" + link.sourcePathId + ":" +
                link.field + ":" + link.fileId + ":" + link.pathId,
            sourceNodeId = sourceNode.id,
            targetNodeId = targetNode?.id,
            relationType = relationType,
            confidence = confidence,
            status = findingStatus,
            evidenceIds = listOf(evidenceItem.id),
            findingIds = listOf(findingId),
            analyzerRunId = analyzerRunId,
            targetHint = targetHint
        )
        val finding = Finding.create(
            scanId = scanId,
            findingKey = findingKey,
            analyzerRunId = analyzerRunId,
            sourceFileId = owner.record.id,
            category = "unity.external-pointer",
            title = title,
            summary = summary,
            confidence = confidence,
            status = findingStatus,
            reason = reason,
            evidenceIds = listOf(evidenceItem.id),
            graphNodeIds = listOfNotNull(sourceNode.id, targetNode?.id),
            graphEdgeIds = listOf(edge.id),
            supportedFormatRange = "SerializedFile metadata v19-v26"
        )
        if (findings.none { it.id == finding.id }) findings.add(finding)
        if (evidenceRecords.none { it.id == evidenceItem.id }) evidenceRecords.add(evidenceItem)
        addOrMergeGraphNode(sourceNode)
        targetNode?.let { addOrMergeGraphNode(it) }
        if (graphEdges.none { it.id == edge.id }) graphEdges.add(edge)
    }

    private fun unityObjectGraphNodeType(classId: Int?): String = when (classId) {
        1 -> "unity-gameobject"
        4 -> "unity-transform"
        114 -> "unity-monobehaviour"
        115 -> "unity-monoscript"
        null -> "unity-object"
        else -> "unity-object-class-" + classId
    }

    private fun unityObjectGraphLabel(classId: Int?, pathId: Long, name: String?): String {
        name?.takeIf { it.isNotBlank() }?.let { return it }
        val typeName = when (classId) {
            1 -> "GameObject"
            4 -> "Transform"
            114 -> "MonoBehaviour"
            115 -> "MonoScript"
            null -> "Unity object"
            else -> "Unity object ClassID " + classId
        }
        return typeName + " PathID " + pathId
    }

    private fun recordMonoBehaviourScriptBinding(
        ownerPath: String,
        owner: ScanEntry,
        metadata: UnityMetadataSummary,
        binding: UnityMonoBehaviourScriptBinding,
        analyzerRunId: String,
        files: List<ScanEntry>
    ) {
        val sourceObject = metadata.decodedObjects.firstOrNull {
            it.pathId == binding.sourcePathId && it.classId == 114
        }
        val targetPath = binding.targetFilePath
        val targetEntry = targetPath?.let { path -> files.firstOrNull { it.path == path } }
        val targetMetadata = targetPath?.let { unityMetadata[it] }
        val targetScript = targetMetadata?.monoScripts?.firstOrNull {
            it.pathId == binding.scriptPathId
        }
        val targetObject = targetMetadata?.monoScriptObjects?.firstOrNull {
            it.pathId == binding.scriptPathId
        }
        val targetObjectKnown = targetScript != null || targetObject != null
        val targetFileRecordId = targetEntry?.record?.id

        val isResolvedClass = binding.status == "resolved-monoscript-class" &&
            targetScript != null && targetFileRecordId != null
        val isMetadataOnly = binding.status == "monoscript-object-found-payload-unavailable" &&
            targetObjectKnown && targetFileRecordId != null
        val isNullPointer = binding.status == "null-script-reference"

        val findingStatus = when {
            isResolvedClass -> FindingStatus.DECODED
            isMetadataOnly -> FindingStatus.METADATA_ONLY
            isNullPointer -> FindingStatus.SKIPPED
            binding.status == "target-file-not-parsed" ||
                binding.status == "monoscript-not-in-object-sample" -> FindingStatus.PARTIAL
            else -> FindingStatus.UNRESOLVED
        }
        val confidence = when {
            isResolvedClass || isNullPointer -> AnalysisConfidence.CONFIRMED
            isMetadataOnly -> AnalysisConfidence.STRONG
            binding.status == "target-file-not-parsed" -> AnalysisConfidence.WEAK
            else -> AnalysisConfidence.UNKNOWN
        }
        val reason = when {
            isResolvedClass -> null
            isMetadataOnly -> "MonoScript object record was found, but its payload did not provide a decoded class."
            isNullPointer -> "MonoBehaviour m_Script is a null PPtr; no script target is assigned in the decoded record."
            binding.status == "external-fileID-not-in-reference-table" ->
                "The PPtr fileID is absent from the owner's external reference table."
            binding.status == "external-file-path-unmatched-or-ambiguous" ->
                "The external SerializedFile path did not resolve to one unique inventory entry."
            binding.status == "target-file-not-parsed" ->
                "The target file was identified but its metadata was not available in the parsed sample."
            binding.status == "monoscript-not-in-object-sample" ->
                "The target SerializedFile was parsed, but the referenced MonoScript is outside its bounded object sample."
            targetEntry == null && binding.targetFilePath != null ->
                "The resolved target path has no matching FileRecord in this scan inventory."
            else -> "MonoBehaviour m_Script binding was not fully resolved: ${binding.status}."
        }

        val classLabel = binding.className?.let { className ->
            (binding.namespace?.takeIf { it.isNotBlank() }?.plus(".") ?: "") + className
        }
        val targetLabel = classLabel
            ?: binding.monoScriptName
            ?: "MonoScript PathID ${binding.scriptPathId}"
        val sourceLabel = sourceObject?.name?.takeIf { it.isNotBlank() }
            ?: "MonoBehaviour PathID ${binding.sourcePathId}"
        val title = if (isResolvedClass || isMetadataOnly) {
            "$sourceLabel attaches $targetLabel"
        } else {
            "$sourceLabel m_Script binding"
        }
        val summary = buildString {
            append(ownerPath)
            append(" MonoBehaviour PathID=").append(binding.sourcePathId)
            append(" has m_Script PPtr fileID=").append(binding.fileId)
            append(", PathID=").append(binding.scriptPathId)
            append("; resolver status=").append(binding.status).append('.')
            targetPath?.let { append(" Target file=").append(it).append('.') }
            classLabel?.let { append(" Resolved class=").append(it).append('.') }
        }

        val findingKey = "monobehaviour-script:" + binding.sourcePathId + ":" +
            binding.fileId + ":" + binding.scriptPathId
        val findingId = Finding.create(
            scanId = scanId,
            findingKey = findingKey,
            analyzerRunId = analyzerRunId,
            sourceFileId = owner.record.id,
            category = "unity.monobehaviour-script-binding",
            title = title,
            summary = summary,
            confidence = confidence,
            status = findingStatus,
            reason = reason,
            supportedFormatRange = "SerializedFile metadata v19-v26"
        ).id
        val evidenceItem = Evidence.create(
            scanId = scanId,
            evidenceKey = "m-script-pptr:" + binding.sourcePathId + ":" +
                binding.fileId + ":" + binding.scriptPathId,
            findingId = findingId,
            analyzerRunId = analyzerRunId,
            sourceFileId = owner.record.id,
            method = if (isResolvedClass || isMetadataOnly) {
                EvidenceMethod.RESOLVED_POINTER
            } else {
                EvidenceMethod.PARSED
            },
            confidence = confidence,
            description = "Decoded MonoBehaviour.m_Script on source object PathID=" + binding.sourcePathId +
                "; PPtr fileID=" + binding.fileId + ", PathID=" + binding.scriptPathId +
                "; resolver status=" + binding.status + "." +
                (targetPath?.let { " Target file=$it." } ?: ""),
            locator = SourceLocator(unityFileId = binding.fileId, pathId = binding.scriptPathId)
        )

        val sourceNode = GraphNode.create(
            scanId = scanId,
            nodeType = "unity-monobehaviour",
            key = "PathID:" + binding.sourcePathId,
            label = sourceLabel,
            fileRecordId = owner.record.id,
            locator = SourceLocator(pathId = binding.sourcePathId),
            findingIds = listOf(findingId),
            evidenceIds = listOf(evidenceItem.id)
        )
        val targetNode = if ((isResolvedClass || isMetadataOnly) && targetFileRecordId != null) {
            GraphNode.create(
                scanId = scanId,
                nodeType = "unity-monoscript",
                key = "PathID:" + binding.scriptPathId,
                label = if (isResolvedClass) targetLabel else "$targetLabel (payload unavailable)",
                fileRecordId = targetFileRecordId,
                locator = SourceLocator(pathId = binding.scriptPathId),
                findingIds = listOf(findingId),
                evidenceIds = listOf(evidenceItem.id)
            )
        } else {
            null
        }

        val edge = if (isNullPointer) {
            null
        } else {
            val externalPathHint = metadata.externalReferences.firstOrNull {
                it.fileId == binding.fileId
            }?.path
            val targetHint = if (targetNode == null) {
                buildString {
                    append("fileID=").append(binding.fileId)
                    append(", PathID=").append(binding.scriptPathId)
                    (targetPath ?: externalPathHint)?.let { append(", path=").append(it) }
                    append(" (").append(binding.status).append(')')
                }
            } else {
                null
            }
            GraphEdge.create(
                scanId = scanId,
                edgeKey = "monobehaviour-script:" + binding.sourcePathId + ":" +
                    binding.fileId + ":" + binding.scriptPathId,
                sourceNodeId = sourceNode.id,
                targetNodeId = targetNode?.id,
                relationType = "attaches-script",
                confidence = confidence,
                status = findingStatus,
                evidenceIds = listOf(evidenceItem.id),
                findingIds = listOf(findingId),
                analyzerRunId = analyzerRunId,
                targetHint = targetHint
            )
        }

        val finding = Finding.create(
            scanId = scanId,
            findingKey = findingKey,
            analyzerRunId = analyzerRunId,
            sourceFileId = owner.record.id,
            category = "unity.monobehaviour-script-binding",
            title = title,
            summary = summary,
            confidence = confidence,
            status = findingStatus,
            reason = reason,
            evidenceIds = listOf(evidenceItem.id),
            graphNodeIds = listOfNotNull(sourceNode.id, targetNode?.id),
            graphEdgeIds = listOfNotNull(edge?.id),
            supportedFormatRange = "SerializedFile metadata v19-v26"
        )
        if (findings.none { it.id == finding.id }) findings.add(finding)
        if (evidenceRecords.none { it.id == evidenceItem.id }) evidenceRecords.add(evidenceItem)
        addOrMergeGraphNode(sourceNode)
        targetNode?.let { addOrMergeGraphNode(it) }
        edge?.let { candidate ->
            if (graphEdges.none { it.id == candidate.id }) graphEdges.add(candidate)
        }
    }

    private fun addOrMergeGraphNode(candidate: GraphNode) {
        val index = graphNodes.indexOfFirst { it.id == candidate.id }
        if (index < 0) {
            graphNodes.add(candidate)
            return
        }
        val existing = graphNodes[index]
        val preferredLabel = if (existing.label.startsWith("MonoScript PathID ") &&
            !candidate.label.startsWith("MonoScript PathID ")) {
            candidate.label
        } else {
            existing.label
        }
        graphNodes[index] = existing.copy(
            label = preferredLabel,
            findingIds = (existing.findingIds + candidate.findingIds).distinct(),
            evidenceIds = (existing.evidenceIds + candidate.evidenceIds).distinct()
        )
    }

    private data class ExternalFileMatch(val entry: ScanEntry?, val ambiguous: Boolean)

    private fun resolveExternalFile(externalPath: String, files: List<ScanEntry>): ExternalFileMatch {
        val match = UnityExternalPathResolver.resolve(
            externalPath,
            files,
            { entry -> entry.path },
            { entry -> entry.name }
        )
        return ExternalFileMatch(match.item, match.ambiguous)
    }

    private fun readMonoScriptRecords(
        entry: ScanEntry,
        header: UnityFileHeader,
        metadata: UnityMetadataSummary
    ): UnityMetadataSummary {
        val dataOffset = header.dataOffset
        if (dataOffset == null) {
            addAnalyzerRun(
                "mono_script_payload",
                entry,
                AnalyzerRunStatus.SKIPPED,
                "Unity header does not provide a data offset."
            )
            return readUnitySceneRecords(entry, header, metadata)
        }
        val fileSize = header.fileSize ?: entry.size
        val littleEndian = when (header.endianness) {
            "Little Endian" -> true
            "Big Endian" -> false
            else -> {
                addAnalyzerRun(
                    "mono_script_payload",
                    entry,
                    AnalyzerRunStatus.SKIPPED,
                    "Unity header endianness is unknown."
                )
                return readUnitySceneRecords(entry, header, metadata)
            }
        }
        val targets = metadata.scriptTypeBindings.mapNotNull { binding ->
            if (binding.fileId != 0 || binding.pathId == null ||
                binding.objectByteStart == null || binding.objectByteSize == null) {
                null
            } else {
                MonoScriptObjectTarget(binding.pathId, binding.objectByteStart, binding.objectByteSize)
            }
        }.distinctBy { it.pathId }

        if (targets.isEmpty()) {
            addAnalyzerRun(
                "mono_script_payload",
                entry,
                AnalyzerRunStatus.SKIPPED,
                "No local MonoScript object targets were present in the bounded metadata sample."
            )
            return readUnitySceneRecords(entry, header, metadata)
        }

        val runId = addAnalyzerRun("mono_script_payload", entry)
        val updated = try {
            val input = fileAccess.openInputStream(entry.node)
            if (input == null) {
                updateAnalyzerRun(
                    runId,
                    AnalyzerRunStatus.FAILED,
                    "File provider returned no input stream for MonoScript payload."
                )
                val readNote = "MonoScript payload не прочитан: provider не вернул input stream."
                metadata.copy(note = listOfNotNull(metadata.note, readNote).joinToString(" "))
            } else {
                val result = input.use { stream ->
                    MonoScriptParser.readObjects(stream, dataOffset, fileSize, targets, littleEndian)
                }
                val status = when {
                    result.note == null && result.skippedCount == 0 -> AnalyzerRunStatus.ANALYZED
                    result.scripts.isNotEmpty() -> AnalyzerRunStatus.PARTIAL
                    else -> AnalyzerRunStatus.FAILED
                }
                updateAnalyzerRun(runId, status, result.note)

                val scripts = result.scripts.map { it.copy(sourceFilePath = entry.path) }
                val byPathId = scripts.associateBy { it.pathId }
                val updatedBindings = metadata.scriptTypeBindings.map { binding ->
                    val script = if (binding.fileId == 0) byPathId[binding.pathId] else null
                    if (script == null) binding else binding.copy(
                        monoScriptName = script.name,
                        className = script.className,
                        namespace = script.namespace,
                        assemblyName = script.assemblyName,
                        executionOrder = script.executionOrder,
                        resolution = "local-monoscript-decoded"
                    )
                }
                val combinedNote = listOfNotNull(metadata.note, result.note).joinToString(" ")
                metadata.copy(
                    monoScripts = scripts,
                    scriptTypeBindings = updatedBindings,
                    note = combinedNote.takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            updateAnalyzerRun(runId, AnalyzerRunStatus.FAILED, reason)
            val readNote = "MonoScript payload не прочитан: $reason"
            metadata.copy(note = listOfNotNull(metadata.note, readNote).joinToString(" "))
        }
        return readUnitySceneRecords(entry, header, updated)
    }

    private fun readUnitySceneRecords(
        entry: ScanEntry,
        header: UnityFileHeader,
        metadata: UnityMetadataSummary
    ): UnityMetadataSummary {
        val runId = addAnalyzerRun("unity_scene_graph", entry)

        fun skipped(reason: String): UnityMetadataSummary {
            updateAnalyzerRun(runId, AnalyzerRunStatus.SKIPPED, reason)
            return metadata.copy(sceneDecodeNote = reason)
        }

        val dataOffset = header.dataOffset
            ?: return skipped("Неизвестен dataOffset; payload объектов не прочитан.")
        val fileSize = header.fileSize ?: entry.size
        val littleEndian = when (header.endianness) {
            "Little Endian" -> true
            "Big Endian" -> false
            else -> return skipped("Неизвестен порядок байтов; payload объектов не прочитан.")
        }
        val targets = metadata.objectSamples.mapNotNull { objectInfo ->
            val classId = objectInfo.classId ?: return@mapNotNull null
            if (classId != 1 && classId != 4 && classId != 114) return@mapNotNull null
            val tree = metadata.typeTrees.firstOrNull {
                it.typeIndex == objectInfo.typeIndex && it.classId == classId && it.completeForDecoding
            } ?: return@mapNotNull null
            UnityObjectDecodeTarget(objectInfo, tree)
        }
        if (targets.isEmpty()) {
            val reason = when {
                metadata.typeTreeEnabled != true -> "TypeTree отключён или отсутствует."
                else -> "В ограниченной выборке нет полного TypeTree для GameObject, Transform или MonoBehaviour."
            }
            return skipped(reason)
        }
        return try {
            val input = fileAccess.openInputStream(entry.node)
            if (input == null) {
                val reason = "Не удалось открыть файл для чтения scene objects."
                updateAnalyzerRun(runId, AnalyzerRunStatus.FAILED, reason)
                return metadata.copy(sceneDecodeNote = reason)
            }
            val result = input.use { stream ->
                UnityObjectDecoder.readObjects(stream, dataOffset, fileSize, targets, littleEndian)
            }
            val graph = UnitySceneGraphResolver.resolve(result.objects)
            val status = if (result.skippedCount > 0) {
                AnalyzerRunStatus.PARTIAL
            } else {
                AnalyzerRunStatus.ANALYZED
            }
            updateAnalyzerRun(runId, status, result.note)
            metadata.copy(decodedObjects = result.objects, sceneGraph = graph, sceneDecodeNote = result.note)
        } catch (e: Exception) {
            val reason = "Scene object payload не прочитан: " + (e.message ?: e.javaClass.simpleName)
            updateAnalyzerRun(runId, AnalyzerRunStatus.FAILED, reason)
            metadata.copy(sceneDecodeNote = reason)
        }
    }

    private fun readPeInfo(entry: ScanEntry): PeInfo? {
        return try {
            var data = readPrefix(entry.node, MAX_PE_READ) ?: return null
            var info = PeParser.parse(data) ?: return null
            val metadataEnd = info.clrMetadataOffset?.toLong()?.plus(info.clrMetadataSize ?: 0L)
            if (info.managed && metadataEnd != null && metadataEnd > data.size.toLong()) {
                val expanded = readPrefix(entry.node, MAX_CLR_PE_READ)
                if (expanded != null) {
                    data = expanded
                    info = PeParser.parse(data) ?: info
                }
            }
            if (info.managed) {
                clrAttemptedCount++
                val clrRunId = addAnalyzerRun("clr_metadata", entry)
                try {
                    val summary = ClrMetadataParser.parse(
                        data, info.clrMetadataOffset, info.clrMetadataSize,
                        info.clrRuntimeVersion, info.clrFlags
                    )
                    if (summary == null) {
                        clrFailedCount++
                        updateAnalyzerRun(
                            clrRunId,
                            AnalyzerRunStatus.FAILED,
                            "CLR metadata parser returned no result."
                        )
                    } else {
                        clrResults[entry.path] = summary
                        val status = when {
                            summary.status == "parsed" -> AnalyzerRunStatus.ANALYZED
                            summary.status.startsWith("partial-") -> AnalyzerRunStatus.PARTIAL
                            summary.status.contains("unsupported", ignoreCase = true) -> AnalyzerRunStatus.UNSUPPORTED
                            else -> AnalyzerRunStatus.FAILED
                        }
                        updateAnalyzerRun(
                            clrRunId,
                            status,
                            summary.note ?: summary.status.takeIf { status != AnalyzerRunStatus.ANALYZED }
                        )
                    }
                } catch (e: Exception) {
                    clrFailedCount++
                    updateAnalyzerRun(
                        clrRunId,
                        AnalyzerRunStatus.FAILED,
                        e.message ?: e.javaClass.simpleName
                    )
                    addIssue(
                        code = "clr.metadata_analysis_failed",
                        stage = ScanIssueStage.CLR_METADATA,
                        severity = ScanIssueSeverity.ERROR,
                        message = "CLR metadata analysis failed for " + entry.path + ": " + e.message,
                        path = entry.path
                    )
                }
            }
            info
        } catch (e: Exception) {
            addIssue(
                code = "pe.analysis_failed",
                stage = ScanIssueStage.PE,
                severity = ScanIssueSeverity.ERROR,
                message = "PE-анализ не выполнен для " + entry.path + ": " + e.message,
                path = entry.path
            )
            null
        }
    }

    private fun readPrefix(node: ScanNode, maximum: Int): ByteArray? {
        val input = fileAccess.openInputStream(node) ?: return null
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (total < maximum) {
                val count = stream.read(buffer, 0, minOf(buffer.size, maximum - total))
                if (count <= 0) break
                output.write(buffer, 0, count)
                total += count
            }
            return output.toByteArray()
        }
    }

    private fun buildMarkdown(
        rootName: String, backend: String, unityVersion: UnityVersionResult, gameAssemblies: List<ScanEntry>,
        unityModules: List<ScanEntry>, monoFolder: Boolean, managedDir: Boolean,
        hasGameAssembly: Boolean, hasMetadata: Boolean, hasIl2CppDir: Boolean, hasUnityPlayer: Boolean,
        managedBinaries: Set<String>, nativeBinaries: Set<String>, importSummary: List<Map.Entry<String, Int>>,
        unityHeaders: Map<String, UnityFileHeader>, unityMetadata: Map<String, UnityMetadataSummary>,
        coverage: ScanCoverage
    ): String = buildString {
        appendLine("# Unity Game Architecture Report")
        appendLine()
        appendLine("- Root: " + rootName)
        appendLine("- Generated: " + java.time.Instant.now())
        appendLine("- Backend assessment: " + backend)
        appendLine("- Unity version: " + (unityVersion.version ?: if (unityVersion.status.startsWith("conflicting")) "конфликтующие признаки" else "не обнаружена"))
        appendLine("- Unity version confidence: " + (unityVersion.confidence ?: "не определена"))
        appendLine("- Unity version status: " + unityVersion.status)
        appendLine("- Indexed entries: " + entries.size)
        appendLine("- Total size: " + formatBytes(totalBytes))
        appendLine("- UnityPlayer.dll: " + yesNo(hasUnityPlayer))
        appendLine("- MonoBleedingEdge: " + yesNo(monoFolder))
        appendLine("- Managed directory: " + yesNo(managedDir))
        appendLine("- GameAssembly.dll: " + yesNo(hasGameAssembly))
        appendLine("- il2cpp_data + global-metadata.dat: " + yesNo(hasIl2CppDir && hasMetadata))
        appendLine()
        appendLine("## Unity version evidence")
        if (unityVersion.evidence.isEmpty()) appendLine("- Подтверждённые строки версии не найдены")
        unityVersion.evidence.take(40).forEach { item ->
            appendLine("- " + item.version + " — " + item.source + "; confidence=" +
                item.confidence + "; file=" + item.path)
        }
        if (unityVersion.evidence.size > 40) {
            appendLine("- Evidence list limited; omitted " + (unityVersion.evidence.size - 40) + " entries.")
        }
        appendLine()
        appendLine("## Game assemblies")
        if (gameAssemblies.isEmpty()) appendLine("- Не найдены")
        gameAssemblies.forEach { appendLine("- " + it.path + " — " + formatBytes(it.size)) }
        appendLine()
        appendLine("## UnityEngine modules")
        if (unityModules.isEmpty()) appendLine("- Не найдены")
        unityModules.forEach { appendLine("- " + it.path + " — " + formatBytes(it.size)) }
        appendLine()
        appendLine("## Managed PE assemblies (CLR header)")
        if (managedBinaries.isEmpty()) appendLine("- CLR header не обнаружен в проверенных PE-файлах")
        managedBinaries.forEach { appendLine("- " + it) }
        appendLine()
        appendLine("## Native PE binaries")
        if (nativeBinaries.isEmpty()) appendLine("- Не обнаружены")
        nativeBinaries.forEach { appendLine("- " + it) }
        appendLine()
        appendLine("## Static native DLL imports")
        if (importSummary.isEmpty()) appendLine("- Не удалось прочитать таблицы импортов")
        importSummary.forEach { appendLine("- " + it.key + " — используется в " + it.value + " бинарных файлах") }
        appendLine()
        appendLine("## Imported native symbols")
        val importedSymbols = peResults.flatMap { (sourcePath, info) ->
            info.importedSymbols.map { symbol -> sourcePath to symbol }
        }
        if (importedSymbols.isEmpty()) appendLine("- Имена функций и ordinal imports не обнаружены в прочитанных PE-таблицах")
        importedSymbols.forEach { (sourcePath, symbol) ->
            val importedName = symbol.name ?: "ordinal #${symbol.ordinal ?: "?"}"
            val hint = symbol.hint?.let { "; hint=$it" } ?: ""
            appendLine("- $sourcePath: ${symbol.dllName}!$importedName$hint")
        }
        appendLine()
        appendLine("## PE details")
        if (peResults.isEmpty()) appendLine("- PE-заголовки не прочитаны")
        peResults.forEach { (path, info) ->
            val kind = if (info.managed) "managed CLR/.NET" else "native PE"
            appendLine("- " + path + ": " + info.machine + "; " + kind + "; imports=" + info.imports.size +
                (info.note?.let { "; " + it } ?: ""))
            info.imports.forEach { appendLine("  - " + it) }
        }
        appendLine()
        appendLine("## CLR managed assembly metadata")
        if (clrResults.isEmpty()) appendLine("- CLR metadata не удалось прочитать в проверенных managed PE-файлах")
        clrResults.forEach { (path, assembly) ->
            appendLine("- $path: " + (assembly.name ?: "(assembly name unavailable)") +
                (assembly.version?.let { "; version=" + it } ?: "") +
                (assembly.moduleName?.let { "; module=" + it } ?: "") +
                "; types=" + assembly.typeCount + "; fields=" + assembly.fields.size +
                "; methods=" + assembly.methods.size + "; refs=" + assembly.assemblyReferences.size +
                "; status=" + assembly.status + (assembly.note?.let { "; " + it } ?: ""))
            assembly.types.take(100).forEach { type ->
                val fullName = if (type.namespace.isBlank()) type.name else type.namespace + "." + type.name
                appendLine("  - type " + fullName + " flags=0x" + type.flags.toString(16) +
                    (type.fieldNames.joinToString(prefix = if (type.fieldNames.isEmpty()) "" else " fields=[", postfix = if (type.fieldNames.isEmpty()) "" else "]")) +
                    (type.methodNames.joinToString(prefix = if (type.methodNames.isEmpty()) "" else " methods=[", postfix = if (type.methodNames.isEmpty()) "" else "]")))
            }
            assembly.fields.take(100).forEach { field ->
                appendLine("  - field " + field.name + " flags=0x" + field.flags.toString(16) +
                    (field.signatureType?.let { " type=" + it } ?: " signature=" + field.signatureStatus))
            }
            assembly.methods.take(100).forEach { method ->
                appendLine("  - method " + (method.signatureDisplay ?: method.name) + " RVA=0x" +
                    method.relativeVirtualAddress.toString(16) + " flags=0x" + method.flags.toString(16) +
                    " impl=0x" + method.implementationFlags.toString(16) +
                    (if (method.signatureStatus == "parsed") "" else " signature=" + method.signatureStatus))
            }
            assembly.assemblyReferences.forEach { reference ->
                appendLine("  - AssemblyRef " + reference.name + ", Version=" +
                    reference.majorVersion + "." + reference.minorVersion + "." +
                    reference.buildNumber + "." + reference.revisionNumber +
                    (reference.culture.takeIf { it.isNotBlank() }?.let { ", Culture=" + it } ?: ""))
            }
        }
        appendLine()
        appendLine("## MonoBehaviour to managed TypeDef links")
        if (managedTypeResolutions.isEmpty()) appendLine("- Сопоставимых MonoBehaviour MonoScript и managed type records не найдено")
        managedTypeResolutions.forEach { link ->
            appendLine("- " + link.sourceFilePath + " object=" + link.sourcePathId +
                " -> " + ((link.namespace?.takeIf { it.isNotBlank() }?.plus(".") ?: "") + (link.className ?: "?")) +
                " in " + (link.assemblyName ?: "?") +
                (link.managedAssemblyPath?.let { " [" + it + "]" } ?: "") +
                "; status=" + link.status)
        }
        appendLine()
        appendLine("## Unity serialized files and bundles")
        if (unityHeaders.isEmpty()) appendLine("- Поддерживаемые заголовки не обнаружены среди проверенных кандидатов")
        unityHeaders.forEach { (path, header) ->
            appendLine("- " + path + ": " + header.kind +
                (header.formatVersion?.let { "; format=" + it } ?: "") +
                (header.fileSize?.let { "; fileSize=" + it } ?: "") +
                (header.metadataSize?.let { "; metadata=" + it } ?: "") +
                (header.dataOffset?.let { "; dataOffset=" + it } ?: "") +
                (header.endianness?.let { "; " + it } ?: "") +
                (header.playerVersion?.let { "; playerVersion=" + it } ?: "") +
                (header.engineVersion?.let { "; engineVersion=" + it } ?: "") +
                (header.note?.let { "; " + it } ?: ""))
        }
        appendLine()
        appendLine("## Unity metadata (leading fields)")
        if (unityMetadata.isEmpty()) appendLine("- Метаданные не прочитаны или версия формата пока не поддерживается")
        unityMetadata.forEach { (path, metadata) ->
            appendLine("- " + path +
                (metadata.unityVersion?.let { "; Unity=" + it } ?: "") +
                (metadata.targetPlatform?.let { "; platformId=" + it } ?: "") +
                (metadata.typeTreeEnabled?.let { "; TypeTree=" + if (it) "enabled" else "disabled" } ?: "") +
                (metadata.typeCount?.let { "; typeCount=" + it } ?: "") +
                (metadata.objectCount?.let { "; objectCount=" + it } ?: "") +
                (metadata.objectTypeCounts.take(20).joinToString(prefix = if (metadata.objectTypeCounts.isEmpty()) "" else "; objectTypes=", separator = ", ") { item ->
                    val label = when (item.classId) {
                        1 -> "GameObject"
                        4 -> "Transform"
                        114 -> "MonoBehaviour"
                        115 -> "MonoScript"
                        else -> "ClassID=${item.classId}"
                    }
                    "$label:${item.count}"
                }) +
                (metadata.scriptTypeCount?.let { "; MonoScriptRefs=" + it } ?: "") +
                (metadata.externalReferenceCount?.let { "; externalRefs=" + it } ?: "") +
                (metadata.serializedReferenceTypeCount?.let { "; SerializeReferenceTypes=" + it } ?: "") +
                (metadata.sharedSubtreeCount?.let { "; sharedTypeTreeSubtrees=" + it } ?: "") +
                (metadata.serializedTypes.filter { it.scriptTypeIndex >= 0 }.take(10).joinToString(prefix = if (metadata.serializedTypes.none { it.scriptTypeIndex >= 0 }) "" else "; scriptedTypes=", separator = ", ") { "ClassID=${it.classId}:scriptIndex=${it.scriptTypeIndex}:scriptID=${it.scriptId ?: "?"}" }) +
                (metadata.scriptTypeBindings.take(10).joinToString(prefix = if (metadata.scriptTypeBindings.isEmpty()) "" else "; scriptBindings=", separator = ", ") { binding ->
                    val classLabel = binding.className?.let { name ->
                        (binding.namespace?.takeIf { ns -> ns.isNotBlank() }?.plus(".") ?: "") + name
                    }
                    "ClassID=${binding.classId}[index=${binding.scriptTypeIndex}]->fileID=${binding.fileId ?: "?"}/pathID=${binding.pathId ?: "?"}:${binding.resolution}" +
                        (classLabel?.let { " class=$it" } ?: "") +
                        (binding.assemblyName?.let { " assembly=$it" } ?: "") +
                        (binding.matchedExternalFilePath?.let { " source=$it" } ?: "") +
                        (binding.externalPath?.let { " ($it)" } ?: "")
                }) +
                (metadata.scriptTypeReferences.take(10).joinToString(prefix = if (metadata.scriptTypeReferences.isEmpty()) "" else "; scriptTypeRefs=", separator = ", ") { "fileID=${it.fileId}:pathID=${it.pathId}" }) +
                (metadata.externalReferences.take(10).joinToString(prefix = if (metadata.externalReferences.isEmpty()) "" else "; externalFiles=", separator = ", ") { "fileID=${it.fileId}:${it.path}" }) +
                (metadata.typeTrees.take(10).joinToString(prefix = if (metadata.typeTrees.isEmpty()) "" else "; typeTrees=", separator = " | ") { tree -> "ClassID=${tree.classId}:${tree.rootType ?: "?"} ${tree.rootName ?: ""} fields=${tree.fieldNames.joinToString(",")}" }) +
                (metadata.objectSamples.take(20).joinToString(prefix = if (metadata.objectSamples.isEmpty()) "" else "; sampleObjects=", separator = ", ") { obj -> "pathId=${obj.pathId}:type=${obj.classId ?: obj.typeIndex}:size=${obj.byteSize}@${obj.byteStart}" }) +
                (metadata.note?.let { "; " + it } ?: ""))
        }
        appendLine()
        appendLine("## Decoded Unity scene objects")
        var decodedSceneCount = 0
        unityMetadata.forEach { (path, metadata) ->
            metadata.decodedObjects.take(128).forEach { obj ->
                decodedSceneCount++
                val pointer = { ref: UnityObjectPointerSummary? ->
                    ref?.let { "fileID=${it.fileId}/pathID=${it.pathId}" } ?: "?"
                }
                val label = when (obj.classId) {
                    1 -> "GameObject"
                    4 -> "Transform"
                    114 -> "MonoBehaviour"
                    else -> "ClassID=${obj.classId}"
                }
                appendLine("- $path: $label pathID=${obj.pathId}" +
                    (obj.name?.let { " name=\"$it\"" } ?: "") +
                    (obj.isActive?.let { " active=$it" } ?: "") +
                    (obj.isEnabled?.let { " enabled=$it" } ?: "") +
                    (obj.gameObject?.let { " gameObject=${pointer(it)}" } ?: "") +
                    (obj.father?.let { " father=${pointer(it)}" } ?: "") +
                    (obj.children.take(12).joinToString(prefix = if (obj.children.isEmpty()) "" else " children=", separator = ",") { "${it.fileId}:${it.pathId}" }) +
                    (obj.components.take(12).joinToString(prefix = if (obj.components.isEmpty()) "" else " components=", separator = ",") { "${it.fileId}:${it.pathId}" }) +
                    (obj.script?.let { " script=${pointer(it)}" } ?: ""))
            }
            metadata.sceneDecodeNote?.let { appendLine("- $path: decode status: $it") }
        }
        if (decodedSceneCount == 0 && unityMetadata.isEmpty()) appendLine("- Декодированные scene objects не найдены")
        appendLine()
        appendLine("## External Unity object references")
        unityMetadata.forEach { (path, metadata) ->
            val links = metadata.externalPointerResolutions
            if (links.isNotEmpty()) {
                appendLine("- " + path + ": external PPtr=" + links.size +
                    "; decoded targets=" + links.count { it.status == "resolved-decoded-object" } +
                    "; sampled targets=" + links.count { it.status == "resolved-object-record-not-decoded" })
                links.take(64).forEach { link ->
                    appendLine("  - source=" + link.sourcePathId + " field=" + link.field +
                        " -> fileID=" + link.fileId + "/pathID=" + link.pathId +
                        (link.targetFilePath?.let { " file=" + it } ?: "") +
                        (link.targetClassId?.let { " ClassID=" + it } ?: "") +
                        (link.targetName?.let { " name=\"" + it + "\"" } ?: "") +
                        " status=" + link.status)
                }
            }
        }
        appendLine()
        appendLine("## MonoBehaviour script classes")
        unityMetadata.forEach { (path, metadata) ->
            metadata.monoBehaviourScriptBindings.forEach { binding ->
                appendLine("- $path object=${binding.sourcePathId} m_Script=${binding.fileId}/${binding.scriptPathId}" +
                    (binding.targetFilePath?.let { " file=" + it } ?: "") +
                    (binding.className?.let { " class=" + (if (binding.namespace.isNullOrBlank()) it else binding.namespace + "." + it) } ?: "") +
                    (binding.assemblyName?.let { " assembly=" + it } ?: "") +
                    " status=${binding.status}")
            }
        }
        appendLine()
        appendLine("## Evidence-backed Unity script bindings")
        val scriptFindings = findings.filter { it.category == "unity.monobehaviour-script-binding" }
        if (scriptFindings.isEmpty()) appendLine("- Общие Finding/Evidence/Graph записи для MonoBehaviour script bindings не сформированы")
        scriptFindings.forEach { finding ->
            appendLine("- [" + finding.status.id + "/" + finding.confidence.id + "] " +
                finding.title + ": " + finding.summary +
                (finding.reason?.let { " Reason: " + it } ?: ""))
            finding.evidenceIds.forEach { id ->
                val item = evidenceRecords.firstOrNull { it.id == id }
                if (item != null) {
                    appendLine("  - evidence=" + item.id + "; method=" + item.method.id +
                        "; file=" + item.sourceFileId +
                        "; Unity fileID=" + (item.locator?.unityFileId ?: "?") +
                        "; PathID=" + (item.locator?.pathId ?: "?"))
                }
            }
        }
        graphEdges.filter { it.relationType == "attaches-script" }.forEach { edge ->
            appendLine("- graph " + edge.relationType + ": " + edge.sourceNodeId + " -> " +
                (edge.targetNodeId ?: edge.targetHint.orEmpty()) + " (" + edge.status.id + ")")
        }
        appendLine()
        appendLine("## Evidence-backed external Unity PPtr relations")
        val externalPointerFindings = findings.filter { it.category == "unity.external-pointer" }
        if (externalPointerFindings.isEmpty()) {
            appendLine("- Общие Finding/Evidence/Graph записи для внешних PPtr relations не сформированы")
        }
        externalPointerFindings.forEach { finding ->
            appendLine("- [" + finding.status.id + "/" + finding.confidence.id + "] " +
                finding.title + ": " + finding.summary +
                (finding.reason?.let { " Reason: " + it } ?: ""))
            finding.evidenceIds.forEach { id ->
                val item = evidenceRecords.firstOrNull { it.id == id }
                if (item != null) {
                    appendLine("  - evidence=" + item.id + "; method=" + item.method.id +
                        "; sourceFile=" + item.sourceFileId +
                        "; Unity fileID=" + (item.locator?.unityFileId ?: "?") +
                        "; PathID=" + (item.locator?.pathId ?: "?"))
                }
            }
            graphEdges.filter { finding.id in it.findingIds }.forEach { edge ->
                appendLine("  - graph " + edge.relationType + ": " + edge.sourceNodeId + " -> " +
                    (edge.targetNodeId ?: edge.targetHint.orEmpty()) + " (" + edge.status.id + ")")
            }
        }
        appendLine()
        appendLine("## Resolved Unity scene hierarchy")
        unityMetadata.forEach { (path, metadata) ->
            metadata.sceneGraph?.let { graph ->
                appendLine("- $path: GameObjects=${graph.nodes.size}; unresolved PPtr=${graph.unresolvedReferenceCount}; external PPtr=${graph.externalReferenceCount}")
                graph.nodes.forEach { node ->
                    appendLine("  - GameObject pathID=${node.gameObjectPathId}" +
                        (node.name?.let { " name=\"$it\"" } ?: "") +
                        (node.isActive?.let { " active=$it" } ?: "") +
                        (node.transformPathId?.let { " Transform=$it" } ?: "") +
                        (node.parentGameObjectPathId?.let { " parent=$it" } ?: "") +
                        (node.childGameObjectPathIds.take(32).joinToString(prefix = if (node.childGameObjectPathIds.isEmpty()) "" else " children=", separator = ",") { it.toString() }) +
                        (node.componentPathIds.take(32).joinToString(prefix = if (node.componentPathIds.isEmpty()) "" else " components=", separator = ",") { it.toString() }) +
                        (if (node.unresolvedReferenceCount > 0) " unresolved=${node.unresolvedReferenceCount}" else ""))
                }
            }
        }
        appendLine()
        appendLine("## Directory tree")
        val shown = entries.take(MAX_TREE_LINES)
        shown.forEach { entry ->
            val indent = "  ".repeat(entry.path.count { it == '/' })
            val label = if (entry.directory) "[DIR] " else "[FILE] "
            val detail = if (entry.directory) "/" else " — " + formatBytes(entry.size)
            appendLine(indent + "- " + label + entry.name + detail)
        }
        if (entries.size > shown.size) appendLine("- Дерево сокращено: пропущено " + (entries.size - shown.size) + " записей.")
        appendLine()
        appendLine("## Scan coverage")
        appendLine("- Overall status: " + coverage.status)
        appendLine("- Inventory status: " + coverage.inventoryStatus)
        appendLine("- Entries: " + coverage.entriesDiscovered +
            " (files=" + coverage.filesDiscovered + ", directories=" + coverage.directoriesDiscovered + ")")
        appendLine("- Total file bytes: " + coverage.totalFileBytes)
        appendLine("- Inaccessible directories: " + coverage.inaccessibleDirectoryCount)
        appendLine("- Depth-limit hits: " + coverage.depthLimitHitCount)
        appendLine("- Entry limit reached: " + coverage.entryLimitReached)
        coverage.analyzers.forEach { analyzer ->
            appendLine("- " + analyzer.id + ": " + analyzer.status +
                "; candidates=" + analyzer.candidateCount +
                "; attempted=" + analyzer.attemptedCount +
                "; parsed=" + analyzer.parsedCount +
                "; partial=" + analyzer.partialCount +
                "; failed=" + analyzer.failedCount +
                "; limited=" + analyzer.limitedCount +
                "; skipped=" + analyzer.skippedCount)
            analyzer.notes.forEach { note -> appendLine("  - " + note) }
        }
        appendLine()
        appendLine("## Warnings")
        if (warnings.isEmpty()) appendLine("- Нет")
        warnings.forEach { appendLine("- " + it) }
        appendLine()
        appendLine("## Notes")
        appendLine("- Исходные файлы читаются на месте через SAF: не копируются, не изменяются и не запускаются.")
        appendLine("- Mono/IL2CPP классифицируется по структуре и является эвристикой.")
        appendLine("- CLR metadata inventory читает Assembly, TypeDef и AssemblyRef; метод-тела IL, атрибуты и P/Invoke-сигнатуры пока не анализируются.")
        appendLine("- Статическая Import Table показывает DLL, имена импортируемых функций и ordinal imports в пределах bounded PE/thunk windows; динамическая загрузка библиотек и CLR P/Invoke полностью не покрываются.")
        appendLine("- Версия Unity определяется по globalgamemanagers SerializedFile metadata, UnityFS engine version или ограниченному текстовому поиску; playerVersion не считается точной версией движка.")
        appendLine("- Если основные источники противоречат друг другу, unityVersion остаётся null, а evidence сохраняется в отчёте.")
    }

    private fun buildJson(
        rootName: String, backend: String, unityVersion: UnityVersionResult, gameAssemblies: List<ScanEntry>,
        unityModules: List<ScanEntry>, managedBinaries: Set<String>, nativeBinaries: Set<String>,
        importSummary: List<Map.Entry<String, Int>>, unityHeaders: Map<String, UnityFileHeader>,
        unityMetadata: Map<String, UnityMetadataSummary>, coverage: ScanCoverage
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", 24)
        root.put("rootName", rootName)
        root.put("generatedAt", java.time.Instant.now().toString())
        root.put("backendAssessment", backend)
        root.put("unityVersion", unityVersion.version ?: JSONObject.NULL)
        root.put("unityVersionStatus", unityVersion.status)
        root.put("unityVersionConfidence", unityVersion.confidence ?: JSONObject.NULL)
        root.put("unityVersionEvidence", JSONArray(unityVersion.evidence.map { item -> JSONObject()
            .put("version", item.version)
            .put("source", item.source)
            .put("path", item.path)
            .put("confidence", item.confidence) }))
        root.put("entryCount", entries.size)
        root.put("totalFileBytes", totalBytes)
        root.put("scanId", scanId)
        root.put("analyzerRuns", JSONArray(analyzerRuns.map { it.toJsonObject() }))
        root.put("findings", JSONArray(findings.map { it.toJsonObject() }))
        root.put("evidence", JSONArray(evidenceRecords.map { it.toJsonObject() }))
        root.put("graphNodes", JSONArray(graphNodes.map { it.toJsonObject() }))
        root.put("graphEdges", JSONArray(graphEdges.map { it.toJsonObject() }))
        root.put("scanCoverage", JSONObject()
            .put("status", coverage.status)
            .put("inventory", JSONObject()
                .put("status", coverage.inventoryStatus)
                .put("entriesDiscovered", coverage.entriesDiscovered)
                .put("filesDiscovered", coverage.filesDiscovered)
                .put("directoriesDiscovered", coverage.directoriesDiscovered)
                .put("totalFileBytes", coverage.totalFileBytes)
                .put("inaccessibleDirectoryCount", coverage.inaccessibleDirectoryCount)
                .put("depthLimitHitCount", coverage.depthLimitHitCount)
                .put("entryLimitReached", coverage.entryLimitReached))
            .put("analyzers", JSONArray(coverage.analyzers.map { analyzer -> JSONObject()
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
            })))
        root.put("gameAssemblies", JSONArray(gameAssemblies.map { JSONObject().put("path", it.path).put("size", it.size) }))
        root.put("unityEngineModules", JSONArray(unityModules.map { JSONObject().put("path", it.path).put("size", it.size) }))
        root.put("managedPeAssemblies", JSONArray(managedBinaries))
        root.put("nativePeBinaries", JSONArray(nativeBinaries))
        root.put("nativeImports", JSONArray(importSummary.map {
            JSONObject().put("dll", it.key).put("referencedByCount", it.value)
        }))
        root.put("nativeImportSymbols", JSONArray(peResults.flatMap { (sourcePath, info) ->
            info.importedSymbols.map { symbol ->
                JSONObject()
                    .put("sourcePath", sourcePath)
                    .put("dll", symbol.dllName)
                    .put("name", symbol.name ?: JSONObject.NULL)
                    .put("ordinal", symbol.ordinal ?: JSONObject.NULL)
                    .put("hint", symbol.hint ?: JSONObject.NULL)
            }
        }))
        root.put("managedAssemblyMetadata", JSONArray(clrResults.map { (path, assembly) ->
            JSONObject().put("path", path)
                .put("name", assembly.name ?: JSONObject.NULL)
                .put("version", assembly.version ?: JSONObject.NULL)
                .put("moduleName", assembly.moduleName ?: JSONObject.NULL)
                .put("runtimeVersion", assembly.runtimeVersion ?: JSONObject.NULL)
                .put("flags", assembly.flags ?: JSONObject.NULL)
                .put("typeCount", assembly.typeCount)
                .put("types", JSONArray(assembly.types.map { type -> JSONObject()
                    .put("name", type.name).put("namespace", type.namespace).put("flags", type.flags)
                    .put("fields", JSONArray(type.fieldNames))
                    .put("methods", JSONArray(type.methodNames)) }))
                .put("fields", JSONArray(assembly.fields.map { field -> JSONObject()
                    .put("name", field.name).put("flags", field.flags)
                    .put("signatureType", field.signatureType ?: JSONObject.NULL)
                    .put("signatureStatus", field.signatureStatus) }))
                .put("methods", JSONArray(assembly.methods.map { method -> JSONObject()
                    .put("name", method.name).put("flags", method.flags)
                    .put("implementationFlags", method.implementationFlags)
                    .put("relativeVirtualAddress", method.relativeVirtualAddress)
                    .put("returnType", method.returnType ?: JSONObject.NULL)
                    .put("parameterTypes", JSONArray(method.parameterTypes))
                    .put("signatureDisplay", method.signatureDisplay ?: JSONObject.NULL)
                    .put("callingConvention", method.callingConvention ?: JSONObject.NULL)
                    .put("signatureStatus", method.signatureStatus) }))
                .put("assemblyReferences", JSONArray(assembly.assemblyReferences.map { ref -> JSONObject()
                    .put("name", ref.name)
                    .put("majorVersion", ref.majorVersion).put("minorVersion", ref.minorVersion)
                    .put("buildNumber", ref.buildNumber).put("revisionNumber", ref.revisionNumber)
                    .put("culture", ref.culture) }))
                .put("status", assembly.status)
                .put("note", assembly.note ?: JSONObject.NULL)
        }))
        root.put("managedTypeResolutions", JSONArray(managedTypeResolutions.map { link -> JSONObject()
            .put("sourceFilePath", link.sourceFilePath)
            .put("sourcePathId", link.sourcePathId)
            .put("className", link.className ?: JSONObject.NULL)
            .put("namespace", link.namespace ?: JSONObject.NULL)
            .put("assemblyName", link.assemblyName ?: JSONObject.NULL)
            .put("managedAssemblyPath", link.managedAssemblyPath ?: JSONObject.NULL)
            .put("managedTypeFound", link.managedTypeFound ?: JSONObject.NULL)
            .put("status", link.status)
        }))
        root.put("unityMetadata", JSONArray(unityMetadata.map { (path, metadata) ->
            JSONObject().put("path", path)
                .put("unityVersion", metadata.unityVersion ?: JSONObject.NULL)
                .put("targetPlatform", metadata.targetPlatform ?: JSONObject.NULL)
                .put("typeTreeEnabled", metadata.typeTreeEnabled ?: JSONObject.NULL)
                .put("typeCount", metadata.typeCount ?: JSONObject.NULL)
                .put("objectCount", metadata.objectCount ?: JSONObject.NULL)
                .put("objectTypeCounts", JSONArray(metadata.objectTypeCounts.map { item -> JSONObject()
                    .put("classId", item.classId)
                    .put("count", item.count)
                    .put("samplePathIds", JSONArray(item.samplePathIds)) }))
                .put("scriptTypeCount", metadata.scriptTypeCount ?: JSONObject.NULL)
                .put("externalReferenceCount", metadata.externalReferenceCount ?: JSONObject.NULL)
                .put("serializedReferenceTypeCount", metadata.serializedReferenceTypeCount ?: JSONObject.NULL)
                .put("serializedReferenceTypes", JSONArray(metadata.serializedReferenceTypes.map { type -> JSONObject().put("classId", type.classId).put("scriptTypeIndex", type.scriptTypeIndex).put("scriptId", type.scriptId ?: JSONObject.NULL).put("className", type.className ?: JSONObject.NULL).put("namespace", type.namespace ?: JSONObject.NULL).put("assemblyName", type.assemblyName ?: JSONObject.NULL) }))
                .put("sharedSubtreeCount", metadata.sharedSubtreeCount ?: JSONObject.NULL)
                .put("serializedTypes", JSONArray(metadata.serializedTypes.map { type -> JSONObject().put("classId", type.classId).put("scriptTypeIndex", type.scriptTypeIndex).put("scriptId", type.scriptId ?: JSONObject.NULL) }))
                .put("scriptTypeReferences", JSONArray(metadata.scriptTypeReferences.map { ref -> JSONObject().put("fileId", ref.fileId).put("pathId", ref.pathId) }))
                .put("scriptTypeBindings", JSONArray(metadata.scriptTypeBindings.map { binding -> JSONObject()
                    .put("classId", binding.classId)
                    .put("scriptTypeIndex", binding.scriptTypeIndex)
                    .put("scriptId", binding.scriptId ?: JSONObject.NULL)
                    .put("fileId", binding.fileId ?: JSONObject.NULL)
                    .put("pathId", binding.pathId ?: JSONObject.NULL)
                    .put("externalPath", binding.externalPath ?: JSONObject.NULL)
                    .put("matchedExternalFilePath", binding.matchedExternalFilePath ?: JSONObject.NULL)
                    .put("targetObjectClassId", binding.targetObjectClassId ?: JSONObject.NULL)
                    .put("monoScriptName", binding.monoScriptName ?: JSONObject.NULL)
                    .put("className", binding.className ?: JSONObject.NULL)
                    .put("namespace", binding.namespace ?: JSONObject.NULL)
                    .put("assemblyName", binding.assemblyName ?: JSONObject.NULL)
                    .put("executionOrder", binding.executionOrder ?: JSONObject.NULL)
                    .put("resolution", binding.resolution) }))
                .put("monoScripts", JSONArray(metadata.monoScripts.map { script -> JSONObject()
                    .put("pathId", script.pathId)
                    .put("name", script.name)
                    .put("executionOrder", script.executionOrder)
                    .put("className", script.className)
                    .put("namespace", script.namespace)
                    .put("assemblyName", script.assemblyName)
                    .put("sourceFilePath", script.sourceFilePath ?: JSONObject.NULL) }))
                .put("externalReferences", JSONArray(metadata.externalReferences.map { ref -> JSONObject().put("fileId", ref.fileId).put("path", ref.path).put("type", ref.type) }))
                .put("typeTrees", JSONArray(metadata.typeTrees.map { tree -> JSONObject()
                    .put("classId", tree.classId)
                    .put("typeIndex", tree.typeIndex ?: JSONObject.NULL)
                    .put("nodeCount", tree.nodeCount)
                    .put("rootType", tree.rootType ?: JSONObject.NULL)
                    .put("rootName", tree.rootName ?: JSONObject.NULL)
                    .put("fieldNames", JSONArray(tree.fieldNames))
                    .put("completeForDecoding", tree.completeForDecoding)
                    .put("nodes", JSONArray(tree.nodes.map { node -> JSONObject()
                        .put("level", node.level)
                        .put("type", node.typeName ?: JSONObject.NULL)
                        .put("name", node.fieldName ?: JSONObject.NULL)
                        .put("byteSize", node.byteSize)
                        .put("typeFlags", node.typeFlags)
                        .put("metaFlags", node.metaFlags) })) }))
                .put("objectSamples", JSONArray(metadata.objectSamples.map { obj -> JSONObject().put("pathId", obj.pathId).put("byteStart", obj.byteStart).put("byteSize", obj.byteSize).put("typeIndex", obj.typeIndex).put("classId", obj.classId ?: JSONObject.NULL) }))
                .put("decodedObjects", JSONArray(metadata.decodedObjects.map { obj -> JSONObject()
                    .put("pathId", obj.pathId)
                    .put("classId", obj.classId)
                    .put("name", obj.name ?: JSONObject.NULL)
                    .put("isActive", obj.isActive ?: JSONObject.NULL)
                    .put("isEnabled", obj.isEnabled ?: JSONObject.NULL)
                    .put("gameObject", obj.gameObject?.let { JSONObject().put("fileId", it.fileId).put("pathId", it.pathId) } ?: JSONObject.NULL)
                    .put("father", obj.father?.let { JSONObject().put("fileId", it.fileId).put("pathId", it.pathId) } ?: JSONObject.NULL)
                    .put("children", JSONArray(obj.children.map { ref -> JSONObject().put("fileId", ref.fileId).put("pathId", ref.pathId) }))
                    .put("components", JSONArray(obj.components.map { ref -> JSONObject().put("fileId", ref.fileId).put("pathId", ref.pathId) }))
                    .put("script", obj.script?.let { JSONObject().put("fileId", it.fileId).put("pathId", it.pathId) } ?: JSONObject.NULL) }))
                .put("externalPointerResolutions", JSONArray(metadata.externalPointerResolutions.map { link -> JSONObject()
                    .put("sourcePathId", link.sourcePathId).put("field", link.field)
                    .put("fileId", link.fileId).put("pathId", link.pathId)
                    .put("targetFilePath", link.targetFilePath ?: JSONObject.NULL)
                    .put("targetClassId", link.targetClassId ?: JSONObject.NULL)
                    .put("targetName", link.targetName ?: JSONObject.NULL).put("status", link.status) }))
                .put("monoBehaviourScriptBindings", JSONArray(metadata.monoBehaviourScriptBindings.map { binding -> JSONObject()
                    .put("sourcePathId", binding.sourcePathId).put("fileId", binding.fileId)
                    .put("scriptPathId", binding.scriptPathId)
                    .put("targetFilePath", binding.targetFilePath ?: JSONObject.NULL)
                    .put("monoScriptName", binding.monoScriptName ?: JSONObject.NULL)
                    .put("className", binding.className ?: JSONObject.NULL)
                    .put("namespace", binding.namespace ?: JSONObject.NULL)
                    .put("assemblyName", binding.assemblyName ?: JSONObject.NULL)
                    .put("executionOrder", binding.executionOrder ?: JSONObject.NULL)
                    .put("status", binding.status) }))
                .put("sceneGraph", metadata.sceneGraph?.let { graph -> JSONObject()
                    .put("unresolvedReferenceCount", graph.unresolvedReferenceCount)
                    .put("externalReferenceCount", graph.externalReferenceCount)
                    .put("nodes", JSONArray(graph.nodes.map { node -> JSONObject()
                        .put("gameObjectPathId", node.gameObjectPathId)
                        .put("name", node.name ?: JSONObject.NULL)
                        .put("isActive", node.isActive ?: JSONObject.NULL)
                        .put("transformPathId", node.transformPathId ?: JSONObject.NULL)
                        .put("parentGameObjectPathId", node.parentGameObjectPathId ?: JSONObject.NULL)
                        .put("childGameObjectPathIds", JSONArray(node.childGameObjectPathIds))
                        .put("componentPathIds", JSONArray(node.componentPathIds))
                        .put("unresolvedReferenceCount", node.unresolvedReferenceCount) })) } ?: JSONObject.NULL)
                .put("sceneDecodeNote", metadata.sceneDecodeNote ?: JSONObject.NULL)
                .put("note", metadata.note ?: JSONObject.NULL)
        }))
        root.put("unityFileHeaders", JSONArray(unityHeaders.map { (path, header) ->
            JSONObject().put("path", path).put("kind", header.kind)
                .put("formatVersion", header.formatVersion ?: JSONObject.NULL)
                .put("fileSize", header.fileSize ?: JSONObject.NULL)
                .put("metadataSize", header.metadataSize ?: JSONObject.NULL)
                .put("dataOffset", header.dataOffset ?: JSONObject.NULL)
                .put("endianness", header.endianness ?: JSONObject.NULL)
                .put("playerVersion", header.playerVersion ?: JSONObject.NULL)
                .put("engineVersion", header.engineVersion ?: JSONObject.NULL)
                .put("headerSize", header.headerSize)
                .put("note", header.note ?: JSONObject.NULL)
        }))
        root.put("files", JSONArray(entries.map { it.record.toJsonObject() }))
        root.put("issues", JSONArray(issues.map { it.toJsonObject() }))
        root.put("warnings", JSONArray(warnings))
        return root.toString(2)
    }

    private fun yesNo(value: Boolean) = if (value) "найден" else "не найден"

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GiB", value / (1024.0 * 1024 * 1024))
        value >= 1024L * 1024 -> String.format(Locale.US, "%.2f MiB", value / (1024.0 * 1024))
        value >= 1024L -> String.format(Locale.US, "%.2f KiB", value / 1024.0)
        else -> value.toString() + " B"
    }
}

