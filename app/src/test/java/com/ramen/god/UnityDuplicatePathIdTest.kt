package com.ramen.god

import org.junit.Assert.*
import org.junit.Test

class UnityDuplicatePathIdTest {
    @Test fun duplicatePathIdsAreExcludedFromObjectSamples() {
        val b = ByteArray(160)
        val version = "2022.3.10f1".toByteArray()
        var p = 48
        version.copyInto(b, p); p += version.size
        b[p++] = 0
        put32(b, p, 13); p += 4
        b[p++] = 0
        put32(b, p, 1); p += 4
        put32(b, p, 1); p += 4
        b[p++] = 0
        put16(b, p, 0xffff); p += 2
        p += 16
        put32(b, p, 0); p += 4
        put32(b, p, 2); p += 4
        repeat(2) {
            put64(b, p, 7); p += 8
            put64(b, p, 0); p += 8
            put32(b, p, 24); p += 4
            put32(b, p, 0); p += 4
        }
        put32(b, p, 0); p += 4
        put32(b, p, 0); p += 4
        put32(b, p, 0)

        val result = requireNotNull(UnityMetadataParser.parse(
            b, UnityFileHeader("SerializedFile", 22, 4096, 208, 256, "Little Endian", 48)
        ))
        assertEquals(2L, result.objectCount)
        assertTrue(result.objectSamples.isEmpty())
        assertTrue(result.note.orEmpty().contains("повторяющийся PathID"))
    }

    private fun put16(b: ByteArray, p: Int, v: Int) {
        b[p] = v.toByte(); b[p + 1] = (v ushr 8).toByte()
    }
    private fun put32(b: ByteArray, p: Int, v: Long) {
        repeat(4) { b[p + it] = (v ushr (8 * it)).toByte() }
    }
    private fun put64(b: ByteArray, p: Int, v: Long) {
        repeat(8) { b[p + it] = (v ushr (8 * it)).toByte() }
    }
}
