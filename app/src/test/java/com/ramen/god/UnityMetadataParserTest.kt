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
        put16(bytes, p, 2, true); p += 2 // script type index
        p += 16 // script ID is present when scriptTypeIndex >= 0
        p += 16 // old type hash
        put32(bytes, p, 0, true); p += 4 // type dependencies (v21+)
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
        put32(bytes, p, 2, true); p += 4 // TypeTree node count
        put32(bytes, p, 0, true); p += 4 // no local strings; use Unity CommonString offsets
        // Root node: GameObject Base
        put16(bytes, p, 1, true); p += 2
        bytes[p++] = 0; bytes[p++] = 0 // level and flags
        put32(bytes, p, 0x800000ACL, true); p += 4 // common string: GameObject (172)
        put32(bytes, p, 0x80000037L, true); p += 4 // common string: Base (55)
        put32(bytes, p, 0xffffffffL, true); p += 4
        put32(bytes, p, 0, true); p += 4
        put32(bytes, p, 0, true); p += 4
        p += 8 // reference type hash
        // Child node: int m_Name
        put16(bytes, p, 1, true); p += 2
        bytes[p++] = 1; bytes[p++] = 0 // level and flags
        put32(bytes, p, 0x800000DEL, true); p += 4 // common string: int (222)
        put32(bytes, p, 0x800001ABL, true); p += 4 // common string: m_Name (427)
        put32(bytes, p, 4, true); p += 4 // byte size
        put32(bytes, p, 0, true); p += 4
        put32(bytes, p, 0, true); p += 4
        p += 8 // reference type hash
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
        assertEquals(listOf("m_Name"), result.typeTrees[0].fieldNames)
        assertEquals(2L, result.typeTrees[0].nodeCount)
        assertEquals(2, result.typeTrees[0].nodes.size)
        assertEquals("int", result.typeTrees[0].nodes[1].typeName)
        assertEquals("m_Name", result.typeTrees[0].nodes[1].fieldName)
        assertEquals(1, result.typeTrees[0].nodes[1].level)
        assertEquals(4, result.typeTrees[0].nodes[1].byteSize)
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
        val strings = ByteArray(0) // built-in CommonString offsets
        val blob = ByteArray(4 + 4 + 4 + 4 + 32 + strings.size + 4)
        "mhtt".toByteArray().copyInto(blob, 0)
        put32(blob, 4, 1, true) // TypeTree blob version
        put32(blob, 8, 1, true) // node count
        put32(blob, 12, strings.size.toLong(), true)
        var n = 16
        put16(blob, n, 1, true); n += 2
        blob[n++] = 0; blob[n++] = 0
        put32(blob, n, 0x80000491L, true); n += 4 // Unity 2022+ RenderingLayerMask (1169)
        put32(blob, n, 0x800001ABL, true); n += 4 // m_Name (427)
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
        assertEquals("RenderingLayerMask", result.typeTrees.single().rootType)
        assertEquals("m_Name", result.typeTrees.single().rootName)
        assertEquals(1169, result.typeTrees.single().nodes.single().typeName?.let { 1169 })
        assertEquals("RenderingLayerMask", result.typeTrees.single().nodes.single().typeName)
    }

    @Test
    fun preservesMonoBehaviourScriptIdAndTypeIndexWithoutTypeTree() {
        val header = UnityFileHeader("SerializedFile", 22, 4096, 128, 256, "Little Endian", 48)
        val bytes = ByteArray(192)
        val version = "2022.3.10f1".toByteArray()
        version.copyInto(bytes, 48)
        var p = 48 + version.size
        bytes[p++] = 0
        put32(bytes, p, 13, true); p += 4
        bytes[p++] = 0 // TypeTree disabled
        put32(bytes, p, 1, true); p += 4 // types
        put32(bytes, p, 114, true); p += 4 // MonoBehaviour
        bytes[p++] = 0 // not stripped
        put16(bytes, p, 0, true); p += 2 // scriptTypeIndex
        val scriptId = ByteArray(16) { it.toByte() }
        scriptId.copyInto(bytes, p); p += scriptId.size
        p += 16 // old type hash
        put32(bytes, p, 0, true); p += 4 // type dependencies
        put32(bytes, p, 0, true); p += 4 // objects
        put32(bytes, p, 0, true); p += 4 // script references
        put32(bytes, p, 0, true); p += 4 // external references
        put32(bytes, p, 0, true) // SerializeReference types

        val result = requireNotNull(UnityMetadataParser.parse(bytes, header))
        assertEquals(114, result.serializedTypes.single().classId)
        assertEquals(0, result.serializedTypes.single().scriptTypeIndex)
        assertEquals("000102030405060708090a0b0c0d0e0f", result.serializedTypes.single().scriptId)
    }

    @Test
    fun readsSerializeReferenceTypeIdentityFields() {
        val header = UnityFileHeader("SerializedFile", 21, 4096, 128, 256, "Little Endian", 20)
        val bytes = ByteArray(256)
        val version = "2021.3.20f1".toByteArray()
        version.copyInto(bytes, 20)
        var p = 20 + version.size
        bytes[p++] = 0
        put32(bytes, p, 13, true); p += 4
        bytes[p++] = 0 // TypeTree disabled
        put32(bytes, p, 0, true); p += 4 // regular types
        put32(bytes, p, 0, true); p += 4 // objects
        put32(bytes, p, 0, true); p += 4 // script references
        put32(bytes, p, 0, true); p += 4 // external references
        put32(bytes, p, 1, true); p += 4 // SerializeReference types
        put32(bytes, p, 0xffffffffL, true); p += 4 // ClassID -1
        bytes[p++] = 0 // not stripped
        put16(bytes, p, 0xffff, true); p += 2 // scriptTypeIndex -1
        p += 16 // undefined persistent type still stores script ID
        p += 16 // old type hash
        val fields = listOf("PlayerData", "Game", "Assembly-CSharp")
        for (field in fields) {
            val encoded = field.toByteArray()
            encoded.copyInto(bytes, p); p += encoded.size
            bytes[p++] = 0
        }

        val result = requireNotNull(UnityMetadataParser.parse(bytes, header))
        assertEquals(1L, result.serializedReferenceTypeCount)
        assertEquals(-1, result.serializedReferenceTypes.single().classId)
        assertEquals("PlayerData", result.serializedReferenceTypes.single().className)
        assertEquals("Game", result.serializedReferenceTypes.single().namespace)
        assertEquals("Assembly-CSharp", result.serializedReferenceTypes.single().assemblyName)
    }

    @Test
    fun readsVersion26SharedSubtreeCount() {
        val header = UnityFileHeader("SerializedFile", 26, 4096, 128, 256, "Little Endian", 48)
        val bytes = ByteArray(192)
        val version = "6000.0.0f1".toByteArray()
        version.copyInto(bytes, 48)
        var p = 48 + version.size
        bytes[p++] = 0
        put32(bytes, p, 13, true); p += 4
        bytes[p++] = 0
        put32(bytes, p, 0, true); p += 4 // types
        put32(bytes, p, 0, true); p += 4 // objects
        put32(bytes, p, 0, true); p += 4 // script references
        put32(bytes, p, 0, true); p += 4 // external references
        put32(bytes, p, 0, true); p += 4 // SerializeReference types
        put32(bytes, p, 0, true) // shared subtree count

        val result = requireNotNull(UnityMetadataParser.parse(bytes, header))
        assertEquals(0L, result.serializedReferenceTypeCount)
        assertEquals(0L, result.sharedSubtreeCount)
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
        put32(bytes, p, 1, true); p += 4 // one serialized type
        put32(bytes, p, 114, true); p += 4 // MonoBehaviour
        bytes[p++] = 0
        put16(bytes, p, 0, true); p += 2 // scriptTypeIndex = 0
        p += 16 // MonoBehaviour script ID
        p += 16 // type hash
        put32(bytes, p, 0, true); p += 4 // type dependencies
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
        assertEquals("external-file-path-known", result.scriptTypeBindings.single().resolution)
        assertEquals("sharedassets0.assets", result.scriptTypeBindings.single().externalPath)
        assertEquals(77L, result.scriptTypeBindings.single().pathId)
    }

    @Test
    fun linksMonoBehaviourScriptTypeToLocalMonoScriptObjectSample() {
        val header = UnityFileHeader("SerializedFile", 22, 4096, 128, 256, "Little Endian", 48)
        val bytes = ByteArray(256)
        val version = "2022.3.10f1".toByteArray()
        version.copyInto(bytes, 48)
        var p = 48 + version.size
        bytes[p++] = 0
        put32(bytes, p, 13, true); p += 4
        bytes[p++] = 0 // TypeTree disabled
        put32(bytes, p, 2, true); p += 4 // type count

        // Type 0: MonoBehaviour with scriptTypeIndex 0 and an embedded script GUID.
        put32(bytes, p, 114, true); p += 4
        bytes[p++] = 0
        put16(bytes, p, 0, true); p += 2
        p += 16 // script ID
        p += 16 // type hash
        put32(bytes, p, 0, true); p += 4 // dependencies

        // Type 1: MonoScript (ClassID 115), no script type.
        put32(bytes, p, 115, true); p += 4
        bytes[p++] = 0
        put16(bytes, p, 0xffff, true); p += 2
        p += 16 // type hash
        put32(bytes, p, 0, true); p += 4 // dependencies

        put32(bytes, p, 2, true); p += 4 // object count
        p = (p + 3) and -4
        put64(bytes, p, 777L, true); p += 8 // local MonoScript pathID
        put64(bytes, p, 0L, true); p += 8 // byte start
        put32(bytes, p, 16, true); p += 4 // byte size
        put32(bytes, p, 1, true); p += 4 // type index -> class 115
        put64(bytes, p, 778L, true); p += 8 // second MonoScript pathID
        put64(bytes, p, 16L, true); p += 8 // byte start
        put32(bytes, p, 16, true); p += 4 // byte size
        put32(bytes, p, 1, true); p += 4 // type index -> class 115
        put32(bytes, p, 1, true); p += 4 // script reference count
        put32(bytes, p, 0, true); p += 4 // fileID 0 = same SerializedFile
        p = (p + 3) and -4
        put64(bytes, p, 777L, true); p += 8
        put32(bytes, p, 0, true); p += 4 // external count
        put32(bytes, p, 0, true) // SerializeReference types

        val result = requireNotNull(UnityMetadataParser.parse(bytes, header))
        val binding = result.scriptTypeBindings.single()
        assertEquals(114, binding.classId)
        assertEquals(0, binding.scriptTypeIndex)
        assertEquals(0, binding.fileId)
        assertEquals(777L, binding.pathId)
        assertEquals(115, binding.targetObjectClassId)
        assertEquals("local-monoscript-object-found", binding.resolution)
        val monoScriptTypeCount = result.objectTypeCounts.single { it.classId == 115 }
        assertEquals(2L, monoScriptTypeCount.count)
        assertEquals(listOf(777L, 778L), monoScriptTypeCount.samplePathIds)
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
