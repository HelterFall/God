package com.ramen.god

/** Bounded metadata summary for modern Unity SerializedFiles. */
internal data class UnityMetadataSummary(
    val unityVersion: String?,
    val targetPlatform: Long?,
    val typeTreeEnabled: Boolean?,
    val typeCount: Long?,
    val objectCount: Long? = null,
    val scriptTypeCount: Long? = null,
    val externalReferenceCount: Long? = null,
    val note: String? = null
)

internal object UnityMetadataParser {
    private const val MIN_SUPPORTED_VERSION = 19L
    private const val MAX_SUPPORTED_VERSION = 26L
    private const val MAX_VERSION_STRING = 64
    private const val MAX_TYPE_COUNT = 200_000L
    private const val MAX_OBJECT_COUNT = 2_000_000L
    private const val MAX_AUX_COUNT = 500_000L
    private const val TYPE_TREE_NODE_SIZE = 32L

    fun parse(prefix: ByteArray, header: UnityFileHeader): UnityMetadataSummary? {
        if (header.kind != "SerializedFile") return null
        val version = header.formatVersion ?: return null
        if (version !in MIN_SUPPORTED_VERSION..MAX_SUPPORTED_VERSION) {
            return UnityMetadataSummary(
                null, null, null, null,
                note = "Чтение метаданных пока поддерживается только для форматов 19–26."
            )
        }
        val base = header.headerSize
        if (prefix.size <= base) return null
        val little = header.endianness == "Little Endian"
        val cursor = Cursor(prefix, base, little)

        val unityVersion = cursor.readCString(MAX_VERSION_STRING) ?: return null
        if (unityVersion.isBlank()) return null
        val targetPlatform = cursor.readU32() ?: return null
        val typeTreeByte = cursor.readU8() ?: return null
        if (typeTreeByte !in 0..1) return null
        val typeCount = cursor.readU32() ?: return null
        if (typeCount > MAX_TYPE_COUNT) return null

        val partial = UnityMetadataSummary(
            unityVersion, targetPlatform, typeTreeByte == 1, typeCount
        )
        val typeTreeEnabled = typeTreeByte == 1

        // Skip type records with strict bounds so we can reach the object table.
        repeat(typeCount.toInt()) {
            if (!skipTypeEntry(cursor, version, typeTreeEnabled)) {
                return partial.copy(note = "Таблица типов обрезана или содержит неподдерживаемые размеры; object count не прочитан.")
            }
        }

        val objectCount = cursor.readU32()
            ?: return partial.copy(note = "Не удалось дочитать metadata до таблицы объектов.")
        if (objectCount > MAX_OBJECT_COUNT) {
            return partial.copy(note = "Число объектов превышает безопасный лимит.")
        }

        repeat(objectCount.toInt()) {
            if (!cursor.align4() || cursor.readU64() == null) {
                return partial.copy(objectCount = objectCount, note = "Таблица объектов обрезана.")
            }
            val relativeStart = if (version >= 22L) cursor.readU64() else cursor.readU32()
            if (relativeStart == null || cursor.readU32() == null || cursor.readU32() == null) {
                return partial.copy(objectCount = objectCount, note = "Таблица объектов обрезана.")
            }
        }

        val scriptCount = cursor.readU32()
            ?: return partial.copy(objectCount = objectCount, note = "Не удалось дочитать metadata до списка MonoScript.")
        if (scriptCount > MAX_AUX_COUNT) {
            return partial.copy(objectCount = objectCount, note = "Число ссылок MonoScript превышает безопасный лимит.")
        }
        repeat(scriptCount.toInt()) {
            if (cursor.readU32() == null || !cursor.align4() || cursor.readU64() == null) {
                return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount, note = "Список MonoScript обрезан.")
            }
        }

        val externalCount = cursor.readU32()
            ?: return partial.copy(
                objectCount = objectCount, scriptTypeCount = scriptCount,
                note = "Не удалось дочитать metadata до списка внешних файлов."
            )
        if (externalCount > MAX_AUX_COUNT) {
            return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount,
                note = "Число внешних ссылок превышает безопасный лимит.")
        }
        repeat(externalCount.toInt()) {
            if (cursor.readCString(4096) == null || !cursor.skip(16) ||
                cursor.readU32() == null || cursor.readCString(4096) == null) {
                return partial.copy(objectCount = objectCount, scriptTypeCount = scriptCount,
                    externalReferenceCount = externalCount, note = "Список внешних ссылок обрезан.")
            }
        }

        return partial.copy(
            objectCount = objectCount,
            scriptTypeCount = scriptCount,
            externalReferenceCount = externalCount,
            note = if (prefix.size < header.metadataSize?.plus(base)?.coerceAtMost(Int.MAX_VALUE.toLong()) ?: false) {
                "Прочитаны основные таблицы metadata; последующие расширения (SerializeReference/TypeTree shared subtrees) не анализировались."
            } else {
                "Прочитаны основные таблицы metadata; расширения SerializeReference/TypeTree shared subtrees не анализировались."
            }
        )
    }

    private fun skipTypeEntry(cursor: Cursor, version: Long, typeTreeEnabled: Boolean): Boolean {
        val classId = cursor.readI32() ?: return false
        if (version >= 16L && cursor.readU8() == null) return false
        val scriptTypeIndex = if (version >= 17L) cursor.readI16() ?: return false else -1
        if (classId == -1 || classId == 114 || scriptTypeIndex >= 0) {
            if (!cursor.skip(16)) return false
        }
        if (!cursor.skip(16)) return false // old type hash
        if (!typeTreeEnabled) return true

        if (version >= 23L) {
            if (!cursor.skip(16)) return false // TypeTree content hash
            val treeSize = cursor.readU32() ?: return false
            if (treeSize > 0 && !cursor.skip(treeSize)) return false
        } else {
            val nodeCount = cursor.readU32() ?: return false
            val stringSize = cursor.readU32() ?: return false
            if (nodeCount > 1_000_000L) return false
            val nodeBytes = nodeCount * TYPE_TREE_NODE_SIZE
            if (nodeBytes / TYPE_TREE_NODE_SIZE != nodeCount) return false
            if (!cursor.skip(nodeBytes + stringSize)) return false
        }

        if (version >= 21L) {
            val dependencyCount = cursor.readU32() ?: return false
            if (dependencyCount > MAX_AUX_COUNT || !cursor.skip(dependencyCount * 4L)) return false
        }
        return true
    }

    private class Cursor(
        private val bytes: ByteArray,
        private var position: Int,
        private val little: Boolean
    ) {
        fun readU8(): Int? {
            if (position >= bytes.size) return null
            return bytes[position++].toInt() and 0xff
        }

        fun readI16(): Int? {
            val value = readUnsigned(2) ?: return null
            return value.toInt().toShort().toInt()
        }

        fun readI32(): Int? {
            val value = readUnsigned(4) ?: return null
            return value.toInt()
        }

        fun readU32(): Long? = readUnsigned(4)

        fun readU64(): Long? {
            val value = readUnsigned(8) ?: return null
            return value
        }

        fun readCString(maximum: Int): String? {
            val endLimit = minOf(bytes.size, position + maximum + 1)
            var end = position
            while (end < endLimit && bytes[end].toInt() != 0) end++
            if (end >= endLimit) return null
            val result = String(bytes, position, end - position, Charsets.US_ASCII)
            position = end + 1
            return result
        }

        fun skip(count: Long): Boolean {
            if (count < 0L || count > Int.MAX_VALUE.toLong()) return false
            val next = position.toLong() + count
            if (next > bytes.size) return false
            position = next.toInt()
            return true
        }

        fun align4(): Boolean {
            val relative = position - (if (position >= 0) 0 else 0)
            val aligned = (relative + 3) and -4
            if (aligned > bytes.size) return false
            position = aligned
            return true
        }

        private fun readUnsigned(count: Int): Long? {
            if (position < 0 || position + count > bytes.size) return null
            var value = 0L
            if (little) {
                for (i in count - 1 downTo 0) value = (value shl 8) or (bytes[position + i].toLong() and 0xff)
            } else {
                for (i in 0 until count) value = (value shl 8) or (bytes[position + i].toLong() and 0xff)
            }
            position += count
            return value
        }
    }
}
