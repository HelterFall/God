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
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var selectedTree: Uri? = null
    private var latestReport: String? = null

    companion object {
        private const val PICK_TREE = 1001
        private const val CREATE_REPORT = 1002
        private const val PREFS = "god_analyzer"
        private const val KEY_TREE = "tree_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTree = savedInstanceState?.getString("tree")?.let(Uri::parse)
            ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)
        buildUi()
        status.text = if (selectedTree != null) "Папка выбрана ранее. Можно запускать анализ." else "Выберите корневую папку распакованной Unity-игры."
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        root.addView(TextView(this).apply {
            text = "GOD / UNITY FOLDER ANALYZER"
            textSize = 20f
            setTextColor(0xff202124.toInt())
        })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        val choose = Button(this).apply {
            text = "Выбрать папку игры"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, PICK_TREE)
            }
        }
        val scan = Button(this).apply {
            text = "Анализировать папку"
            setOnClickListener { scanSelectedFolder() }
        }
        val export = Button(this).apply {
            text = "Экспортировать Markdown-отчёт"
            setOnClickListener {
                if (latestReport == null) status.text = "Сначала выполните анализ."
                else {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/markdown"
                        putExtra(Intent.EXTRA_TITLE, "unity-game-architecture.md")
                    }
                    startActivityForResult(intent, CREATE_REPORT)
                }
            }
        }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(24))
        }
        root.addView(choose, match())
        root.addView(scan, match())
        root.addView(export, match())
        root.addView(status)
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun scanSelectedFolder() {
        val tree = selectedTree
        if (tree == null) {
            status.text = "Сначала выберите папку игры."
            return
        }
        status.text = "Анализирую. Файлы не копируются и не запускаются…"
        output.text = ""
        executor.execute {
            try {
                val root = DocumentFile.fromTreeUri(this, tree) ?: error("Не удалось открыть папку.")
                val report = GameScanner(this).scan(root)
                latestReport = report
                runOnUiThread {
                    output.text = report
                    status.text = "Анализ завершён. Можно экспортировать отчёт."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка анализа: ${e.message}" }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            PICK_TREE -> {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                selectedTree = uri
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
                status.text = "Папка выбрана. Исходные файлы останутся на месте."
            }
            CREATE_REPORT -> {
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(latestReport ?: "") }
                    status.text = "Отчёт сохранён."
                } catch (e: Exception) {
                    status.text = "Ошибка сохранения: ${e.message}"
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

private data class Entry(val path: String, val name: String, val directory: Boolean, val size: Long)

private class GameScanner(private val activity: Activity) {
    private val entries = mutableListOf<Entry>()
    private val errors = mutableListOf<String>()
    private var totalBytes = 0L
    private val maxEntries = 100_000
    private val maxDepth = 40

    fun scan(root: DocumentFile): String {
        entries.clear()
        errors.clear()
        totalBytes = 0
        walk(root, "", 0)
        val lower = entries.map { it.path.lowercase(Locale.ROOT) }
        val files = entries.filter { !it.directory }
        val dlls = files.filter { it.name.endsWith(".dll", true) }
        val hasMonoFolder = lower.any { it.contains("monobleedingedge") }
        val hasManaged = lower.any { it.endsWith("/managed") || it.contains("/managed/") }
        val hasGameAssembly = lower.any { it.endsWith("/gameassembly.dll") }
        val hasMetadata = lower.any { it.endsWith("/global-metadata.dat") }
        val hasIl2CppData = lower.any { it.contains("/il2cpp_data/") }
        val hasUnityPlayer = lower.any { it.endsWith("/unityplayer.dll") }
        val gameDlls = dlls.filter { it.name.equals("Assembly-CSharp.dll", true) || it.name.equals("Assembly-CSharp-firstpass.dll", true) }
        val unityDlls = dlls.filter { it.name.startsWith("UnityEngine", true) }
        val il2cpp = hasGameAssembly && hasMetadata && hasIl2CppData
        val mono = hasMonoFolder || hasManaged || gameDlls.isNotEmpty()
        val backend = when {
            il2cpp -> "IL2CPP — сильные структурные признаки"
            mono && !hasGameAssembly -> "Mono — сильные структурные признаки"
            mono -> "Смешанные признаки — требуется ручная проверка"
            else -> "Не определён"
        }
        val unityVersion = findUnityVersion(root)
        val peInfo = files.filter { it.name.endsWith(".exe", true) || it.name.endsWith(".dll", true) }
            .mapNotNull { f -> readPeMachine(findFile(root, f.path))?.let { "${f.path}: $it" } }

        return buildString {
            appendLine("# Unity Game Folder Analysis")
            appendLine()
            appendLine("- Root: ${root.name ?: "(unnamed)"}")
            appendLine("- Generated: ${java.time.Instant.now()}")
            appendLine("- Backend assessment: **$backend**")
            appendLine("- Unity version string: **${unityVersion ?: "не найдена автоматически"}**")
            appendLine("- UnityPlayer.dll: ${if (hasUnityPlayer) "найден" else "не найден"}")
            appendLine("- GameAssembly.dll: ${if (hasGameAssembly) "найден" else "не найден"}")
            appendLine("- global-metadata.dat: ${if (hasMetadata) "найден" else "не найден"}")
            appendLine("- MonoBleedingEdge: ${if (hasMonoFolder) "найден" else "не найден"}")
            appendLine("- Managed: ${if (hasManaged) "найдена" else "не найдена"}")
            appendLine("- Entries: ${entries.size}")
            appendLine("- Total file size: ${formatBytes(totalBytes)}")
            appendLine()
            appendLine("## Game assemblies")
            if (gameDlls.isEmpty()) appendLine("- Не найдены")
            gameDlls.forEach { appendLine("- ${it.path} — ${formatBytes(it.size)}") }
            appendLine()
            appendLine("## UnityEngine modules")
            if (unityDlls.isEmpty()) appendLine("- Не найдены")
            unityDlls.forEach { appendLine("- ${it.path} — ${formatBytes(it.size)}") }
            appendLine()
            appendLine("## PE architecture")
            if (peInfo.isEmpty()) appendLine("- Не удалось прочитать PE machine")
            peInfo.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Directory tree")
            entries.forEach { e ->
                val indent = "  ".repeat(e.path.count { it == '/' })
                appendLine("$indent- ${if (e.directory) "[DIR]" else "[FILE]"} ${e.name}${if (e.directory) "/" else " — ${formatBytes(e.size)}"}")
            }
            appendLine()
            appendLine("## Warnings")
            if (errors.isEmpty()) appendLine("- Нет")
            errors.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Notes")
            appendLine("- Mono определяется по совокупности MonoBleedingEdge, Managed и Assembly-CSharp.")
            appendLine("- IL2CPP определяется по GameAssembly.dll, il2cpp_data и global-metadata.dat.")
            appendLine("- Версия Unity ищется как строка в globalgamemanagers; отсутствие строки не доказывает отсутствие версии.")
            appendLine("- PE machine описывает нативный EXE/DLL, а не CIL-сборки.")
            appendLine("- Исходные файлы не копируются, не изменяются и не исполняются.")
        }
    }

    private fun walk(dir: DocumentFile, parent: String, depth: Int) {
        if (depth > maxDepth || entries.size >= maxEntries) {
            errors.add("Достигнут лимит глубины или количества записей.")
            return
        }
        val children = try { dir.listFiles() } catch (e: Exception) {
            errors.add("Не удалось прочитать каталог $parent: ${e.message}")
            return
        }
        for (child in children) {
            if (entries.size >= maxEntries) {
                errors.add("Достигнут лимит $maxEntries записей.")
                break
            }
            val name = child.name ?: "(unnamed)"
            val path = if (parent.isEmpty()) name else "$parent/$name"
            val isDir = child.isDirectory
            val size = if (isDir) 0L else child.length().coerceAtLeast(0)
            entries.add(Entry(path, name, isDir, size))
            if (!isDir) totalBytes += size
            if (isDir) walk(child, path, depth + 1)
        }
    }

    private fun findFile(root: DocumentFile, relative: String): DocumentFile? {
        var current = root
        for (part in relative.split('/')) current = current.findFile(part) ?: return null
        return current
    }

    private fun findUnityVersion(root: DocumentFile): String? {
        val item = entries.firstOrNull { !it.directory && it.name.equals("globalgamemanagers", true) } ?: return null
        val file = findFile(root, item.path) ?: return null
        return try {
            val bytes = activity.contentResolver.openInputStream(file.uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (total < 8 * 1024 * 1024) {
                    val n = input.read(buffer, 0, minOf(buffer.size, 8 * 1024 * 1024 - total))
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    total += n
                }
                out.toByteArray()
            } ?: return null
            val matcher = Pattern.compile("20\\d{2}\\.\\d+\\.\\d+[abfp]\\d+").matcher(String(bytes, Charsets.ISO_8859_1))
            if (matcher.find()) matcher.group() else null
        } catch (e: Exception) {
            errors.add("Не удалось прочитать globalgamemanagers: ${e.message}")
            null
        }
    }

    private fun readPeMachine(file: DocumentFile?): String? {
        if (file == null) return null
        return try {
            val bytes = activity.contentResolver.openInputStream(file.uri)?.use { input ->\n                val buffer = ByteArray(4096)\n                val count = input.read(buffer)\n                if (count <= 0) ByteArray(0) else buffer.copyOf(count)\n            } ?: return null
            if (bytes.size < 64 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) return null
            val offset = intLE(bytes, 0x3c)
            if (offset < 0 || offset + 6 > bytes.size) return null
            if (bytes[offset] != 'P'.code.toByte() || bytes[offset + 1] != 'E'.code.toByte()) return null
            val machine = (bytes[offset + 4].toInt() and 255) or ((bytes[offset + 5].toInt() and 255) shl 8)
            when (machine) {
                0x014c -> "x86"
                0x8664 -> "x86-64"
                0xaa64 -> "ARM64"
                else -> "unknown (0x${machine.toString(16)})"
            }
        } catch (_: Exception) { null }
    }

    private fun intLE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or
        ((b[o+2].toInt() and 255) shl 16) or ((b[o+3].toInt() and 255) shl 24)

    private fun formatBytes(v: Long): String = when {
        v >= 1024L*1024*1024 -> String.format(Locale.US, "%.2f GiB", v / (1024.0*1024*1024))
        v >= 1024L*1024 -> String.format(Locale.US, "%.2f MiB", v / (1024.0*1024))
        v >= 1024L -> String.format(Locale.US, "%.2f KiB", v / 1024.0)
        else -> "$v B"
    }
}
