package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeParserTest {
    @Test
    fun rejectsNonPeAndTruncatedInputs() {
        assertNull(PeParser.parse(ByteArray(128)))
        val mzOnly = ByteArray(128)
        mzOnly[0] = 0x4d
        mzOnly[1] = 0x5a
        assertNull(PeParser.parse(mzOnly))
    }

    @Test
    fun detectsX86NativePe() {
        val info = PeParser.parse(minimalPe(machine = 0x014c))
        requireNotNull(info)
        assertEquals("x86", info.machine)
        assertFalse(info.managed)
        assertTrue(info.imports.isEmpty())
    }

    @Test
    fun extractsDllNamesFromImportDirectoryMappedThroughSection() {
        val info = PeParser.parse(peWithImports())
        requireNotNull(info)

        assertEquals("x86-64", info.machine)
        assertFalse(info.managed)
        assertEquals(listOf("KERNEL32.dll", "USER32.dll"), info.imports)
        assertEquals(
            listOf(
                PeImportedSymbol("KERNEL32.dll", name = "CreateFileW", hint = 5),
                PeImportedSymbol("KERNEL32.dll", ordinal = 7),
                PeImportedSymbol("USER32.dll", name = "MessageBoxW", hint = 1)
            ),
            info.importedSymbols
        )
        assertNull(info.note)
    }

    @Test
    fun reads32BitNamedAndOrdinalImportsFromOriginalFirstThunk() {
        val info = PeParser.parse(pe32WithImports())
        requireNotNull(info)

        assertEquals("x86", info.machine)
        assertEquals(listOf("KERNEL32.dll"), info.imports)
        assertEquals(
            listOf(
                PeImportedSymbol("KERNEL32.dll", name = "GetTickCount", hint = 3),
                PeImportedSymbol("KERNEL32.dll", ordinal = 42)
            ),
            info.importedSymbols
        )
    }

    @Test
    fun reportsTruncatedThunkTableWithoutLosingOtherImports() {
        val bytes = peWithImports()
        // Put the first thunk at the end of the section: one entry fits,
        // but the next pointer falls outside the file's readable section.
        put32(bytes, 0x210, 0x11f8)
        put64(bytes, 0x3f8, 0x10a0L)

        val info = PeParser.parse(bytes)
        requireNotNull(info)

        assertTrue(info.note.orEmpty().contains("thunk table for KERNEL32.dll is truncated"))
        assertEquals(
            listOf(
                PeImportedSymbol("KERNEL32.dll", name = "CreateFileW", hint = 5),
                PeImportedSymbol("USER32.dll", name = "MessageBoxW", hint = 1)
            ),
            info.importedSymbols
        )
    }

    @Test
    fun detectsClrDirectory() {
        val info = PeParser.parse(minimalPe(machine = 0x8664, clrRva = 0x2000))
        requireNotNull(info)
        assertEquals("x86-64", info.machine)
        assertTrue(info.managed)
    }

    private fun peWithImports(): ByteArray {
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
        put16(bytes, optional, 0x20b) // PE32+ optional header

        // IMAGE_DIRECTORY_ENTRY_IMPORT: RVA 0x1000, mapped by .rdata.
        put32(bytes, optional + 120, 0x1000)

        // One section: RVA 0x1000..0x11ff maps to file offset 0x200..0x3ff.
        put32(bytes, section + 8, 0x200) // VirtualSize
        put32(bytes, section + 12, 0x1000) // VirtualAddress
        put32(bytes, section + 16, 0x200) // SizeOfRawData
        put32(bytes, section + 20, 0x200) // PointerToRawData

        // Two IMAGE_IMPORT_DESCRIPTOR records followed by the zero terminator.
        put32(bytes, 0x20c, 0x1040) // KERNEL32.dll name RVA
        put32(bytes, 0x210, 0x1080) // FirstThunk
        put32(bytes, 0x220, 0x1050) // USER32.dll name RVA
        put32(bytes, 0x224, 0x10c0) // FirstThunk

        // OriginalFirstThunk is zero, so the parser must use FirstThunk.
        put64(bytes, 0x280, 0x10a0L)
        put64(bytes, 0x288, Long.MIN_VALUE or 7L) // import by ordinal 7
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

    private fun pe32WithImports(): ByteArray {
        val bytes = ByteArray(1024)
        val pe = 0x80
        val optional = pe + 24
        val optionalSize = 224
        val section = optional + optionalSize
        val directory = optional + 96

        bytes[0] = 0x4d
        bytes[1] = 0x5a
        put32(bytes, 0x3c, pe)
        bytes[pe] = 0x50
        bytes[pe + 1] = 0x45
        put16(bytes, pe + 4, 0x014c) // IMAGE_FILE_MACHINE_I386
        put16(bytes, pe + 6, 1)
        put16(bytes, pe + 20, optionalSize)
        put16(bytes, optional, 0x10b) // PE32
        put32(bytes, directory + 8, 0x1000)

        put32(bytes, section + 8, 0x200)
        put32(bytes, section + 12, 0x1000)
        put32(bytes, section + 16, 0x200)
        put32(bytes, section + 20, 0x200)

        // OriginalFirstThunk is non-zero and must take precedence over FirstThunk.
        put32(bytes, 0x200, 0x1020)
        put32(bytes, 0x20c, 0x1040)
        put32(bytes, 0x210, 0x1060)
        put32(bytes, 0x220, 0x10a0)
        put32(bytes, 0x224, 0x8000002aL.toLong().toInt())

        put16(bytes, 0x2a0, 3)
        "GetTickCount".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x2a2)
        "KERNEL32.dll".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x240)
        return bytes
    }

    private fun minimalPe(machine: Int, clrRva: Int = 0): ByteArray {
        val bytes = ByteArray(512)
        bytes[0] = 0x4d
        bytes[1] = 0x5a
        put32(bytes, 0x3c, 0x80)
        bytes[0x80] = 0x50
        bytes[0x81] = 0x45
        put16(bytes, 0x84, machine)
        put16(bytes, 0x86, 0)
        put16(bytes, 0x94, 224)
        put16(bytes, 0x98, 0x10b)
        put32(bytes, 0x98 + 208, clrRva)
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
}
