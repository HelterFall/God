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
import java.util.concurrent.Executors

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
                    ReportExporter.write(content) { contentResolver.openOutputStream(uri) }
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
