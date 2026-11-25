package com.ramen.god

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClrMetadataParserTest {
    @Test
    fun readsAssemblyIdentityTypeNamesAndAssemblyReferences() {
        val metadata = syntheticMetadataRoot()
        val result = ClrMetadataParser.parse(
            metadata, 0, metadata.size.toLong(), runtimeVersion = "4.0", clrFlags = 1
        )

        assertNotNull(result)
        requireNotNull(result)
        assertEquals("Assembly-CSharp", result.name)
        assertEquals("1.2.3.4", result.version)
        assertEquals("Sample.dll", result.moduleName)
        assertEquals("4.0", result.runtimeVersion)
        assertEquals(1, result.typeCount)
        assertEquals("PlayerController", result.types.single().name)
        assertEquals("Game", result.types.single().namespace)
        assertEquals(listOf("health"), result.types.single().fieldNames)
        assertEquals(listOf("TakeDamage"), result.types.single().methodNames)
        assertEquals("health", result.fields.single().name)
        assertEquals(6, result.fields.single().flags)
        assertEquals("Int32", result.fields.single().signatureType)
        assertEquals("parsed", result.fields.single().signatureStatus)
        assertEquals("TakeDamage", result.methods.single().name)
        assertEquals("Void", result.methods.single().returnType)
        assertEquals(listOf("Int32"), result.methods.single().parameterTypes)
        assertEquals("instance Void TakeDamage(Int32)", result.methods.single().signatureDisplay)
        assertEquals("default", result.methods.single().callingConvention)
        assertEquals("parsed", result.methods.single().signatureStatus)
        assertEquals(0x1234L, result.methods.single().relativeVirtualAddress)
        assertEquals(0x16, result.methods.single().flags)
        assertEquals("mscorlib", result.assemblyReferences.single().name)
        assertEquals("4.0.0.0", listOf(
            result.assemblyReferences.single().majorVersion,
            result.assemblyReferences.single().minorVersion,
            result.assemblyReferences.single().buildNumber,
            result.assemblyReferences.single().revisionNumber
        ).joinToString("."))
        assertEquals("parsed", result.status)
    }

    @Test
    fun reportsMissingBlobHeapAsPartialWhenSignaturesCannotBeDecoded() {
        val metadata = syntheticMetadataRoot(includeBlobHeap = false)
        val result = requireNotNull(ClrMetadataParser.parse(metadata, 0, metadata.size.toLong()))

        assertEquals("partial-metadata-tables", result.status)
        assertEquals("blob-heap-unavailable", result.fields.single().signatureStatus)
        assertEquals("blob-heap-unavailable", result.methods.single().signatureStatus)
        assertTrue(result.note.orEmpty().contains("signatures"))
    }

    @Test
    fun reportsNonMonotonicTypeMemberStartsAsPartialMetadata() {
        val metadata = syntheticMetadataRoot(typeRowsCount = 2, invalidSecondTypeMemberStart = true)
        val result = requireNotNull(ClrMetadataParser.parse(metadata, 0, metadata.size.toLong()))

        assertEquals("partial-metadata-tables", result.status)
        assertTrue(result.note.orEmpty().contains("Привязка полей/методов"))
    }

    @Test
    fun reportsTypeSampleLimitAsPartialMetadata() {
        val metadata = syntheticMetadataRoot(typeRowsCount = 1001)
        val result = requireNotNull(ClrMetadataParser.parse(metadata, 0, metadata.size.toLong()))

        assertEquals(1001, result.typeCount)
        assertEquals(1000, result.types.size)
        assertEquals("partial-metadata-tables", result.status)
        assertTrue(result.note.orEmpty().contains("ограничены первыми 1000"))
    }

    @Test
    fun rejectsDuplicateMetadataStreamNames() {
        val metadata = syntheticMetadataRoot()
        val blobName = metadata.indexOfSubsequence("#Blob".toByteArray(Charsets.US_ASCII))
        require(blobName >= 0)
        metadata[blobName] = '#'.code.toByte()
        metadata[blobName + 1] = '~'.code.toByte()
        metadata[blobName + 2] = 0

        val result = requireNotNull(ClrMetadataParser.parse(metadata, 0, metadata.size.toLong()))
        assertEquals("duplicate-stream-name", result.status)
    }

    @Test
    fun rejectsOverlappingMetadataStreamPayloads() {
        val metadata = syntheticMetadataRoot()
        val tablesName = metadata.indexOfSubsequence("#~".toByteArray(Charsets.US_ASCII))
        val stringsName = metadata.indexOfSubsequence("#Strings".toByteArray(Charsets.US_ASCII))
        require(tablesName >= 8 && stringsName >= 8)
        val tablesOffset = readU32(metadata, tablesName - 8)
        put32(metadata, stringsName - 8, tablesOffset)

        val result = requireNotNull(ClrMetadataParser.parse(metadata, 0, metadata.size.toLong()))
        assertEquals("overlapping-streams", result.status)
    }

    @Test
    fun reportsMetadataOutsideBoundedReadWindow() {
        val result = ClrMetadataParser.parse(ByteArray(32), 16, 1024, "4.0", 0)
        assertNotNull(result)
        assertEquals("metadata-outside-read-window", result?.status)
    }

    @Test
    fun rejectsInvalidMetadataSignatureWithoutThrowing() {
        val result = ClrMetadataParser.parse(ByteArray(64), 0, 64, "4.0", 0)
        assertNotNull(result)
        assertEquals("invalid-metadata-signature", result?.status)
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (start in 0..(size - needle.size)) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        return -1
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (8 * index)).toByte()
    }

    private fun syntheticMetadataRoot(
        typeRowsCount: Int = 1,
        includeBlobHeap: Boolean = true,
        invalidSecondTypeMemberStart: Boolean = false
    ): ByteArray {
        val strings = ByteArrayOutputStream().apply {
            write(0)
        }
        fun addString(value: String): Int {
            val index = strings.size()
            strings.write(value.toByteArray(Charsets.UTF_8))
            strings.write(0)
            return index
        }
        val moduleName = addString("Sample.dll")
        val typeName = addString("PlayerController")
        val namespace = addString("Game")
        val assemblyName = addString("Assembly-CSharp")
        val referenceName = addString("mscorlib")
        val fieldName = addString("health")
        val methodName = addString("TakeDamage")
        val stringHeap = strings.toByteArray()

        val tables = ByteArrayOutputStream().apply {
            u32(0)
            u8(2); u8(0); u8(0); u8(1)
            u64((1L shl 0) or (1L shl 2) or (1L shl 4) or (1L shl 6) or (1L shl 32) or (1L shl 35))
            u64(0)
            u32(1); u32(typeRowsCount.toLong()); u32(1); u32(1); u32(1); u32(1)

            // Module row
            u16(0); u16(moduleName); u16(0); u16(0); u16(0)

            // TypeDef rows: the first row owns the field and method; later
            // rows start after those tables' only records.
            repeat(typeRowsCount) { index ->
                u32(1); u16(typeName); u16(namespace); u16(0)
                u16(if (index == 0) 1 else if (invalidSecondTypeMemberStart && index == 1) 0 else 2)
                u16(if (index == 0) 1 else 2)
            }

            // Field row: private Int32 health
            u16(6); u16(fieldName); u16(1)

            // MethodDef row: TakeDamage
            u32(0x1234); u16(0); u16(0x16); u16(methodName); u16(4); u16(1)

            // Assembly row: Assembly-CSharp, Version=1.2.3.4
            u32(0); u16(1); u16(2); u16(3); u16(4); u32(0)
            u16(0); u16(assemblyName); u16(0)

            // AssemblyRef row: mscorlib, Version=4.0.0.0
            u16(4); u16(0); u16(0); u16(0); u32(0)
            u16(0); u16(referenceName); u16(0); u16(0)
        }.toByteArray()

        val prefix = ByteArrayOutputStream().apply {
            u32(0x424a5342L)
            u16(1); u16(1); u32(0)
            u32(8)
            write(byteArrayOf('v'.code.toByte(), '4'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(), 0, 0, 0, 0))
            u16(0); u16(if (includeBlobHeap) 3 else 2)
        }.toByteArray()

        val blobHeap = byteArrayOf(0, 2, 0x06, 0x08, 4, 0x20, 1, 1, 0x08)
        fun paddedStreamHeaderLength(name: String): Int = 8 + (((name.length + 1 + 3) / 4) * 4)
        val headerLength = paddedStreamHeaderLength("#~") + paddedStreamHeaderLength("#Strings") +
            if (includeBlobHeap) paddedStreamHeaderLength("#Blob") else 0
        val tablesOffset = prefix.size + headerLength
        val stringsOffset = tablesOffset + tables.size
        val blobsOffset = stringsOffset + stringHeap.size
        val headers = ByteArrayOutputStream().apply {
            streamHeader(tablesOffset, tables.size, "#~")
            streamHeader(stringsOffset, stringHeap.size, "#Strings")
            if (includeBlobHeap) streamHeader(blobsOffset, blobHeap.size, "#Blob")
        }.toByteArray()
        return prefix + headers + tables + stringHeap + blobHeap
    }

    private fun ByteArrayOutputStream.streamHeader(offset: Int, size: Int, name: String) {
        u32(offset.toLong())
        u32(size.toLong())
        write(name.toByteArray(Charsets.US_ASCII))
        write(0)
        while (this.size() % 4 != 0) write(0)
    }

    private fun ByteArrayOutputStream.u8(value: Int) = write(value and 0xff)
    private fun ByteArrayOutputStream.u16(value: Int) {
        u8(value); u8(value ushr 8)
    }
    private fun ByteArrayOutputStream.u32(value: Long) {
        u8(value.toInt()); u8((value ushr 8).toInt())
        u8((value ushr 16).toInt()); u8((value ushr 24).toInt())
    }
    private fun ByteArrayOutputStream.u64(value: Long) {
        u32(value); u32(value ushr 32)
    }
}
