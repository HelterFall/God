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
