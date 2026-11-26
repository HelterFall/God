package com.ramen.god

import java.io.InputStream

internal data class MonoScriptObjectTarget(
    val pathId: Long,
    val byteStart: Long,
    val byteSize: Long
)

internal data class UnityMonoScriptSummary(
    val pathId: Long,
    val name: String,
    val executionOrder: Int,
    val className: String,
    val namespace: String,
    val assemblyName: String,
    val sourceFilePath: String? = null
)

internal data class MonoScriptReadResult(
    val scripts: List<UnityMonoScriptSummary>,
    val skippedCount: Int,
    val note: String? = null
)

/**
 * Reads the common MonoScript object layout using strict byte and traversal
 * limits. It never assumes that every local script reference is readable.
 */
internal object MonoScriptParser {
    private const val MAX_STRING_BYTES = 4096
    private const val MAX_OBJECT_BYTES = 64 * 1024
    private const val MAX_OBJECTS_PER_FILE = 64
    private const val MAX_SCAN_DISTANCE = 64L * 1024L * 1024L

    fun parse(bytes: ByteArray, littleEndian: Boolean): Triple<String, Int, List<String>>? {
        val cursor = Cursor(bytes, littleEndian)
        val name = cursor.readAlignedString() ?: return null
        val executionOrder = cursor.readI32() ?: return null
        if (!cursor.skip(16)) return null // m_PropertiesHash
        val className = cursor.readAlignedString() ?: return null
        val namespace = cursor.readAlignedString() ?: return null
        val assemblyName = cursor.readString() ?: return null
        return Triple(name, executionOrder, listOf(className, namespace, assemblyName))
    }

    fun readObjects(
        input: InputStream,
        dataOffset: Long,
        fileSize: Long,
        targets: List<MonoScriptObjectTarget>,
        littleEndian: Boolean,
        shouldCancel: () -> Boolean = { false }
    ): MonoScriptReadResult {
        if (dataOffset < 0L || fileSize < dataOffset) {
            return MonoScriptReadResult(emptyList(), targets.size, "Некорректные границы data section.")
        }
        val seenPathIds = HashSet<Long>(targets.size)
        for (target in targets) {
            if (!seenPathIds.add(target.pathId)) {
                return MonoScriptReadResult(
                    emptyList(),
                    targets.size,
                    "Duplicate MonoScript PathID in decoder targets; decoding stopped to avoid ambiguous selection."
                )
            }
        }
        val ordered = targets.sortedBy { it.byteStart }
        val limited = ordered.take(MAX_OBJECTS_PER_FILE)
        var skipped = (ordered.size - limited.size).coerceAtLeast(0)
        var position = 0L
        val records = ArrayList<UnityMonoScriptSummary>()
        val buffer = ByteArray(8192)

        for ((index, target) in limited.withIndex()) {
            if (shouldCancel()) throw ScanCancelledException()
            if (target.byteStart < 0L || target.byteSize !in 1L..MAX_OBJECT_BYTES.toLong()) {
                skipped++
                continue
            }
            if (dataOffset > Long.MAX_VALUE - target.byteStart) {
                skipped++
                continue
            }
            val absoluteStart = dataOffset + target.byteStart
            if (absoluteStart > fileSize || target.byteSize > fileSize - absoluteStart) {
                skipped++
                continue
            }
            if (absoluteStart > MAX_SCAN_DISTANCE) {
                skipped += limited.size - index
                break
            }
            if (absoluteStart < position) {
                skipped++
                continue
            }
            if (!skipFully(input, absoluteStart - position, buffer, shouldCancel)) {
                skipped += limited.size - index
                return MonoScriptReadResult(records, skipped, "Поток закончился до MonoScript object.")
            }
            position = absoluteStart

            val payload = readExactly(input, target.byteSize.toInt(), shouldCancel) ?: run {
                skipped += limited.size - index
                return MonoScriptReadResult(records, skipped, "MonoScript object обрезан.")
            }
            position += payload.size
            val parsed = parse(payload, littleEndian)
            if (parsed == null) {
                skipped++
                continue
            }
            records.add(
                UnityMonoScriptSummary(
                    target.pathId,
                    parsed.first,
                    parsed.second,
                    parsed.third[0],
                    parsed.third[1],
                    parsed.third[2]
                )
            )
        }
        val note = when {
            skipped == 0 -> null
            records.isEmpty() -> "MonoScript objects не удалось прочитать в установленных лимитах."
            else -> "Прочитано ${records.size} MonoScript objects; пропущено $skipped по лимитам или из-за повреждённых данных."
        }
        return MonoScriptReadResult(records, skipped, note)
    }

    private fun skipFully(
        input: InputStream,
        count: Long,
        buffer: ByteArray,
        shouldCancel: () -> Boolean
    ): Boolean {
        var remaining = count
        while (remaining > 0L) {
            if (shouldCancel()) throw ScanCancelledException()
            // Read bounded chunks rather than delegating a potentially huge skip()
            // to a provider stream that may not offer cancellation points.
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) return false
            remaining -= read.toLong()
        }
        return true
    }

    private fun readExactly(
        input: InputStream,
        count: Int,
        shouldCancel: () -> Boolean
    ): ByteArray? {
        if (count < 0 || count > MAX_OBJECT_BYTES) return null
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            if (shouldCancel()) throw ScanCancelledException()
            val chunkSize = minOf(8192, count - offset)
            val read = input.read(result, offset, chunkSize)
            if (read <= 0) return null
            offset += read
        }
        return result
    }

    private class Cursor(
        private val bytes: ByteArray,
        private val littleEndian: Boolean
    ) {
        private var position = 0

        fun readString(): String? {
            val length = readI32() ?: return null
            if (length < 0 || length > MAX_STRING_BYTES || position.toLong() + length > bytes.size) return null
            val result = String(bytes, position, length, Charsets.UTF_8)
            position += length
            return result
        }

        fun readAlignedString(): String? {
            val result = readString() ?: return null
            val aligned = (position + 3) and -4
            if (aligned > bytes.size) return null
            position = aligned
            return result
        }

        fun readI32(): Int? {
            if (position < 0 || position.toLong() + 4L > bytes.size) return null
            var result = 0
            if (littleEndian) {
                for (i in 3 downTo 0) result = (result shl 8) or (bytes[position + i].toInt() and 0xff)
            } else {
                for (i in 0..3) result = (result shl 8) or (bytes[position + i].toInt() and 0xff)
            }
            position += 4
            return result
        }

        fun skip(count: Int): Boolean {
            if (count < 0 || position.toLong() + count > bytes.size) return false
            position += count
            return true
        }
    }
}
