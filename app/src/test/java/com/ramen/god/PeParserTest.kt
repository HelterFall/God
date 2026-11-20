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
        assertNull(info.note)
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
        put32(bytes, 0x224, 0x1090) // FirstThunk

        "KERNEL32.dll".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x240)
        "USER32.dll".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x250)
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
}
