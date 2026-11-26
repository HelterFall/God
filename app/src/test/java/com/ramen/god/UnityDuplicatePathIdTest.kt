package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityDuplicatePathIdTest {
    @Test
    fun catchesDuplicatePathIdAfterObjectSampleLimit() {
        val version = "2022.3.10f1".toByteArray()
        val objectCount = 501
        val bytes = ByteArray(
            48 + version.size + 1 + 4 + 1 + 4 + 27 + 4 +
                objectCount * 24 + 4 + 4 + 4
        )
        var p = 48
        version.copyInto(bytes, p); p += version.size
        bytes[p++] = 0
        put32(bytes, p, 13); p += 4
        bytes[p++] = 0
        put32(bytes, p, 1); p += 4
        put32(bytes, p, 1); p += 4
        bytes[p++] = 0
        put16(bytes, p, 0xffff); p += 2
        p += 16
        put32(bytes, p, 0); p += 4
        put32(bytes, p, objectCount.toLong()); p += 4
        repeat(objectCount) { index ->
            val pathId = if (index == objectCount - 1) 7L else index.toLong() + 7L
            put64(bytes, p, pathId); p += 8
            put64(bytes, p, 0); p += 8
            put32(bytes, p, 24); p += 4
            put32(bytes, p, 0); p += 4
        }
        put32(bytes, p, 0); p += 4
        put32(bytes, p, 0); p += 4
        put32(bytes, p, 0)

        val result = requireNotNull(UnityMetadataParser.parse(
            bytes,
            UnityFileHeader(
                "SerializedFile", 22, bytes.size.toLong() + 24,
                bytes.size.toLong() - 48, bytes.size.toLong(), "Little Endian", 48
            )
        ))

        assertEquals(objectCount.toLong(), result.objectCount)
        assertTrue(result.objectSamples.isEmpty())
        assertTrue(result.note.orEmpty().contains("повторяющийся PathID"))
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { i -> bytes[offset + i] = (value ushr (8 * i)).toByte() }
    }

    private fun put64(bytes: ByteArray, offset: Int, value: Long) {
        repeat(8) { i -> bytes[offset + i] = (value ushr (8 * i)).toByte() }
    }
}
