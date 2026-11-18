package com.ramen.god

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class ReportExporterTest {
    @Test
    fun failsWhenProviderReturnsNullStream() {
        val error = expectIOException {
            ReportExporter.write("report") { null }
        }
        assertTrue(error.message.orEmpty().contains("поток"))
    }

    @Test
    fun writesUtf8AndClosesStreamBeforeReturning() {
        val output = object : ByteArrayOutputStream() {
            var wasClosed = false
            override fun close() {
                wasClosed = true
                super.close()
            }
        }
        val content = "Отчёт: Unity — 東京"

        ReportExporter.write(content) { output }

        assertArrayEquals(content.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertTrue(output.wasClosed)
    }

    @Test
    fun propagatesProviderOpenFailure() {
        val error = expectIOException {
            ReportExporter.write("report") { throw IOException("provider unavailable") }
        }
        assertEquals("provider unavailable", error.message)
    }

    @Test
    fun propagatesWriteFailure() {
        val output = object : OutputStream() {
            override fun write(value: Int) {
                throw IOException("disk full")
            }
        }
        val error = expectIOException {
            ReportExporter.write("report") { output }
        }
        assertEquals("disk full", error.message)
    }

    @Test
    fun propagatesCloseFailure() {
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                throw IOException("close failed")
            }
        }
        val error = expectIOException {
            ReportExporter.write("report") { output }
        }
        assertEquals("close failed", error.message)
    }

    private fun expectIOException(block: () -> Unit): IOException {
        try {
            block()
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("Expected IOException")
    }
}
