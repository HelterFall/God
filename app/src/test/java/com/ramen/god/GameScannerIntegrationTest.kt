package com.ramen.god

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameScannerIntegrationTest {
    @Test
    fun scansFakeTreeThroughInventoryCandidateSelectionCoverageAndReports() {
        val root = MemoryScanNode(
            name = "FixtureGame",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    name = "Data",
                    isDirectory = true,
                    children = listOf(
                        MemoryScanNode("manifest.txt", false, "fixture".toByteArray())
                    )
                ),
                // Deliberately not a PE file: the scanner should report one
                // attempted and failed PE candidate rather than hide the failure.
                MemoryScanNode("Assembly-CSharp.dll", false, byteArrayOf(0, 1, 2, 3)),
                MemoryScanNode("UnityPlayer.dll", false, minimalNativePe()),
                MemoryScanNode("readme.txt", false, "hello".toByteArray())
            )
        )
        val progress = mutableListOf<Pair<Int, String>>()

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = { false }
        ) { count, path ->
            progress += count to path
        }

        assertFalse(result.isPartial)
        assertTrue(result.markdown.contains("# Unity Game Architecture Report"))
        assertTrue(result.markdown.contains("Indexed entries: 5"))
        assertEquals(listOf(1, 2, 3, 4, 5, 5), progress.map { it.first })
        assertEquals("readme.txt", progress[4].second)
        assertEquals("Инвентаризация завершена; анализирую PE-файлы", progress.last().second)

        val report = JSONObject(result.json)
        assertEquals(23, report.getInt("schemaVersion"))
        assertEquals(5, report.getInt("entryCount"))

        val files = report.getJSONArray("files")
        val paths = (0 until files.length()).map { files.getJSONObject(it).getString("path") }
        assertEquals(
            listOf("Data", "Data/manifest.txt", "Assembly-CSharp.dll", "UnityPlayer.dll", "readme.txt"),
            paths
        )

        val coverage = report.getJSONObject("scanCoverage")
        val inventory = coverage.getJSONObject("inventory")
        assertEquals("completed", inventory.getString("status"))
        assertEquals(5, inventory.getInt("entriesDiscovered"))
        assertEquals(4, inventory.getInt("filesDiscovered"))
        assertEquals(1, inventory.getInt("directoriesDiscovered"))
        assertEquals("partial", coverage.getString("status"))

        val analyzers = coverage.getJSONArray("analyzers")
        val pe = (0 until analyzers.length())
            .map { analyzers.getJSONObject(it) }
            .first { it.getString("id") == "pe" }
        assertEquals(2, pe.getInt("candidateCount"))
        assertEquals(2, pe.getInt("attemptedCount"))
        assertEquals(1, pe.getInt("parsedCount"))
        assertEquals(1, pe.getInt("failedCount"))
        assertEquals("partial", pe.getString("status"))
        val nativeBinaries = report.getJSONArray("nativePeBinaries")
        assertEquals("UnityPlayer.dll", nativeBinaries.getString(0))
    }

    @Test
    fun returnsPartialReportWithExactlyTheInventoryCollectedBeforeCancellation() {
        val root = MemoryScanNode(
            name = "FixtureGame",
            isDirectory = true,
            children = listOf(
                MemoryScanNode(
                    name = "Assets",
                    isDirectory = true,
                    children = listOf(MemoryScanNode("first.txt", false, byteArrayOf(1)))
                ),
                MemoryScanNode("later.txt", false, byteArrayOf(2))
            )
        )
        var cancellationChecks = 0

        val result = GameScanner(MemoryScanFileAccess()).scan(
            root,
            shouldCancel = {
                cancellationChecks++
                cancellationChecks >= 5
            },
            onProgress = { _, _ -> }
        )

        assertTrue(result.isPartial)
        assertTrue(result.markdown.contains("Assets/first.txt"))
        assertTrue(result.json.contains("\"scanStatus\":\"cancelled\""))
        assertTrue(result.json.contains("Assets/first.txt"))
        assertFalse(result.json.contains("later.txt"))
        assertTrue(result.json.contains("\"entryCount\":2"))
    }
}

private fun minimalNativePe(): ByteArray {
    val bytes = ByteArray(512)
    bytes[0] = 0x4d
    bytes[1] = 0x5a
    put32(bytes, 0x3c, 0x80)
    bytes[0x80] = 0x50
    bytes[0x81] = 0x45
    put16(bytes, 0x84, 0x8664)
    put16(bytes, 0x86, 0)
    put16(bytes, 0x94, 224)
    put16(bytes, 0x98, 0x10b)
    return bytes
}

private fun put16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
}

private fun put32(bytes: ByteArray, offset: Int, value: Int) {
    for (i in 0 until 4) bytes[offset + i] = (value ushr (8 * i)).toByte()
}

private class MemoryScanNode(
    override val name: String?,
    override val isDirectory: Boolean,
    val contents: ByteArray = byteArrayOf(),
    private val children: List<MemoryScanNode> = emptyList()
) : ScanNode {
    override val size: Long
        get() = if (isDirectory) 0L else contents.size.toLong()

    override fun listFiles(): List<ScanNode> = children
}

private class MemoryScanFileAccess : ScanFileAccess {
    override fun openInputStream(node: ScanNode): InputStream? {
        val file = node as? MemoryScanNode
            ?: throw IllegalArgumentException("Unknown in-memory scan node.")
        return if (file.isDirectory) null else ByteArrayInputStream(file.contents)
    }
}
