package com.ramen.god

/** Bounded recognizer for Unity SerializedFile and UnityFS headers. */
internal data class UnityFileHeader(
    val kind: String,
    val formatVersion: Long?,
    val fileSize: Long?,
    val metadataSize: Long?,
    val dataOffset: Long?,
    val endianness: String?,
    val headerSize: Int,
    val note: String? = null
)

internal object UnityFileHeaderParser {
    private const val LEGACY_HEADER_SIZE = 20
    private const val MODERN_HEADER_SIZE = 48
    private const val MAX_FORMAT_VERSION = 100

    fun parse(prefix: ByteArray, actualFileSize: Long): UnityFileHeader? {
        if (actualFileSize <= 0L || prefix.isEmpty()) return null
        if (startsWith(prefix, "UnityFS")) return parseUnityFs(prefix)
        if (prefix.size < 16) return null

        val legacyMetadata = u32be(prefix, 0)
        val legacyFileSize = u32be(prefix, 4)
        val version = u32be(prefix, 8)
        val legacyDataOffset = u32be(prefix, 12)
        if (version !in 1L..MAX_FORMAT_VERSION.toLong()) return null

        if (version < 9L) {
            if (legacyFileSize == 0L || legacyFileSize > actualFileSize ||
                legacyMetadata == 0L || legacyMetadata >= legacyFileSize) return null
            return UnityFileHeader(
                "SerializedFile", version, legacyFileSize, legacyMetadata, legacyDataOffset,
                "не определён (старый формат, нужен seek)", 16,
                "Для формата до версии 9 маркер endian расположен перед metadata; требуется позиционное чтение."
            )
        }

        if (version < 22L) {
            if (prefix.size < LEGACY_HEADER_SIZE) return null
            val endian = endianName(prefix[16].toInt() and 0xff) ?: return null
            return validSerialized(version, legacyFileSize, legacyMetadata, legacyDataOffset,
                endian, LEGACY_HEADER_SIZE, actualFileSize)
        }

        if (prefix.size < MODERN_HEADER_SIZE) return null
        // Modern 48-byte header: legacy placeholder (0..15), then three UInt64
        // fields (metadata size, file size, data offset), then endian at byte 40.
        val metadataSize = i64be(prefix, 16) ?: return null
        val fileSize = i64be(prefix, 24) ?: return null
        val dataOffset = i64be(prefix, 32) ?: return null
        val endian = endianName(prefix[40].toInt() and 0xff) ?: return null
        if (metadataSize == 0L || fileSize <= 0L || dataOffset < MODERN_HEADER_SIZE.toLong()) return null
        return validSerialized(version, fileSize, metadataSize, dataOffset, endian, MODERN_HEADER_SIZE, actualFileSize)
    }

    private fun validSerialized(
        version: Long, fileSize: Long, metadataSize: Long, dataOffset: Long,
        endian: String, headerSize: Int, actualFileSize: Long
    ): UnityFileHeader? {
        if (fileSize <= 0L || fileSize > actualFileSize) return null
        if (metadataSize <= 0L || metadataSize > fileSize) return null
        if (dataOffset < headerSize.toLong() || dataOffset > fileSize) return null
        if (headerSize.toLong() + metadataSize > dataOffset) return null
        return UnityFileHeader(
            "SerializedFile", version, fileSize, metadataSize, dataOffset, endian, headerSize,
            if (fileSize < actualFileSize) "В файле есть дополнительные байты после заявленного размера." else null
        )
    }

    private fun parseUnityFs(bytes: ByteArray): UnityFileHeader? {
        if (bytes.size < 13) return null
        val version = u32be(bytes, 7)
        if (version !in 1L..20L) return null
        val player = readCString(bytes, 11, 128) ?: return null
        val revisionStart = 11 + player.second
        val revision = readCString(bytes, revisionStart, 128) ?: return null
        return UnityFileHeader(
            "UnityFS bundle", version, null, null, null, null,
            revisionStart + revision.second,
            "Распознан только заголовок контейнера; блоки и сжатие пока не разбираются. Player=" +
                player.first + ", revision=" + revision.first
        )
    }

    private fun readCString(bytes: ByteArray, offset: Int, maximum: Int): Pair<String, Int>? {
        if (offset < 0 || offset >= bytes.size) return null
        val limit = minOf(bytes.size, offset + maximum)
        var end = offset
        while (end < limit && bytes[end].toInt() != 0) end++
        if (end == limit) return null
        return String(bytes, offset, end - offset, Charsets.UTF_8) to (end - offset + 1)
    }

    private fun startsWith(bytes: ByteArray, value: String): Boolean {
        if (bytes.size < value.length) return false
        return value.indices.all { (bytes[it].toInt() and 0xff) == value[it].code }
    }

    private fun endianName(value: Int): String? = when (value) {
        0 -> "Little Endian"
        1 -> "Big Endian"
        else -> null
    }

    private fun u32be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun i64be(bytes: ByteArray, offset: Int): Long? {
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
        return if (value < 0L) null else value
    }
}
