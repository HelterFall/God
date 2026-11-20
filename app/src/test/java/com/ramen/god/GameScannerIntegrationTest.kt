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
        assertEquals(24, report.getInt("schemaVersion"))
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

        val nativeImports = report.getJSONArray("nativeImports")
        val importedDllNames = (0 until nativeImports.length())
            .map { nativeImports.getJSONObject(it).getString("dll") }
            .toSet()
        assertEquals(setOf("kernel32.dll", "user32.dll"), importedDllNames)
        assertTrue((0 until nativeImports.length()).all {
            nativeImports.getJSONObject(it).getInt("referencedByCount") == 1
        })
        assertTrue(result.markdown.contains("## Static native DLL imports"))
        assertTrue(result.markdown.contains("kernel32.dll"))
        assertTrue(result.markdown.contains("user32.dll"))

        val importedSymbols = report.getJSONArray("nativeImportSymbols")
        val symbolDescriptions = (0 until importedSymbols.length()).map { index ->
            val symbol = importedSymbols.getJSONObject(index)
            val name = symbol.optString("name").takeIf { it.isNotBlank() }
                ?: "ordinal #${symbol.getInt("ordinal")}"
            "${symbol.getString("dll").lowercase()}!$name"
        }.toSet()
        assertEquals(
            setOf("kernel32.dll!CreateFileW", "kernel32.dll!ordinal #7", "user32.dll!MessageBoxW"),
            symbolDescriptions
        )
        val hints = (0 until importedSymbols.length()).mapNotNull { index ->
            importedSymbols.getJSONObject(index).takeIf { !it.isNull("hint") }?.getInt("hint")
        }.toSet()
        assertEquals(setOf(5, 1), hints)
        assertTrue(result.markdown.contains("CreateFileW"))
        assertTrue(result.markdown.contains("ordinal #7"))
        assertTrue(result.markdown.contains("MessageBoxW"))
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
    val bytes = ByteArray(1024)
    val pe = 0x80
    val optional = pe + 24
    val optionalSize = 240
    val section = optional + optionalSize

    bytes[0] = 0x4d
    bytes[1] = 0x5a
    put32(bytes, 0x3c, pe)
    bytes[pe] = 0x50
    bytes[pe + 1] = 0x45
    put16(bytes, pe + 4, 0x8664) // IMAGE_FILE_MACHINE_AMD64
    put16(bytes, pe + 6, 1) // one section
    put16(bytes, pe + 20, optionalSize)
    put16(bytes, optional, 0x20b) // PE32+

    // IMAGE_DIRECTORY_ENTRY_IMPORT points to RVA 0x1000 in .rdata.
    put32(bytes, optional + 120, 0x1000)

    // Map RVA 0x1000..0x11ff to file offsets 0x200..0x3ff.
    put32(bytes, section + 8, 0x200) // VirtualSize
    put32(bytes, section + 12, 0x1000) // VirtualAddress
    put32(bytes, section + 16, 0x200) // SizeOfRawData
    put32(bytes, section + 20, 0x200) // PointerToRawData

    // Two IMAGE_IMPORT_DESCRIPTOR entries and an implicit zero terminator.
    put32(bytes, 0x20c, 0x1040) // KERNEL32.dll name RVA
    put32(bytes, 0x210, 0x1080) // FirstThunk
    put32(bytes, 0x220, 0x1050) // USER32.dll name RVA
    put32(bytes, 0x224, 0x1090) // FirstThunk

    // FirstThunk is used as the lookup-table fallback when OriginalFirstThunk is zero.
    put64(bytes, 0x280, 0x10a0L)
    put64(bytes, 0x288, Long.MIN_VALUE or 7L)
    put64(bytes, 0x290, 0L)
    put64(bytes, 0x2c0, 0x10d0L)
    put64(bytes, 0x2c8, 0L)

    put16(bytes, 0x2a0, 5)
    "CreateFileW".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x2a2)
    put16(bytes, 0x2d0, 1)
    "MessageBoxW".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x2d2)
    "KERNEL32.dll".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x240)
    "USER32.dll".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x250)
    return bytes
}

private fun put16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
}

private fun put32(bytes: ByteArray, offset: Int, value: Int) {
    for (i in 0 until 4) bytes[offset + i] = (value ushr (8 * i)).toByte()
}

private fun put64(bytes: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
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
