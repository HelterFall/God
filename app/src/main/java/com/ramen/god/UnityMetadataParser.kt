package com.ramen.god

/** Safely reads the fixed leading fields of SerializedFile metadata (v19..v26). */
internal data class UnityMetadataSummary(
    val unityVersion: String?,
    val targetPlatform: Long?,
    val typeTreeEnabled: Boolean?,
    val typeCount: Long?,
    val note: String? = null
)

internal object UnityMetadataParser {
    private const val MIN_SUPPORTED_VERSION = 19L
    private const val MAX_SUPPORTED_VERSION = 26L
    private const val MAX_VERSION_STRING = 64
    private const val MAX_TYPE_COUNT = 200_000L

    fun parse(prefix: ByteArray, header: UnityFileHeader): UnityMetadataSummary? {
        if (header.kind != "SerializedFile") return null
        val version = header.formatVersion ?: return null
        if (version !in MIN_SUPPORTED_VERSION..MAX_SUPPORTED_VERSION) {
            return UnityMetadataSummary(
                null, null, null, null,
                "Чтение метаданных пока поддерживается только для форматов 19–26."
            )
        }
        val base = header.headerSize
        if (prefix.size <= base) return null
        val little = header.endianness == "Little Endian"
        var cursor = base

        val versionEnd = findZero(prefix, cursor, minOf(prefix.size, cursor + MAX_VERSION_STRING + 1))
        if (versionEnd < 0) return null
        val unityVersion = String(prefix, cursor, versionEnd - cursor, Charsets.US_ASCII)
        if (unityVersion.isBlank() || unityVersion.length > MAX_VERSION_STRING) return null
        cursor = versionEnd + 1

        val targetPlatform = readU32(prefix, cursor, little) ?: return null
        cursor += 4
        if (cursor >= prefix.size) return null
        val typeTreeFlag = prefix[cursor].toInt() and 0xff
        if (typeTreeFlag !in 0..1) return null
        cursor += 1

        val typeCount = readU32(prefix, cursor, little) ?: return null
        if (typeCount > MAX_TYPE_COUNT) return null
        return UnityMetadataSummary(
            unityVersion,
            targetPlatform,
            typeTreeFlag == 1,
            typeCount,
            if (typeTreeFlag == 0) "TypeTree в файле отключён." else null
        )
    }

    private fun findZero(bytes: ByteArray, start: Int, endExclusive: Int): Int {
        for (i in start until endExclusive) if (bytes[i].toInt() == 0) return i
        return -1
    }

    private fun readU32(bytes: ByteArray, offset: Int, little: Boolean): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        var value = 0L
        if (little) {
            for (i in 3 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        } else {
            for (i in 0..3) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        }
        return value
    }
}
