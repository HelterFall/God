package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityRectTransformTypeTreeTest {
    @Test
    fun retainsCompleteRectTransformTypeTreeForObjectDecoding() {
        val version = "2023.1.0f1".toByteArray()
        val bytes = ByteArray(320)
        var p = 48
        version.copyInto(bytes, p); p += version.size
        bytes[p++] = 0
        put32(bytes, p, 13); p += 4
        bytes[p++] = 1 // TypeTree enabled
        put32(bytes, p, 1); p += 4
        put32(bytes, p, 224); p += 4 // RectTransform
        bytes[p++] = 0
        put16(bytes, p, 0xffff); p += 2
        p += 16 // old type hash
        p += 16 // TypeTree content hash

        val blob = ByteArray(52)
        "mhtt".toByteArray().copyInto(blob, 0)
        put32(blob, 4, 1) // TypeTree blob version
        put32(blob, 8, 1) // one node
        put32(blob, 12, 0) // no local string buffer
        var n = 16
        put16(blob, n, 1); n += 2
        blob[n++] = 0
        blob[n++] = 0
        put32(blob, n, 0x80000491L); n += 4 // known CommonString
        put32(blob, n, 0x800001ABL); n += 4 // m_Name
        put32(blob, n, 0xffffffffL); n += 4
        put32(blob, n, 0); n += 4
        put32(blob, n, 0); n += 4
        n += 8
        put32(blob, n, 0) // no reference hashes

        put32(bytes, p, blob.size.toLong()); p += 4
        blob.copyInto(bytes, p); p += blob.size
        put32(bytes, p, 0); p += 4 // type dependencies
        put32(bytes, p, 0); p += 4 // objects
        put32(bytes, p, 0); p += 4 // script references
        put32(bytes, p, 0); p += 4 // external references
        put32(bytes, p, 0) // SerializeReference types

        val result = requireNotNull(UnityMetadataParser.parse(
            bytes,
            UnityFileHeader("SerializedFile", 23, 4096, 256, 512, "Little Endian", 48)
        ))
        val rectTransformTree = result.typeTrees.single()

        assertEquals(224, rectTransformTree.classId)
        assertTrue(rectTransformTree.completeForDecoding)
        assertTrue(rectTransformTree.decoderNodes.isNotEmpty())
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { i -> bytes[offset + i] = (value ushr (8 * i)).toByte() }
    }
}
