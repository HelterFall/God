package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityMetadataParserTest {
    @Test
    fun readsLeadingMetadataFieldsLittleEndian() {
        val header = UnityFileHeader("SerializedFile", 22, 4096, 128, 256, "Little Endian", 48)
        val bytes = ByteArray(128)
        val start = 48
        val version = "2022.3.10f1".toByteArray()
        version.copyInto(bytes, start)
        bytes[start + version.size] = 0
        put32(bytes, start + version.size + 1,  StandInPlatform, true)
        bytes[start + version.size + 5] = 1
        put32(bytes, start + version.size + 6, 17, true)

        val result = UnityMetadataParser.parse(bytes, header)
        requireNotNull(result)
        assertEquals("2022.3.10f1", result.unityVersion)
        assertEquals(StandalonePlatform, result.targetPlatform)
        assertTrue(result.typeTreeEnabled == true)
        assertEquals(17L, result.typeCount)
    }

    @Test
    fun readsBigEndianMetadata() {
        val header = UnityFileHeader("SerializedFile", 19, 4096, 128, 256, "Big Endian", 20)
        val bytes = ByteArray(96)
        val start = 20
        val version = "2019.4.1f1".toByteArray()
        version.copyInto(bytes, start)
        bytes[start + version.size] = 0
        put32(bytes, start + version.size + 1, 5, false)
        bytes[start + version.size + 5] = 0
        put32(bytes, start + version.size + 6, 3, false)

        val result = UnityMetadataParser.parse(bytes, header)
        requireNotNull(result)
        assertEquals(5L, result.targetPlatform)
        assertFalse(result.typeTreeEnabled == true)
        assertEquals(3L, result.typeCount)
    }

    @Test
    fun refusesUnknownFutureMetadataVersions() {
        val header = UnityFileHeader("SerializedFile", 27, 4096, 128, 256, "Little Endian", 48)
        val result = UnityMetadataParser.parse(ByteArray(128), header)
        requireNotNull(result)
        assertTrue(result.note?.contains("19–26") == true)
    }

    @Test
    fun rejectsTruncatedAndImplausibleCounts() {
        val header = UnityFileHeader("SerializedFile", 22, 4096, 128, 256, "Little Endian", 48)
        assertNull(UnityMetadataParser.parse(ByteArray(60), header))
        val bytes = ByteArray(128)
        val version = "2022.3.10f1".toByteArray()
        version.copyInto(bytes, 48)
        val p = 48 + version.size
        bytes[p] = 0
        put32(bytes, p + 1, 5, true)
        bytes[p + 5] = 1
        put32(bytes, p + 6, 0xffffffffL, true)
        assertNull(UnityMetadataParser.parse(bytes, header))
    }


    @Test
    fun readsObjectScriptAndExternalReferenceCountsWhenTypeTreeIsDisabled() {
        val header = UnityFileHeader("SerializedFile", 22, 4096, 128, 256, "Little Endian", 48)
        val bytes = ByteArray(160)
        val start = 48
        val version = "2022.3.10f1".toByteArray()
        version.copyInto(bytes, start)
        var p = start + version.size
        bytes[p++] = 0
        put32(bytes, p, 13, true); p += 4
        bytes[p++] = 0 // TypeTree disabled
        put32(bytes, p, 1, true); p += 4 // type count
        put32(bytes, p, 1, true); p += 4 // class ID
        bytes[p++] = 0 // stripped
        put16(bytes, p, 2, true); p += 2 // non-negative script index does not imply an extra script ID
        p += 16 // old type hash
        put32(bytes, p, 1, true); p += 4 // object count
        put64(bytes, p, 7L, true); p += 8 // file ID
        put64(bytes, p, 0L, true); p += 8 // byte start
        put32(bytes, p, 24, true); p += 4 // byte size
        put32(bytes, p, 0, true); p += 4 // type index
        put32(bytes, p, 0, true); p += 4 // script type count
        put32(bytes, p, 0, true); p += 4 // external references count
        put32(bytes, p, 0, true) // SerializeReference type count

        val result = UnityMetadataParser.parse(bytes, header)
        requireNotNull(result)
        assertEquals(1L, result.typeCount)
        assertEquals(1L, result.objectCount)
        assertEquals(0L, result.scriptTypeCount)
        assertEquals(0L, result.externalReferenceCount)
        assertEquals(1, result.objectSamples.size)
        assertEquals(7L, result.objectSamples[0].pathId)
        assertEquals(24L, result.objectSamples[0].byteSize)
        assertEquals(1, result.objectSamples[0].classId)
    }

    @Test
    fun extractsLegacyTypeTreeRootAndEmbeddedFieldNames() {
        val header = UnityFileHeader("SerializedFile", 22, 4096, 256, 512, "Little Endian", 48)
        val bytes = ByteArray(256)
        val version = "2022.3.10f1".toByteArray()
        version.copyInto(bytes, 48)
        var p = 48 + version.size
        bytes[p++] = 0
        put32(bytes, p, 13, true); p += 4
        bytes[p++] = 1
        put32(bytes, p, 1, true); p += 4 // type count
        put32(bytes, p, 1, true); p += 4 // class ID
        bytes[p++] = 0
        bytes[p++] = 0xff.toByte(); bytes[p++] = 0xff.toByte()
        p += 16 // old type hash
        put32(bytes, p, 1, true); p += 4 // TypeTree node count
        val strings = ("GameObject" + 0.toChar() + "Base" + 0.toChar()).toByteArray()
        put32(bytes, p, strings.size.toLong(), true); p += 4
        put16(bytes, p, 1, true); p += 2 // node version
        bytes[p++] = 0; bytes[p++] = 0 // level and flags
        put32(bytes, p, 0, true); p += 4 // type string offset
        put32(bytes, p, 11, true); p += 4 // name string offset
        put32(bytes, p, 0xffffffffL, true); p += 4
        put32(bytes, p, 0, true); p += 4
        put32(bytes, p, 0, true); p += 4
        p += 8 // reference type hash
        strings.copyInto(bytes, p); p += strings.size
        put32(bytes, p, 0, true); p += 4 // type dependencies
        put32(bytes, p, 0, true); p += 4 // object count
        put32(bytes, p, 0, true); p += 4 // script count
        put32(bytes, p, 0, true); p += 4 // external references
        put32(bytes, p, 0, true) // SerializeReference type count

        val result = UnityMetadataParser.parse(bytes, header)
        requireNotNull(result)
        assertEquals(1, result.typeTrees.size)
        assertEquals("GameObject", result.typeTrees[0].rootType)
        assertEquals("Base", result.typeTrees[0].rootName)
    }

    @Test
    fun extractsModernTypeTreeInfoBlob() {
        val header = UnityFileHeader("SerializedFile", 23, 4096, 256, 512, "Little Endian", 48)
        val bytes = ByteArray(320)
        val version = "2023.1.0f1".toByteArray()
        version.copyInto(bytes, 48)
        var p = 48 + version.size
        bytes[p++] = 0
        put32(bytes, p, 13, true); p += 4
        bytes[p++] = 1
        put32(bytes, p, 1, true); p += 4
        put32(bytes, p, 1, true); p += 4
        bytes[p++] = 0
        bytes[p++] = 0xff.toByte(); bytes[p++] = 0xff.toByte()
        p += 16 // old type hash
        p += 16 // TypeTree content hash
        val strings = ("MonoBehaviour" + 0.toChar() + "Base" + 0.toChar()).toByteArray()
        val blob = ByteArray(4 + 4 + 4 + 4 + 32 + strings.size + 4)
        "mhtt".toByteArray().copyInto(blob, 0)
        put32(blob, 4, 1, true) // TypeTree blob version
        put32(blob, 8, 1, true) // node count
        put32(blob, 12, strings.size.toLong(), true)
        var n = 16
        put16(blob, n, 1, true); n += 2
        blob[n++] = 0; blob[n++] = 0
        put32(blob, n, 0, true); n += 4
        put32(blob, n, 14, true); n += 4
        put32(blob, n, 0xffffffffL, true); n += 4
        put32(blob, n, 0, true); n += 4
        put32(blob, n, 0, true); n += 4
        n += 8
        strings.copyInto(blob, n); n += strings.size
        put32(blob, n, 0, true) // ref hash count
        put32(bytes, p, blob.size.toLong(), true); p += 4
        blob.copyInto(bytes, p); p += blob.size
        put32(bytes, p, 0, true); p += 4 // dependencies
        put32(bytes, p, 0, true); p += 4 // object count
        put32(bytes, p, 0, true); p += 4 // script count
        put32(bytes, p, 0, true); p += 4 // external references
        put32(bytes, p, 0, true) // SerializeReference type count

        val result = UnityMetadataParser.parse(bytes, header)
        requireNotNull(result)
        assertEquals("MonoBehaviour", result.typeTrees.single().rootType)
        assertEquals("Base", result.typeTrees.single().rootName)
    }

    @Test
    fun readsMonoScriptReferenceAndExternalFilePath() {
        val header = UnityFileHeader("SerializedFile", 22, 4096, 128, 256, "Little Endian", 48)
        val bytes = ByteArray(256)
        val version = "2022.3.10f1".toByteArray()
        version.copyInto(bytes, 48)
        var p = 48 + version.size
        bytes[p++] = 0
        put32(bytes, p, 13, true); p += 4
        bytes[p++] = 0
        put32(bytes, p, 0, true); p += 4 // types
        put32(bytes, p, 0, true); p += 4 // objects
        put32(bytes, p, 1, true); p += 4 // script references
        put32(bytes, p, 1, true); p += 4 // fileID = first external file
        p = (p + 3) and -4
        put64(bytes, p, 77L, true); p += 8 // pathID
        put32(bytes, p, 1, true); p += 4 // external references
        bytes[p++] = 0 // empty temporary field
        p += 16 // GUID
        put32(bytes, p, 0, true); p += 4 // reference type
        val externalPath = "sharedassets0.assets".toByteArray()
        externalPath.copyInto(bytes, p); p += externalPath.size
        bytes[p++] = 0
        put32(bytes, p, 0, true) // SerializeReference types

        val result = requireNotNull(UnityMetadataParser.parse(bytes, header))
        assertEquals(1L, result.scriptTypeCount)
        assertEquals(UnityScriptTypeReference(1, 77L), result.scriptTypeReferences.single())
        assertEquals(UnityExternalReference(1, "sharedassets0.assets", 0), result.externalReferences.single())
    }

    @Test
    fun rejectsObjectDataOutsideSerializedFileDataSection() {
        val header = UnityFileHeader("SerializedFile", 22, 4096, 128, 256, "Little Endian", 48)
        val bytes = ByteArray(192)
        val version = "2022.3.10f1".toByteArray()
        version.copyInto(bytes, 48)
        var p = 48 + version.size
        bytes[p++] = 0
        put32(bytes, p, 13, true); p += 4
        bytes[p++] = 0
        put32(bytes, p, 0, true); p += 4 // types
        put32(bytes, p, 1, true); p += 4 // objects
        p = (p + 3) and -4
        put64(bytes, p, 1L, true); p += 8
        put64(bytes, p, 4000L, true); p += 8 // outside fileSize - dataOffset
        put32(bytes, p, 32, true); p += 4
        put32(bytes, p, 0, true); p += 4

        val result = requireNotNull(UnityMetadataParser.parse(bytes, header))
        assertTrue(result.note?.contains("за пределы data section") == true)
        assertTrue(result.objectSamples.isEmpty())
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int, little: Boolean) {
        bytes[offset + if (little) 0 else 1] = value.toByte()
        bytes[offset + if (little) 1 else 0] = (value ushr 8).toByte()
    }

    private fun put64(bytes: ByteArray, offset: Int, value: Long, little: Boolean) {
        for (i in 0 until 8) {
            val shift = if (little) i * 8 else (7 - i) * 8
            bytes[offset + i] = (value ushr shift).toByte()
        }
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long, little: Boolean) {
        for (i in 0 until 4) {
            val shift = if (little) i * 8 else (3 - i) * 8
            bytes[offset + i] = (value ushr shift).toByte()
        }
    }

    companion object {
        private const val StandInPlatform = 13L
        private const val StandalonePlatform = 13L
    }
}
