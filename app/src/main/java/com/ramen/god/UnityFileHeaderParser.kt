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
    val note: String? = null,
    val playerVersion: String? = null,
    val engineVersion: String? = null,
    val bundleCompressedBlocksInfoSize: Long? = null,
    val bundleUncompressedBlocksInfoSize: Long? = null,
    val bundleFlags: Long? = null
)

internal object UnityFileHeaderParser {
    private const val LEGACY_HEADER_SIZE = 20
    private const val MODERN_HEADER_SIZE = 48
    private const val MAX_FORMAT_VERSION = 100

    fun parse(prefix: ByteArray, actualFileSize: Long): UnityFileHeader? {
        if (actualFileSize <= 0L || prefix.isEmpty()) return null
        if (startsWith(prefix, "UnityFS")) return parseUnityFs(prefix, actualFileSize)
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
        // Subtraction avoids overflow when an untrusted UInt64-sized header
        // advertises a metadata size close to Long.MAX_VALUE.
        if (metadataSize > dataOffset - headerSize.toLong()) return null
        return UnityFileHeader(
            "SerializedFile", version, fileSize, metadataSize, dataOffset, endian, headerSize,
            if (fileSize < actualFileSize) "В файле есть дополнительные байты после заявленного размера." else null
        )
    }

    private fun parseUnityFs(bytes: ByteArray, actualFileSize: Long): UnityFileHeader? {
        if (bytes.size < 16 || bytes[7].toInt() != 0) return null
        val version = u32be(bytes, 8)
        if (version !in 1L..20L) return null
        val player = readCString(bytes, 12, 128) ?: return null
        val revisionStart = 12 + player.second
        val revision = readCString(bytes, revisionStart, 128) ?: return null
        val fieldsOffset = revisionStart.toLong() + revision.second.toLong()
        if (fieldsOffset > Int.MAX_VALUE || !has(bytes, fieldsOffset.toInt(), 20)) return null
        val headerEnd = fieldsOffset.toInt() + 20

        // UnityFS fixed tail: UInt64 bundle size, then compressed and
        // uncompressed block-info sizes and archive flags (all big-endian).
        val bundleSize = i64be(bytes, fieldsOffset.toInt()) ?: return null
        val compressedInfoSize = u32be(bytes, fieldsOffset.toInt() + 8)
        val uncompressedInfoSize = u32be(bytes, fieldsOffset.toInt() + 12)
        val flags = u32be(bytes, fieldsOffset.toInt() + 16)
        if (bundleSize < headerEnd.toLong() || bundleSize > actualFileSize) return null
        if (compressedInfoSize == 0L || uncompressedInfoSize == 0L) return null
        if (compressedInfoSize > bundleSize - headerEnd.toLong()) return null

        return UnityFileHeader(
            kind = "UnityFS bundle",
            formatVersion = version,
            fileSize = bundleSize,
            metadataSize = null,
            dataOffset = null,
            endianness = null,
            headerSize = headerEnd,
            note = "Полный фиксированный заголовок UnityFS проверен; таблица блоков и payload не разбираются. " +
                "Compression=" + (flags and 0x3f) + ", flags=0x" + flags.toString(16) +
                ", Player=" + player.first + ", revision=" + revision.first,
            playerVersion = player.first,
            engineVersion = revision.first,
            bundleCompressedBlocksInfoSize = compressedInfoSize,
            bundleUncompressedBlocksInfoSize = uncompressedInfoSize,
            bundleFlags = flags
        )
    }

    private fun readCString(bytes: ByteArray, offset: Int, maximum: Int): Pair<String, Int>? {
        if (offset < 0 || offset >= bytes.size) return null
        val limit = minOf(bytes.size.toLong(), offset.toLong() + maximum.toLong()).toInt()
        var end = offset
        while (end < limit && bytes[end].toInt() != 0) end++
        if (end == limit) return null
        return String(bytes, offset, end - offset, Charsets.UTF_8) to (end - offset + 1)
    }

    private fun has(bytes: ByteArray, offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset.toLong() + length.toLong() <= bytes.size.toLong()

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
