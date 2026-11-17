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
    fun detectsClrDirectory() {
        val info = PeParser.parse(minimalPe(machine = 0x8664, clrRva = 0x2000))
        requireNotNull(info)
        assertEquals("x86-64", info.machine)
        assertTrue(info.managed)
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
