package com.ramen.god

import java.io.IOException
import java.io.OutputStream

/**
 * Writes an exported report and treats every incomplete provider operation as a failure.
 *
 * The stream is always closed before this method returns, so callers may display success
 * only after the write and close operations both complete.
 */
internal object ReportExporter {
    fun write(content: String, openOutputStream: () -> OutputStream?) {
        val outputStream = openOutputStream()
            ?: throw IOException("Не удалось открыть поток для сохранения отчёта.")

        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(content)
        }
    }
}
