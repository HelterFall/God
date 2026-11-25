package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityMetadataTypeIndexTest {
    @Test
    fun rejectsNegativeAndOutOfRangeSerializedTypeIndices() {
        for (invalidTypeIndex in listOf(-1, 1)) {
            val result = requireNotNull(UnityMetadataParser.parse(
                serializedFileWithObjectTypeIndex(invalidTypeIndex),
                UnityFileHeader("SerializedFile", 22, 4096, 208, 256, "Little Endian", 48)
            ))

            assertEquals(1L, result.objectCount)
            assertTrue(result.objectSamples.isEmpty())
            assertTrue(result.note.orEmpty().contains("typeIndex"))
        }
    }

    private fun serializedFileWithObjectTypeIndex(typeIndex: Int): ByteArray {
        val bytes = ByteArray(160)
        val version = "2022.3.10f1".toByteArray()
        var p = 48
        version.copyInto(bytes, p); p += version.size
        bytes[p++] = 0
        put32(bytes, p, 13); p += 4
        bytes[p++] = 0 // TypeTree disabled
        put32(bytes, p, 1); p += 4 // one SerializedType
        put32(bytes, p, 1); p += 4 // class ID
        bytes[p++] = 0 // stripped
        put16(bytes, p, 0xffff); p += 2 // no script type index
        p += 16 // old type hash
        put32(bytes, p, 0); p += 4 // type dependencies
        put32(bytes, p, 1); p += 4 // one object
        put64(bytes, p, 7L); p += 8 // PathID
        put64(bytes, p, 0L); p += 8 // byte start
        put32(bytes, p, 24); p += 4 // byte size
        put32(bytes, p, typeIndex.toLong() and 0xffff_ffffL); p += 4
        put32(bytes, p, 0); p += 4 // MonoScript references
        put32(bytes, p, 0); p += 4 // external references
        put32(bytes, p, 0) // SerializeReference types
        return bytes
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
