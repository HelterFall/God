package com.ramen.god

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

private data class ScanEntry(val path: String, val name: String, val directory: Boolean, val size: Long, val uri: Uri)
data class ScanResult(val markdown: String, val json: String, val isPartial: Boolean = false)
class ScanCancelledException : Exception()

class GameScanner(private val contentResolver: ContentResolver) {
    private val entries = mutableListOf<ScanEntry>()
    private val warnings = mutableListOf<String>()
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
        root: DocumentFile,
        shouldCancel: () -> Boolean,
        onProgress: (Int, String) -> Unit
    ): ScanResult {
        entries.clear()
        warnings.clear()
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
        try {
            walk(root, "", 0, shouldCancel, onProgress)
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
        if (peCandidates.size > MAX_PE_FILES) warnings.add("PE-анализ ограничен первыми " + MAX_PE_FILES + " EXE/DLL.")
        try {
            peCandidates.take(MAX_PE_FILES).forEach { entry ->
                if (shouldCancel()) throw ScanCancelledException()
                peAttemptedCount++
                val info = readPeInfo(entry)
                if (info == null) {
                    peFailedCount++
                } else {
                    peResults[entry.path] = info
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
        if (unityCandidates.size > MAX_UNITY_HEADER_FILES) {
            warnings.add("Проверка Unity-заголовков ограничена первыми " + MAX_UNITY_HEADER_FILES + " файлами-кандидатами.")
        }
        try {
            unityCandidates.take(MAX_UNITY_HEADER_FILES).forEachIndexed { index, entry ->
            if (shouldCancel()) throw ScanCancelledException()
            val deepMetadataRead = index < MAX_UNITY_METADATA_FILES
            unityHeaderAttemptedCount++

            var headerParsed = false
            var metadataParsed = false
            var diagnostic: String? = null
            try {
                val readLimit = if (deepMetadataRead) MAX_UNITY_METADATA_READ else MAX_UNITY_HEADER_READ
                val prefix = readPrefix(entry.uri, readLimit)
                if (prefix == null) {
                    diagnostic = "не удалось открыть файл для чтения"
                } else {
                    val header = UnityFileHeaderParser.parse(prefix, entry.size)
                    if (header == null) {
                        diagnostic = "заголовок не распознан в прочитанном диапазоне"
                    } else {
                        headerParsed = true
                        unityHeaders[entry.path] = header
                        if (header.kind == "SerializedFile") {
                            unityMetadataCandidateCount++
                            if (deepMetadataRead) {
                                unityMetadataAttemptedCount++
                                val metadata = UnityMetadataParser.parse(prefix, header)
                                if (metadata == null) {
                                    diagnostic = "metadata не разобрана в доступном диапазоне"
                                } else {
                                    unityMetadata[entry.path] = readMonoScriptRecords(entry, header, metadata)
                                    metadataParsed = true
                                }
                            } else {
                                unityMetadataWindowLimitedCount++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                diagnostic = e.message ?: e.javaClass.simpleName
            }
            if (!headerParsed) {
                unityHeaderFailedCount++
                unityMetadataCandidateCount++
                unityMetadataPrerequisiteSkippedCount++
            } else if (deepMetadataRead && unityHeaders[entry.path]?.kind == "SerializedFile" && !metadataParsed) {
                unityMetadataFailedCount++
            }
            diagnostic?.let { warnings.add("Unity-анализ " + entry.path + ": " + it) }
            }
        } catch (_: ScanCancelledException) {
            return buildPartialResult(root.name ?: "(unnamed)")
        }

        resolveExternalMonoScriptRecords(files)
        if (shouldCancel()) return buildPartialResult(root.name ?: "(unnamed)")
        resolveExternalScenePointers(files)
        if (shouldCancel()) return buildPartialResult(root.name ?: "(unnamed)")
        resolveMonoBehaviourScriptBindings(files)
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
            unityCandidates.size,
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

    private fun buildPartialResult(rootName: String): ScanResult =
        PartialReportBuilder.build(
            rootName,
            entries.map { PartialFileEntry(it.path, it.directory, it.size) },
            totalBytes
        )

    private fun walk(
        dir: DocumentFile,
        parent: String,
        depth: Int,
        shouldCancel: () -> Boolean,
        onProgress: (Int, String) -> Unit
    ) {
        if (shouldCancel()) throw ScanCancelledException()
        if (depth > MAX_DEPTH) {
            depthLimitHitCount++
            warnings.add("Достигнут лимит глубины " + MAX_DEPTH + " в " + parent)
            return
        }
        if (entries.size >= MAX_ENTRIES) {
            entryLimitReached = true
            warnings.add("Достигнут лимит " + MAX_ENTRIES + " записей; дерево неполное.")
            return
        }
        val children = try { dir.listFiles() } catch (e: Exception) {
            inaccessibleDirectoryCount++
            warnings.add("Не удалось прочитать каталог " + parent + ": " + e.message)
            return
        }
        for (child in children) {
            if (shouldCancel()) throw ScanCancelledException()
            if (entries.size >= MAX_ENTRIES) {
                entryLimitReached = true
                warnings.add("Достигнут лимит " + MAX_ENTRIES + " записей; дерево неполное.")
                break
            }
            val name = child.name ?: "(unnamed)"
            val path = if (parent.isEmpty()) name else parent + "/" + name
            val isDir = child.isDirectory
            val size = if (isDir) 0L else child.length().coerceAtLeast(0L)
            entries.add(ScanEntry(path, name, isDir, size, child.uri))
            onProgress(entries.size, path)
            if (!isDir) totalBytes += size
            if (isDir) walk(child, path, depth + 1, shouldCancel, onProgress)
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
                    val bytes = readPrefix(entry.uri, MAX_GGM_READ) ?: return@forEach
                    val text = String(bytes, Charsets.ISO_8859_1)
                    UnityVersionResolver.extractVersions(text).take(8).forEach { version ->
                        evidence.add(UnityVersionEvidence(
                            version, "globalgamemanagers-raw", entry.path, "medium"
                        ))
                    }
                } catch (e: Exception) {
                    warnings.add("Не удалось прочитать " + entry.path + " для поиска версии Unity: " + e.message)
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

    private fun resolveExternalMonoScriptRecords(files: List<ScanEntry>) {
        val metadataSnapshot = unityMetadata.toMap()
        val updatedBindings = metadataSnapshot.mapValues { it.value.scriptTypeBindings.toMutableList() }.toMutableMap()
        val changedOwners = mutableSetOf<String>()
        val requests = mutableListOf<ExternalMonoScriptRequest>()
        val discoveredMetadata = linkedMapOf<String, UnityMetadataSummary>()
        val attemptedMetadata = mutableSetOf<String>()
        var extraMetadataReads = 0

        metadataSnapshot.forEach { (ownerPath, metadata) ->
            metadata.scriptTypeBindings.forEachIndexed { index, binding ->
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

                // The initial metadata pass is deliberately limited to 40 files.
                // External references can promote another referenced file into a
                // second bounded pass, without rescanning the full game folder.
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
                    val prefix = readPrefix(targetEntry.uri, MAX_UNITY_METADATA_READ)
                    if (prefix != null) {
                        if (targetHeader == null) {
                            targetHeader = UnityFileHeaderParser.parse(prefix, targetEntry.size)
                            if (targetHeader != null) unityHeaders[targetEntry.path] = targetHeader
                        }
                        if (targetHeader?.kind == "SerializedFile") {
                            targetMetadata = UnityMetadataParser.parse(prefix, targetHeader)?.let { parsed ->
                                readMonoScriptRecords(targetEntry, targetHeader, parsed)
                            }
                            if (targetMetadata != null) discoveredMetadata[targetEntry.path] = targetMetadata
                        }
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
                requests.add(
                    ExternalMonoScriptRequest(
                        ownerPath,
                        index,
                        targetEntry,
                        targetHeader,
                        MonoScriptObjectTarget(targetObject.pathId, targetObject.byteStart, targetObject.byteSize)
                    )
                )
            }
        }

        // Add newly parsed targets only after iterating the original owner set.
        discoveredMetadata.forEach { (path, metadata) ->
            if (!unityMetadata.containsKey(path)) unityMetadata[path] = metadata
        }

        val extraScripts = mutableMapOf<String, MutableList<UnityMonoScriptSummary>>()
        requests.groupBy { it.targetEntry.path }.forEach { (_, group) ->
            val first = group.first()
            val littleEndian = when (first.targetHeader.endianness) {
                "Little Endian" -> true
                "Big Endian" -> false
                else -> null
            }
            if (littleEndian == null) {
                group.forEach { request ->
                    val bindings = updatedBindings[request.ownerPath] ?: return@forEach
                    val current = bindings.getOrNull(request.bindingIndex) ?: return@forEach
                    bindings[request.bindingIndex] = current.copy(resolution = "external-file-endianness-unknown")
                    changedOwners.add(request.ownerPath)
                }
                return@forEach
            }
            try {
                val input = contentResolver.openInputStream(first.targetEntry.uri)
                if (input == null) {
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
                        stream,
                        first.targetHeader.dataOffset!!,
                        first.targetHeader.fileSize!!,
                        group.map { it.target },
                        littleEndian
                    )
                }
                val parsedByPath = readResult.scripts.associateBy { it.pathId }
                group.forEach { request ->
                    val bindings = updatedBindings[request.ownerPath] ?: return@forEach
                    val current = bindings.getOrNull(request.bindingIndex) ?: return@forEach
                    val script = parsedByPath[request.target.pathId]
                    if (script == null) {
                        bindings[request.bindingIndex] = current.copy(resolution = "external-monoscript-unreadable")
                    } else {
                        bindings[request.bindingIndex] = current.copy(
                            monoScriptName = script.name,
                            className = script.className,
                            namespace = script.namespace,
                            assemblyName = script.assemblyName,
                            executionOrder = script.executionOrder,
                            resolution = "external-monoscript-decoded"
                        )
                        extraScripts.getOrPut(request.ownerPath) { mutableListOf() }
                            .add(script.copy(sourceFilePath = request.targetEntry.path))
                    }
                    changedOwners.add(request.ownerPath)
                }
            } catch (e: Exception) {
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
            unityMetadata[ownerPath] = old.copy(
                scriptTypeBindings = bindings,
                monoScripts = scripts
            )
        }
    }

    private fun resolveExternalScenePointers(files: List<ScanEntry>) {
        val snapshot = unityMetadata.toMap()
        val resolutions = UnityExternalPointerResolver.resolve(snapshot) { externalPath ->
            val match = resolveExternalFile(externalPath, files)
            if (match.ambiguous) null else match.entry?.path
        }
        resolutions.forEach { (path, links) ->
            val old = unityMetadata[path] ?: return@forEach
            unityMetadata[path] = old.copy(externalPointerResolutions = links)
        }
    }

    private fun resolveMonoBehaviourScriptBindings(files: List<ScanEntry>) {
        val snapshot = unityMetadata.toMap()
        val bindings = UnityMonoBehaviourScriptResolver.resolve(snapshot) { externalPath ->
            val match = resolveExternalFile(externalPath, files)
            if (match.ambiguous) null else match.entry?.path
        }
        bindings.forEach { (path, items) ->
            val old = unityMetadata[path] ?: return@forEach
            unityMetadata[path] = old.copy(monoBehaviourScriptBindings = items)
        }
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
        val dataOffset = header.dataOffset ?: return metadata
        val fileSize = header.fileSize ?: entry.size
        val littleEndian = when (header.endianness) {
            "Little Endian" -> true
            "Big Endian" -> false
            else -> return metadata
        }
        val targets = metadata.scriptTypeBindings.mapNotNull { binding ->
            if (binding.fileId != 0 || binding.pathId == null ||
                binding.objectByteStart == null || binding.objectByteSize == null) {
                null
            } else {
                MonoScriptObjectTarget(binding.pathId, binding.objectByteStart, binding.objectByteSize)
            }
        }.distinctBy { it.pathId }

        var updated = metadata
        if (targets.isNotEmpty()) {
            updated = try {
                val input = contentResolver.openInputStream(entry.uri)
                    ?: return readUnitySceneRecords(entry, header, metadata)
                val result = input.use { stream ->
                    MonoScriptParser.readObjects(stream, dataOffset, fileSize, targets, littleEndian)
                }
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
            } catch (e: Exception) {
                val readNote = "MonoScript payload не прочитан: " + (e.message ?: e.javaClass.simpleName)
                metadata.copy(note = listOfNotNull(metadata.note, readNote).joinToString(" "))
            }
        }
        return readUnitySceneRecords(entry, header, updated)
    }

    private fun readUnitySceneRecords(
        entry: ScanEntry,
        header: UnityFileHeader,
        metadata: UnityMetadataSummary
    ): UnityMetadataSummary {
        val dataOffset = header.dataOffset ?: return metadata.copy(
            sceneDecodeNote = "Неизвестен dataOffset; payload объектов не прочитан."
        )
        val fileSize = header.fileSize ?: entry.size
        val littleEndian = when (header.endianness) {
            "Little Endian" -> true
            "Big Endian" -> false
            else -> return metadata.copy(sceneDecodeNote = "Неизвестен порядок байтов; payload объектов не прочитан.")
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
            return metadata.copy(sceneDecodeNote = reason)
        }
        return try {
            val input = contentResolver.openInputStream(entry.uri)
                ?: return metadata.copy(sceneDecodeNote = "Не удалось открыть файл для чтения scene objects.")
            val result = input.use { stream ->
                UnityObjectDecoder.readObjects(stream, dataOffset, fileSize, targets, littleEndian)
            }
            val graph = UnitySceneGraphResolver.resolve(result.objects)
            metadata.copy(decodedObjects = result.objects, sceneGraph = graph, sceneDecodeNote = result.note)
        } catch (e: Exception) {
            metadata.copy(sceneDecodeNote = "Scene object payload не прочитан: " +
                (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun readPeInfo(entry: ScanEntry): PeInfo? {
        return try {
            var data = readPrefix(entry.uri, MAX_PE_READ) ?: return null
            var info = PeParser.parse(data) ?: return null
            val metadataEnd = info.clrMetadataOffset?.toLong()?.plus(info.clrMetadataSize ?: 0L)
            if (info.managed && metadataEnd != null && metadataEnd > data.size.toLong()) {
                val expanded = readPrefix(entry.uri, MAX_CLR_PE_READ)
                if (expanded != null) {
                    data = expanded
                    info = PeParser.parse(data) ?: info
                }
            }
            if (info.managed) {
                clrAttemptedCount++
                try {
                    val summary = ClrMetadataParser.parse(
                        data, info.clrMetadataOffset, info.clrMetadataSize,
                        info.clrRuntimeVersion, info.clrFlags
                    )
                    if (summary == null) {
                        clrFailedCount++
                    } else {
                        clrResults[entry.path] = summary
                    }
                } catch (e: Exception) {
                    clrFailedCount++
                    warnings.add("CLR metadata analysis failed for " + entry.path + ": " + e.message)
                }
            }
            info
        } catch (e: Exception) {
            warnings.add("PE-анализ не выполнен для " + entry.path + ": " + e.message)
            null
        }
    }

    private fun readPrefix(uri: Uri, maximum: Int): ByteArray? {
        val input = contentResolver.openInputStream(uri) ?: return null
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
        appendLine("- Таблица импортов показывает статические импорты PE; динамические LoadLibrary и P/Invoke полностью не покрываются.")
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
        root.put("schemaVersion", 23)
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
        root.put("files", JSONArray(entries.map {
            JSONObject().put("path", it.path).put("directory", it.directory).put("size", it.size)
        }))
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

