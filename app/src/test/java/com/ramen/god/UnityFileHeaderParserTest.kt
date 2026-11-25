package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityFileHeaderParserTest {
    @Test
    fun parsesLegacySerializedFile() {
        val bytes = serializedV9()
        val result = UnityFileHeaderParser.parse(bytes, bytes.size.toLong())
        requireNotNull(result)
        assertEquals("SerializedFile", result.kind)
        assertEquals(9L, result.formatVersion)
        assertEquals(512L, result.fileSize)
        assertEquals(128L, result.metadataSize)
        assertEquals(384L, result.dataOffset)
        assertEquals("Little Endian", result.endianness)
    }

    @Test
    fun parsesModern64BitSerializedFile() {
        val bytes = serializedV22()
        val result = UnityFileHeaderParser.parse(bytes, 5_000_000_000L)
        requireNotNull(result)
        assertEquals(22L, result.formatVersion)
        assertEquals(5_000_000_000L, result.fileSize)
        assertEquals(2048L, result.metadataSize)
        assertEquals(4096L, result.dataOffset)
        assertEquals(48, result.headerSize)
    }

    @Test
    fun parsesBigEndianFlag() {
        val bytes = serializedV9(endian = 1)
        assertEquals("Big Endian", UnityFileHeaderParser.parse(bytes, 512)?.endianness)
    }

    @Test
    fun recognizesUnityFsBundleOnlyAfterValidatingFixedHeaderTail() {
        val bytes = unityFsBundle()
        val result = UnityFileHeaderParser.parse(bytes, 4096)
        requireNotNull(result)
        assertEquals("UnityFS bundle", result.kind)
        assertEquals(6L, result.formatVersion)
        assertEquals(4096L, result.fileSize)
        assertEquals(64L, result.bundleCompressedBlocksInfoSize)
        assertEquals(128L, result.bundleUncompressedBlocksInfoSize)
        assertEquals(0L, result.bundleFlags)
        assertEquals("5.x.x", result.playerVersion)
        assertEquals("2022.3.1f1", result.engineVersion)
    }

    @Test
    fun rejectsUnityFsFormatVersionsBelowSix() {
        val bytes = unityFsBundle()
        put32be(bytes, 8, 5)
        assertNull(UnityFileHeaderParser.parse(bytes, 4096))
    }

    @Test
    fun rejectsUnityFsPrefixWithoutRequiredSizeAndFlagsFields() {
        val bytes = "UnityFS".toByteArray() + byteArrayOf(0, 0, 0, 0, 6) +
            "5.x.x".toByteArray() + byteArrayOf(0) + "2022.3.1f1".toByteArray() + byteArrayOf(0)
        assertNull(UnityFileHeaderParser.parse(bytes, 4096))
    }

    @Test
    fun rejectsUnityFsHeaderWithImpossibleDeclaredSizes() {
        val oversized = unityFsBundle()
        put64be(oversized, unityFsTailOffset(oversized), 8192L)
        assertNull(UnityFileHeaderParser.parse(oversized, 4096))

        val oversizedBlockInfo = unityFsBundle()
        put32be(oversizedBlockInfo, unityFsTailOffset(oversizedBlockInfo) + 8, 4096L)
        assertNull(UnityFileHeaderParser.parse(oversizedBlockInfo, 4096))
    }

    @Test
    fun rejectsInvalidAndTruncatedHeaders() {
        assertNull(UnityFileHeaderParser.parse(ByteArray(32), 32))
        assertNull(UnityFileHeaderParser.parse(serializedV9().copyOf(19), 512))
        assertNull(UnityFileHeaderParser.parse(serializedV22().copyOf(40), 5_000_000_000L))
    }

    @Test
    fun rejectsImpossibleOffsetsAndSizes() {
        val badOffset = serializedV9()
        put32be(badOffset, 12, 900)
        assertNull(UnityFileHeaderParser.parse(badOffset, badOffset.size.toLong()))
        val badEndian = serializedV9()
        badEndian[16] = 7
        assertNull(UnityFileHeaderParser.parse(badEndian, badEndian.size.toLong()))
    }

    @Test
    fun rejectsMetadataSizeThatWouldOverflowHeaderEndCalculation() {
        val bytes = serializedV22()
        put64be(bytes, 16, Long.MAX_VALUE)
        put64be(bytes, 24, Long.MAX_VALUE)
        put64be(bytes, 32, 48L)

        assertNull(UnityFileHeaderParser.parse(bytes, Long.MAX_VALUE))
    }

    @Test
    fun reportsLegacyPreV9AsNeedingRandomAccess() {
        val bytes = ByteArray(512)
        put32be(bytes, 0, 128)
        put32be(bytes, 4, 512)
        put32be(bytes, 8, 8)
        put32be(bytes, 12, 32)
        val result = UnityFileHeaderParser.parse(bytes, 512)
        requireNotNull(result)
        assertTrue(result.note?.contains("позиционное чтение") == true)
    }

    private fun unityFsBundle(): ByteArray {
        val headerPrefix = "UnityFS".toByteArray() + byteArrayOf(0) +
            byteArrayOf(0, 0, 0, 6) +
            "5.x.x".toByteArray() + byteArrayOf(0) +
            "2022.3.1f1".toByteArray() + byteArrayOf(0)
        val bytes = headerPrefix + ByteArray(20)
        val tail = headerPrefix.size
        put64be(bytes, tail, 4096L)
        put32be(bytes, tail + 8, 64L)
        put32be(bytes, tail + 12, 128L)
        put32be(bytes, tail + 16, 0L)
        return bytes
    }

    private fun unityFsTailOffset(bytes: ByteArray): Int {
        val playerEnd = 12 + "5.x.x".length + 1
        return playerEnd + "2022.3.1f1".length + 1
    }

    private fun serializedV9(endian: Int = 0): ByteArray {
        val bytes = ByteArray(512)
        put32be(bytes, 0, 128)
        put32be(bytes, 4, 512)
        put32be(bytes, 8, 9)
        put32be(bytes, 12, 384)
        bytes[16] = endian.toByte()
        return bytes
    }

    private fun serializedV22(): ByteArray {
        val bytes = ByteArray(64)
        put32be(bytes, 8, 22)
        put64be(bytes, 16, 2048L)
        put64be(bytes, 24, 5_000_000_000L)
        put64be(bytes, 32, 4096L)
        bytes[40] = 0
        return bytes
    }

    private fun put64be(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) bytes[offset + index] = (value ushr (56 - index * 8)).toByte()
    }

    private fun put32be(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
    }
}
