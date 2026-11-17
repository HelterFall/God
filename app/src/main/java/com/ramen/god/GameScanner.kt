package com.ramen.god

import android.app.Activity
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.regex.Pattern

private data class ScanEntry(val path: String, val name: String, val directory: Boolean, val size: Long, val uri: Uri)
data class ScanResult(val markdown: String, val json: String)
class ScanCancelledException : Exception()

class GameScanner(private val activity: Activity) {
    private val entries = mutableListOf<ScanEntry>()
    private val warnings = mutableListOf<String>()
    private val peResults = linkedMapOf<String, PeInfo>()
    private val unityHeaders = linkedMapOf<String, UnityFileHeader>()
    private val unityMetadata = linkedMapOf<String, UnityMetadataSummary>()
    private var totalBytes = 0L

    companion object {
        private const val MAX_ENTRIES = 100_000
        private const val MAX_DEPTH = 40
        private const val MAX_PE_FILES = 150
        private const val MAX_PE_READ = 1024 * 1024
        private const val MAX_UNITY_HEADER_FILES = 500
        private const val MAX_UNITY_HEADER_READ = 256
        private const val MAX_UNITY_METADATA_FILES = 40
        private const val MAX_UNITY_METADATA_READ = 1024 * 1024
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
        unityHeaders.clear()
        unityMetadata.clear()
        totalBytes = 0L
        walk(root, "", 0, shouldCancel, onProgress)
        if (shouldCancel()) throw ScanCancelledException()
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

        val unityVersion = findUnityVersion(files)
        val peCandidates = files.filter { it.name.endsWith(".exe", true) || it.name.endsWith(".dll", true) }
        if (peCandidates.size > MAX_PE_FILES) warnings.add("PE-анализ ограничен первыми " + MAX_PE_FILES + " EXE/DLL.")
        peCandidates.take(MAX_PE_FILES).forEach { entry ->
            if (shouldCancel()) throw ScanCancelledException()
            readPeInfo(entry)?.let { peResults[entry.path] = it }
        }

        val unityCandidates = files.filter { isUnityHeaderCandidate(it.name) }
        if (unityCandidates.size > MAX_UNITY_HEADER_FILES) {
            warnings.add("Проверка Unity-заголовков ограничена первыми " + MAX_UNITY_HEADER_FILES + " файлами-кандидатами.")
        }
        unityCandidates.take(MAX_UNITY_HEADER_FILES).forEachIndexed { index, entry ->
            if (shouldCancel()) throw ScanCancelledException()
            try {
                val readLimit = if (index < MAX_UNITY_METADATA_FILES) MAX_UNITY_METADATA_READ else MAX_UNITY_HEADER_READ
                val prefix = readPrefix(entry.uri, readLimit) ?: return@forEachIndexed
                UnityFileHeaderParser.parse(prefix, entry.size)?.let { header ->
                    unityHeaders[entry.path] = header
                    UnityMetadataParser.parse(prefix, header)?.let { unityMetadata[entry.path] = it }
                }
            } catch (e: Exception) {
                warnings.add("Unity-заголовок не прочитан для " + entry.path + ": " + e.message)
            }
        }

        val managedBinaries = peResults.filterValues { it.managed }.keys
        val nativeBinaries = peResults.filterValues { !it.managed }.keys
        val importSummary = peResults.values.flatMap { it.imports }
            .groupingBy { it.lowercase(Locale.ROOT) }.eachCount().entries.sortedByDescending { it.value }

        val md = buildMarkdown(
            root.name ?: "(unnamed)", backend, unityVersion, gameAssemblies, unityModules,
            monoFolder, managedDir, hasGameAssembly, hasMetadata, hasIl2CppDir, hasUnityPlayer,
            managedBinaries, nativeBinaries, importSummary, unityHeaders, unityMetadata
        )
        val json = buildJson(
            root.name ?: "(unnamed)", backend, unityVersion, gameAssemblies, unityModules,
            managedBinaries, nativeBinaries, importSummary, unityHeaders, unityMetadata
        )
        return ScanResult(md, json)
    }

    private fun walk(
        dir: DocumentFile,
        parent: String,
        depth: Int,
        shouldCancel: () -> Boolean,
        onProgress: (Int, String) -> Unit
    ) {
        if (shouldCancel()) throw ScanCancelledException()
        if (depth > MAX_DEPTH) {
            warnings.add("Достигнут лимит глубины " + MAX_DEPTH + " в " + parent)
            return
        }
        if (entries.size >= MAX_ENTRIES) {
            warnings.add("Достигнут лимит " + MAX_ENTRIES + " записей; дерево неполное.")
            return
        }
        val children = try { dir.listFiles() } catch (e: Exception) {
            warnings.add("Не удалось прочитать каталог " + parent + ": " + e.message)
            return
        }
        for (child in children) {
            if (shouldCancel()) throw ScanCancelledException()
            if (entries.size >= MAX_ENTRIES) {
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

    private fun isUnityHeaderCandidate(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".assets") || lower.endsWith(".bundle") ||
            lower.endsWith(".unity3d") || lower.endsWith(".assetbundle") ||
            lower == "globalgamemanagers" || lower.startsWith("level")
    }

    private fun findUnityVersion(files: List<ScanEntry>): String? {
        val candidate = files.firstOrNull { it.name.equals("globalgamemanagers", true) } ?: return null
        return try {
            val bytes = readPrefix(candidate.uri, MAX_GGM_READ) ?: return null
            val matcher = Pattern.compile("20\\d{2}\\.\\d+\\.\\d+[abfp]\\d+").matcher(String(bytes, Charsets.ISO_8859_1))
            if (matcher.find()) matcher.group() else null
        } catch (e: Exception) {
            warnings.add("Не удалось прочитать globalgamemanagers: " + e.message)
            null
        }
    }

    private fun readPeInfo(entry: ScanEntry): PeInfo? {
        return try {
            val data = readPrefix(entry.uri, MAX_PE_READ) ?: return null
            PeParser.parse(data)
        } catch (e: Exception) {
            warnings.add("PE-анализ не выполнен для " + entry.path + ": " + e.message)
            null
        }
    }

    private fun readPrefix(uri: Uri, maximum: Int): ByteArray? {
        val input = activity.contentResolver.openInputStream(uri) ?: return null
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
        rootName: String, backend: String, unityVersion: String?, gameAssemblies: List<ScanEntry>,
        unityModules: List<ScanEntry>, monoFolder: Boolean, managedDir: Boolean,
        hasGameAssembly: Boolean, hasMetadata: Boolean, hasIl2CppDir: Boolean, hasUnityPlayer: Boolean,
        managedBinaries: Set<String>, nativeBinaries: Set<String>, importSummary: List<Map.Entry<String, Int>>,
        unityHeaders: Map<String, UnityFileHeader>, unityMetadata: Map<String, UnityMetadataSummary>
    ): String = buildString {
        appendLine("# Unity Game Architecture Report")
        appendLine()
        appendLine("- Root: " + rootName)
        appendLine("- Generated: " + java.time.Instant.now())
        appendLine("- Backend assessment: " + backend)
        appendLine("- Unity version: " + (unityVersion ?: "не обнаружена"))
        appendLine("- Indexed entries: " + entries.size)
        appendLine("- Total size: " + formatBytes(totalBytes))
        appendLine("- UnityPlayer.dll: " + yesNo(hasUnityPlayer))
        appendLine("- MonoBleedingEdge: " + yesNo(monoFolder))
        appendLine("- Managed directory: " + yesNo(managedDir))
        appendLine("- GameAssembly.dll: " + yesNo(hasGameAssembly))
        appendLine("- il2cpp_data + global-metadata.dat: " + yesNo(hasIl2CppDir && hasMetadata))
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
        appendLine("## Unity serialized files and bundles")
        if (unityHeaders.isEmpty()) appendLine("- Поддерживаемые заголовки не обнаружены среди проверенных кандидатов")
        unityHeaders.forEach { (path, header) ->
            appendLine("- " + path + ": " + header.kind +
                (header.formatVersion?.let { "; format=" + it } ?: "") +
                (header.fileSize?.let { "; fileSize=" + it } ?: "") +
                (header.metadataSize?.let { "; metadata=" + it } ?: "") +
                (header.dataOffset?.let { "; dataOffset=" + it } ?: "") +
                (header.endianness?.let { "; " + it } ?: "") +
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
                (metadata.scriptTypeCount?.let { "; MonoScriptRefs=" + it } ?: "") +
                (metadata.externalReferenceCount?.let { "; externalRefs=" + it } ?: "") +
                (metadata.objectSamples.take(20).joinToString(prefix = if (metadata.objectSamples.isEmpty()) "" else "; sampleObjects=", separator = ", ") { obj -> "pathId=${obj.pathId}:type=${obj.classId ?: obj.typeIndex}:size=${obj.byteSize}@${obj.byteStart}" }) +
                (metadata.note?.let { "; " + it } ?: ""))
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
        appendLine("## Warnings")
        if (warnings.isEmpty()) appendLine("- Нет")
        warnings.forEach { appendLine("- " + it) }
        appendLine()
        appendLine("## Notes")
        appendLine("- Исходные файлы читаются на месте через SAF: не копируются, не изменяются и не запускаются.")
        appendLine("- Mono/IL2CPP классифицируется по структуре и является эвристикой.")
        appendLine("- CLR-флаг PE помогает отличить managed assembly от native PE, но не анализирует все managed references.")
        appendLine("- Таблица импортов показывает статические импорты PE; динамические LoadLibrary и P/Invoke полностью не покрываются.")
        appendLine("- Поиск версии Unity эвристический, результат желательно сверять по нескольким источникам.")
    }

    private fun buildJson(
        rootName: String, backend: String, unityVersion: String?, gameAssemblies: List<ScanEntry>,
        unityModules: List<ScanEntry>, managedBinaries: Set<String>, nativeBinaries: Set<String>,
        importSummary: List<Map.Entry<String, Int>>, unityHeaders: Map<String, UnityFileHeader>,
        unityMetadata: Map<String, UnityMetadataSummary>
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", 4)
        root.put("rootName", rootName)
        root.put("generatedAt", java.time.Instant.now().toString())
        root.put("backendAssessment", backend)
        root.put("unityVersion", unityVersion ?: JSONObject.NULL)
        root.put("entryCount", entries.size)
        root.put("totalFileBytes", totalBytes)
        root.put("gameAssemblies", JSONArray(gameAssemblies.map { JSONObject().put("path", it.path).put("size", it.size) }))
        root.put("unityEngineModules", JSONArray(unityModules.map { JSONObject().put("path", it.path).put("size", it.size) }))
        root.put("managedPeAssemblies", JSONArray(managedBinaries))
        root.put("nativePeBinaries", JSONArray(nativeBinaries))
        root.put("nativeImports", JSONArray(importSummary.map {
            JSONObject().put("dll", it.key).put("referencedByCount", it.value)
        }))
        root.put("unityMetadata", JSONArray(unityMetadata.map { (path, metadata) ->
            JSONObject().put("path", path)
                .put("unityVersion", metadata.unityVersion ?: JSONObject.NULL)
                .put("targetPlatform", metadata.targetPlatform ?: JSONObject.NULL)
                .put("typeTreeEnabled", metadata.typeTreeEnabled ?: JSONObject.NULL)
                .put("typeCount", metadata.typeCount ?: JSONObject.NULL)
                .put("objectCount", metadata.objectCount ?: JSONObject.NULL)
                .put("scriptTypeCount", metadata.scriptTypeCount ?: JSONObject.NULL)
                .put("externalReferenceCount", metadata.externalReferenceCount ?: JSONObject.NULL)
                .put("objectSamples", JSONArray(metadata.objectSamples.map { obj -> JSONObject().put("pathId", obj.pathId).put("byteStart", obj.byteStart).put("byteSize", obj.byteSize).put("typeIndex", obj.typeIndex).put("classId", obj.classId ?: JSONObject.NULL) }))
                .put("note", metadata.note ?: JSONObject.NULL)
        }))
        root.put("unityFileHeaders", JSONArray(unityHeaders.map { (path, header) ->
            JSONObject().put("path", path).put("kind", header.kind)
                .put("formatVersion", header.formatVersion ?: JSONObject.NULL)
                .put("fileSize", header.fileSize ?: JSONObject.NULL)
                .put("metadataSize", header.metadataSize ?: JSONObject.NULL)
                .put("dataOffset", header.dataOffset ?: JSONObject.NULL)
                .put("endianness", header.endianness ?: JSONObject.NULL)
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

