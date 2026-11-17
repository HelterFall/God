package com.ramen.god

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var selectedTree: Uri? = null
    @Volatile private var scanCancelled = false
    private lateinit var cancelScanButton: Button
    private var markdownReport: String? = null
    private var jsonReport: String? = null

    companion object {
        private const val PICK_TREE = 1001
        private const val SAVE_MD = 1002
        private const val SAVE_JSON = 1003
        private const val PREFS = "god_analyzer"
        private const val KEY_TREE = "tree_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTree = savedInstanceState?.getString("tree")?.let(Uri::parse)
            ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)
        buildUi()
        status.text = if (selectedTree != null) "Папка сохранена. Можно запускать анализ." else "Выберите корневую папку распакованной Unity-игры."
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        root.addView(TextView(this).apply {
            text = "GOD / UNITY GAME ANALYZER"
            textSize = 21f
            setTextColor(0xff18212f.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Сканирует папку на месте. EXE и DLL не запускаются, файлы игры не копируются."
            textSize = 13f
            setTextColor(0xff596579.toInt())
            setPadding(0, dp(6), 0, dp(10))
        })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        val choose = Button(this).apply {
            text = "1. Выбрать папку игры"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, PICK_TREE)
            }
        }
        val scan = Button(this).apply {
            text = "2. Анализировать"
            setOnClickListener { scanSelectedFolder() }
        }
        cancelScanButton = Button(this).apply {
            text = "Остановить анализ"
            isEnabled = false
            setOnClickListener {
                scanCancelled = true
                status.text = "Останавливаю анализ…"
                isEnabled = false
            }
        }
        val saveMd = Button(this).apply {
            text = "Экспорт отчёта Markdown"
            setOnClickListener { requestExport(SAVE_MD) }
        }
        val saveJson = Button(this).apply {
            text = "Экспорт отчёта JSON"
            setOnClickListener { requestExport(SAVE_JSON) }
        }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(24))
        }
        root.addView(choose, match())
        root.addView(scan, match())
        root.addView(cancelScanButton, match())
        root.addView(saveMd, match())
        root.addView(saveJson, match())
        root.addView(status)
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun requestExport(requestCode: Int) {
        val content = if (requestCode == SAVE_MD) markdownReport else jsonReport
        if (content == null) {
            status.text = "Сначала выполните анализ."
            return
        }
        val json = requestCode == SAVE_JSON
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (json) "application/json" else "text/markdown"
            putExtra(Intent.EXTRA_TITLE, if (json) "unity-game-analysis.json" else "unity-game-analysis.md")
        }
        startActivityForResult(intent, requestCode)
    }

    private fun scanSelectedFolder() {
        val tree = selectedTree
        if (tree == null) {
            status.text = "Сначала выберите папку игры."
            return
        }
        scanCancelled = false
        cancelScanButton.isEnabled = true
        status.text = "Анализирую. Исходные файлы остаются на месте…"
        output.text = ""
        markdownReport = null
        jsonReport = null
        executor.execute {
            try {
                val root = DocumentFile.fromTreeUri(this, tree) ?: error("Не удалось открыть выбранную папку.")
                val result = GameScanner(this).scan(
                    root,
                    shouldCancel = { scanCancelled }
                ) { count, path ->
                    if (count % 128 == 0) {
                        runOnUiThread {
                            if (!scanCancelled) status.text = "Просмотрено записей: $count\nПоследний путь: $path"
                        }
                    }
                }
                markdownReport = result.markdown
                jsonReport = result.json
                runOnUiThread {
                    output.text = result.markdown
                    status.text = "Анализ завершён. Доступен экспорт Markdown и JSON."
                    cancelScanButton.isEnabled = false
                }
            } catch (_: ScanCancelledException) {
                runOnUiThread {
                    status.text = "Анализ остановлен. Отчёт не создан."
                    cancelScanButton.isEnabled = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Ошибка анализа: " + (e.message ?: e.javaClass.simpleName)
                    cancelScanButton.isEnabled = false
                }
            }
        }
    }

    @Deprecated("Activity result API kept minimal for this prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            PICK_TREE -> {
                val readFlag = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(uri, readFlag)
                } catch (_: SecurityException) {
                    status.text = "Папка выбрана, но разрешение не удалось сохранить."
                }
                selectedTree = uri
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
                status.text = "Папка выбрана. Файлы не копируются."
            }
            SAVE_MD, SAVE_JSON -> {
                val content = if (requestCode == SAVE_JSON) jsonReport else markdownReport
                if (content == null) return
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                    status.text = "Отчёт сохранён."
                } catch (e: Exception) {
                    status.text = "Ошибка сохранения: " + e.message
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tree", selectedTree?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

private data class ScanEntry(val path: String, val name: String, val directory: Boolean, val size: Long, val uri: Uri)
private data class PeInfo(val machine: String, val managed: Boolean, val imports: List<String>, val note: String? = null)
private data class ScanResult(val markdown: String, val json: String)
private class ScanCancelledException : Exception()

private class GameScanner(private val activity: Activity) {
    private val entries = mutableListOf<ScanEntry>()
    private val warnings = mutableListOf<String>()
    private val peResults = linkedMapOf<String, PeInfo>()
    private var totalBytes = 0L

    companion object {
        private const val MAX_ENTRIES = 100_000
        private const val MAX_DEPTH = 40
        private const val MAX_PE_FILES = 150
        private const val MAX_PE_READ = 1024 * 1024
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

        val managedBinaries = peResults.filterValues { it.managed }.keys
        val nativeBinaries = peResults.filterValues { !it.managed }.keys
        val importSummary = peResults.values.flatMap { it.imports }
            .groupingBy { it.lowercase(Locale.ROOT) }.eachCount().entries.sortedByDescending { it.value }

        val md = buildMarkdown(
            root.name ?: "(unnamed)", backend, unityVersion, gameAssemblies, unityModules,
            monoFolder, managedDir, hasGameAssembly, hasMetadata, hasIl2CppDir, hasUnityPlayer,
            managedBinaries, nativeBinaries, importSummary
        )
        val json = buildJson(
            root.name ?: "(unnamed)", backend, unityVersion, gameAssemblies, unityModules,
            managedBinaries, nativeBinaries, importSummary
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
        managedBinaries: Set<String>, nativeBinaries: Set<String>, importSummary: List<Map.Entry<String, Int>>
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
        importSummary: List<Map.Entry<String, Int>>
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", 2)
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

private object PeParser {
    private data class Section(val virtualSize: Long, val virtualAddress: Long, val rawSize: Long, val rawPointer: Long)

    fun parse(bytes: ByteArray): PeInfo? {
        if (bytes.size < 64 || u8(bytes, 0) != 0x4d || u8(bytes, 1) != 0x5a) return null
        val peLong = u32(bytes, 0x3c)
        if (peLong > Int.MAX_VALUE) return null
        val pe = peLong.toInt()
        if (!has(bytes, pe, 24)) return null
        if (u8(bytes, pe) != 0x50 || u8(bytes, pe + 1) != 0x45 || u8(bytes, pe + 2) != 0 || u8(bytes, pe + 3) != 0) return null

        val machineCode = u16(bytes, pe + 4)
        val sectionCount = u16(bytes, pe + 6)
        val optionalSize = u16(bytes, pe + 20)
        val optional = pe + 24
        if (!has(bytes, optional, optionalSize) || optionalSize < 96) return null

        val machine = when (machineCode) {
            0x014c -> "x86"
            0x8664 -> "x86-64"
            0xaa64 -> "ARM64"
            else -> "unknown (0x" + machineCode.toString(16) + ")"
        }
        val directory = when (u16(bytes, optional)) {
            0x10b -> optional + 96
            0x20b -> optional + 112
            else -> return PeInfo(machine, false, emptyList(), "unknown optional header")
        }
        if (!has(bytes, directory, 15 * 8)) return PeInfo(machine, false, emptyList(), "data directories truncated")

        val importRva = u32(bytes, directory + 8)
        val clrRva = u32(bytes, directory + 14 * 8)
        val sectionsStart = optional + optionalSize
        val sections = mutableListOf<Section>()
        for (index in 0 until minOf(sectionCount, 96)) {
            val offset = sectionsStart + index * 40
            if (!has(bytes, offset, 40)) break
            sections.add(Section(
                u32(bytes, offset + 8), u32(bytes, offset + 12),
                u32(bytes, offset + 16), u32(bytes, offset + 20)
            ))
        }

        val imports = if (importRva == 0L) emptyList() else parseImports(bytes, importRva, sections)
        val note = if (importRva != 0L && imports.isEmpty()) "import table outside read window or undecodable" else null
        return PeInfo(machine, clrRva != 0L, imports, note)
    }

    private fun parseImports(bytes: ByteArray, rva: Long, sections: List<Section>): List<String> {
        val start = rvaToOffset(rva, sections) ?: return emptyList()
        val result = linkedSetOf<String>()
        var cursor = start
        repeat(512) {
            if (!has(bytes, cursor, 20)) return result.toList()
            val fields = (0..4).map { u32(bytes, cursor + it * 4) }
            if (fields.all { it == 0L }) return result.toList()
            val nameOffset = rvaToOffset(fields[3], sections)
            if (nameOffset != null) {
                val name = readCString(bytes, nameOffset, 260)
                if (name.isNotBlank()) result.add(name)
            }
            cursor += 20
        }
        return result.toList()
    }

    private fun rvaToOffset(rva: Long, sections: List<Section>): Int? {
        for (section in sections) {
            val span = maxOf(section.virtualSize, section.rawSize)
            if (rva >= section.virtualAddress && rva < section.virtualAddress + span) {
                val delta = rva - section.virtualAddress
                if (delta >= section.rawSize) return null
                val offset = section.rawPointer + delta
                if (offset > Int.MAX_VALUE) return null
                return offset.toInt()
            }
        }
        return null
    }

    private fun readCString(bytes: ByteArray, offset: Int, maximum: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        val limit = minOf(bytes.size, offset + maximum)
        var end = offset
        while (end < limit && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.ISO_8859_1)
    }

    private fun has(bytes: ByteArray, offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size.toLong()

    private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 0xff
    private fun u16(bytes: ByteArray, offset: Int) = u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long =
        u8(bytes, offset).toLong() or (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or (u8(bytes, offset + 3).toLong() shl 24)
}
