package com.ramen.god

internal data class ClrTypeSummary(
    val name: String,
    val namespace: String,
    val flags: Long
)

internal data class ClrAssemblyReferenceSummary(
    val name: String,
    val majorVersion: Int,
    val minorVersion: Int,
    val buildNumber: Int,
    val revisionNumber: Int,
    val culture: String
)

internal data class ClrAssemblySummary(
    val name: String?,
    val version: String?,
    val moduleName: String?,
    val runtimeVersion: String?,
    val flags: Long?,
    val typeCount: Int,
    val types: List<ClrTypeSummary>,
    val assemblyReferences: List<ClrAssemblyReferenceSummary>,
    val status: String,
    val note: String? = null
)

/**
 * Bounded reader for the ECMA-335 metadata root in a PE/CLR image.
 * It inventories assembly identity, TypeDef names and AssemblyRef rows;
 * it does not decode method bodies or infer runtime reachability.
 */
internal object ClrMetadataParser {
    private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
    private const val MAX_STREAMS = 32
    private const val MAX_TYPES = 1000
    private const val MAX_ASSEMBLY_REFS = 256
    private const val MAX_HEAP_STRING_BYTES = 4096

    private data class StreamInfo(val offset: Int, val size: Int)
    private data class AssemblyIdentity(val name: String?, val version: String?, val flags: Long?)
    private data class TablesInfo(
        val rows: LongArray,
        val tableOffsets: LongArray,
        val tableSizes: LongArray,
        val heapSizes: Int,
        val streamEnd: Int
    )

    fun parse(
        bytes: ByteArray,
        metadataOffset: Int?,
        metadataSize: Long?,
        runtimeVersion: String? = null,
        clrFlags: Long? = null
    ): ClrAssemblySummary? {
        if (metadataOffset == null || metadataSize == null || metadataSize <= 0L) return null
        if (metadataSize > MAX_METADATA_BYTES) {
            return emptySummary(runtimeVersion, clrFlags, "metadata-size-limit", "CLR metadata превышает лимит 2 MiB.")
        }
        if (metadataOffset < 0 || metadataSize > Int.MAX_VALUE ||
            metadataOffset.toLong() + metadataSize > bytes.size.toLong()) {
            return emptySummary(runtimeVersion, clrFlags, "metadata-outside-read-window", "CLR metadata находится за пределами прочитанного PE-префикса.")
        }
        val base = metadataOffset
        val end = base + metadataSize.toInt()
        val cursor = Cursor(bytes, base, end)
        if (cursor.u32() != 0x424a5342L) {
            return emptySummary(runtimeVersion, clrFlags, "invalid-metadata-signature", "Не найдена сигнатура BSJB.")
        }
        cursor.u16() ?: return emptySummary(runtimeVersion, clrFlags, "truncated-metadata-root")
        cursor.u16() ?: return emptySummary(runtimeVersion, clrFlags, "truncated-metadata-root")
        cursor.u32() ?: return emptySummary(runtimeVersion, clrFlags, "truncated-metadata-root")
        val versionLength = cursor.u32()?.toInt() ?: return emptySummary(runtimeVersion, clrFlags, "truncated-metadata-root")
        if (versionLength !in 1..256 || !cursor.skip(versionLength)) {
            return emptySummary(runtimeVersion, clrFlags, "invalid-runtime-version")
        }
        cursor.align4()
        if (cursor.u16() == null) return emptySummary(runtimeVersion, clrFlags, "truncated-stream-header")
        val streamCount = cursor.u16() ?: return emptySummary(runtimeVersion, clrFlags, "truncated-stream-header")
        if (streamCount !in 1..MAX_STREAMS) {
            return emptySummary(runtimeVersion, clrFlags, "invalid-stream-count")
        }

        val streams = linkedMapOf<String, StreamInfo>()
        repeat(streamCount) {
            val offset = cursor.u32()?.toInt() ?: return emptySummary(runtimeVersion, clrFlags, "truncated-stream-header")
            val size = cursor.u32()?.toInt() ?: return emptySummary(runtimeVersion, clrFlags, "truncated-stream-header")
            val name = cursor.cString(32) ?: return emptySummary(runtimeVersion, clrFlags, "invalid-stream-name")
            cursor.align4()
            if (offset < 0 || size < 0 || offset.toLong() + size > metadataSize) {
                return emptySummary(runtimeVersion, clrFlags, "stream-out-of-range", "Поток $name выходит за границы metadata.")
            }
            streams[name] = StreamInfo(offset, size)
        }

        val tables = streams["#~"] ?: streams["#-"]
            ?: return emptySummary(runtimeVersion, clrFlags, "tables-stream-missing")
        val strings = streams["#Strings"]
            ?: return emptySummary(runtimeVersion, clrFlags, "strings-heap-missing")
        val tablesInfo = parseTablesHeader(bytes, base, tables, end)
            ?: return emptySummary(runtimeVersion, clrFlags, "invalid-tables-stream")
        val tableRows = tablesInfo.rows
        val stringHeapStart = base + strings.offset
        val stringHeapEnd = stringHeapStart + strings.size

        val moduleName = readFirstModuleName(
            bytes, base, tablesInfo, strings, tableRows, stringHeapStart, stringHeapEnd
        )
        val assembly = readAssemblyIdentity(bytes, base, tablesInfo, strings, tableRows, stringHeapStart, stringHeapEnd)
        val typeResult = readTypes(bytes, base, tablesInfo, strings, tableRows, stringHeapStart, stringHeapEnd)
        val referenceResult = readAssemblyReferences(
            bytes, base, tablesInfo, strings, tableRows, stringHeapStart, stringHeapEnd
        )

        val notes = listOfNotNull(typeResult.second, referenceResult.second).distinct()
        val status = when {
            typeResult.second == "type-table-unavailable" -> "partial-type-table-unavailable"
            referenceResult.second == "assembly-reference-table-unavailable" -> "partial-assembly-refs-unavailable"
            else -> "parsed"
        }
        return ClrAssemblySummary(
            name = assembly?.name,
            version = assembly?.version,
            moduleName = moduleName,
            runtimeVersion = runtimeVersion,
            flags = assembly?.flags ?: clrFlags,
            typeCount = tableRows[2].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            types = typeResult.first,
            assemblyReferences = referenceResult.first,
            status = status,
            note = notes.joinToString("; ").ifBlank { null }
        )
    }

    private fun parseTablesHeader(
        bytes: ByteArray,
        base: Int,
        stream: StreamInfo,
        metadataEnd: Int
    ): TablesInfo? {
        val start = base.toLong() + stream.offset
        val streamEndLong = start + stream.size
        if (start > Int.MAX_VALUE || streamEndLong > metadataEnd || stream.size < 24) return null
        val c = Cursor(bytes, start.toInt(), streamEndLong.toInt())
        if (c.u32() == null || c.u8() == null || c.u8() == null) return null
        val heapSizes = c.u8() ?: return null
        if (c.u8() == null) return null
        val valid = c.u64() ?: return null
        c.u64() ?: return null
        val rows = LongArray(64)
        for (table in 0 until 64) {
            if ((valid and (1L shl table)) != 0L) {
                rows[table] = c.u32() ?: return null
                if (rows[table] > 2_000_000L) return null
            }
        }
        val tableOffsets = LongArray(64)
        val tableSizes = LongArray(64)
        var position = c.position.toLong()
        val streamLimit = streamEndLong - start
        for (table in 0 until 64) {
            if (rows[table] == 0L) continue
            val rowSize = rowSize(table, rows, heapSizes) ?: return null
            tableOffsets[table] = position
            tableSizes[table] = rowSize
            val total = rowSize * rows[table]
            if (total < 0L || position + total > streamLimit) return null
            position += total
        }
        return TablesInfo(rows, tableOffsets, tableSizes, heapSizes, streamEndLong.toInt())
    }

    private fun rowSize(table: Int, r: LongArray, heaps: Int): Long? {
        val s = if ((heaps and 0x01) != 0) 4L else 2L
        val g = if ((heaps and 0x02) != 0) 4L else 2L
        val b = if ((heaps and 0x04) != 0) 4L else 2L
        fun ti(id: Int) = if (r[id] < 65536L) 2L else 4L
        fun coded(bits: Int, vararg ids: Int): Long {
            val max = ids.maxOfOrNull { r[it] } ?: 0L
            return if (max < (1L shl (16 - bits))) 2L else 4L
        }
        val tdor = coded(2, 2, 1, 27)
        val hasConstant = coded(2, 4, 8, 23)
        val hasCustom = coded(5, 6, 4, 1, 2, 8, 9, 10, 0, 14, 23, 20, 17, 26, 27, 32, 35, 38, 39, 40, 42, 44, 43)
        val hasMarshal = coded(1, 4, 8)
        val hasSecurity = coded(2, 2, 6, 32)
        val memberRefParent = coded(3, 2, 1, 26, 6, 27)
        val hasSemantics = coded(1, 20, 23)
        val methodDefOrRef = coded(1, 6, 10)
        val memberForwarded = coded(1, 4, 6)
        val implementation = coded(2, 38, 35, 39)
        val customAttributeType = coded(3, 6, 10)
        val resolutionScope = coded(2, 0, 26, 35, 1)
        val typeOrMethod = coded(1, 2, 6)
        return when (table) {
            0 -> 2L + s + 3L * g
            1 -> resolutionScope + 2L * s
            2 -> 4L + 2L * s + tdor + ti(4) + ti(6)
            3 -> ti(4)
            4 -> 2L + s + b
            5 -> ti(6)
            6 -> 8L + s + b + ti(8)
            7 -> ti(8)
            8 -> 4L + s
            9 -> ti(2) + tdor
            10 -> memberRefParent + s + b
            11 -> 2L + hasConstant + b
            12 -> hasCustom + customAttributeType + b
            13 -> hasMarshal + b
            14 -> 2L + hasSecurity + b
            15 -> 6L + ti(2)
            16 -> 4L + ti(4)
            17 -> b
            18 -> ti(2) + ti(20)
            19 -> ti(20)
            20 -> 2L + s + tdor
            21 -> ti(2) + ti(23)
            22 -> ti(23)
            23 -> 2L + s + b
            24 -> 2L + ti(6) + hasSemantics
            25 -> ti(2) + 2L * methodDefOrRef
            26 -> s
            27 -> b
            28 -> 2L + memberForwarded + s + ti(26)
            29 -> 4L + ti(4)
            30 -> 8L
            31 -> 4L
            32 -> 16L + b + 2L * s
            33 -> 4L
            34 -> 12L
            35 -> 12L + b + 2L * s + b
            36 -> ti(35) + 4L
            37 -> ti(35) + 12L
            38 -> 4L + s + b
            39 -> 8L + 2L * s + implementation
            40 -> 8L + s + implementation
            41 -> 2L * ti(2)
            42 -> 4L + typeOrMethod + s
            43 -> methodDefOrRef + b
            44 -> ti(42) + tdor
            else -> null
        }
    }

    private fun readFirstModuleName(
        bytes: ByteArray, base: Int, info: TablesInfo, streams: StreamInfo,
        rows: LongArray, heapStart: Int, heapEnd: Int
    ): String? {
        if (rows[0] == 0L) return null
        val offset = (base.toLong() + info.tableOffsets[0] + 2L).toInt()
        val width = if ((info.heapSizes and 1) != 0) 4 else 2
        val index = readIndex(bytes, offset, width) ?: return null
        return readHeapString(bytes, heapStart, heapEnd, index)
    }

    private fun readAssemblyIdentity(
        bytes: ByteArray, base: Int, info: TablesInfo, streams: StreamInfo,
        rows: LongArray, heapStart: Int, heapEnd: Int
    ): AssemblyIdentity? {
        if (rows[32] == 0L) return null
        val widthBlob = if ((info.heapSizes and 4) != 0) 4 else 2
        val widthString = if ((info.heapSizes and 1) != 0) 4 else 2
        val offset = base.toLong() + info.tableOffsets[32]
        if (offset > Int.MAX_VALUE) return null
        var p = offset.toInt() + 4
        val major = readU16(bytes, p) ?: return null; p += 2
        val minor = readU16(bytes, p) ?: return null; p += 2
        val build = readU16(bytes, p) ?: return null; p += 2
        val revision = readU16(bytes, p) ?: return null; p += 2
        val flags = readU32(bytes, p) ?: return null; p += 4
        p += widthBlob
        val nameIndex = readIndex(bytes, p, widthString) ?: return null; p += widthString
        val cultureIndex = readIndex(bytes, p, widthString) ?: return null
        val name = readHeapString(bytes, heapStart, heapEnd, nameIndex)
        return AssemblyIdentity(name, "$major.$minor.$build.$revision", flags)
    }

    private fun readTypes(
        bytes: ByteArray, base: Int, info: TablesInfo, streams: StreamInfo,
        rows: LongArray, heapStart: Int, heapEnd: Int
    ): Pair<List<ClrTypeSummary>, String?> {
        if (rows[2] == 0L) return emptyList<ClrTypeSummary>() to null
        val stringWidth = if ((info.heapSizes and 1) != 0) 4 else 2
        val tdorWidth = if (maxOf(rows[2], rows[1], rows[27]) < 16384L) 2 else 4
        val fieldWidth = if (rows[4] < 65536L) 2 else 4
        val methodWidth = if (rows[6] < 65536L) 2 else 4
        val rowSize = 4L + 2L * stringWidth + tdorWidth + fieldWidth + methodWidth
        val start = base.toLong() + info.tableOffsets[2]
        if (start > Int.MAX_VALUE || rowSize != info.tableSizes[2]) return emptyList<ClrTypeSummary>() to "type-table-unavailable"
        val result = ArrayList<ClrTypeSummary>()
        val count = minOf(rows[2], MAX_TYPES.toLong()).toInt()
        for (i in 0 until count) {
            val row = start + i * rowSize
            if (row + rowSize > bytes.size || row > Int.MAX_VALUE) break
            var p = row.toInt()
            val flags = readU32(bytes, p) ?: break; p += 4
            val nameIndex = readIndex(bytes, p, stringWidth) ?: break; p += stringWidth
            val namespaceIndex = readIndex(bytes, p, stringWidth) ?: break
            val name = readHeapString(bytes, heapStart, heapEnd, nameIndex) ?: continue
            val namespace = readHeapString(bytes, heapStart, heapEnd, namespaceIndex) ?: ""
            if (name != "<Module>") result.add(ClrTypeSummary(name, namespace, flags))
        }
        val note = if (rows[2] > MAX_TYPES) "Типы ограничены первыми $MAX_TYPES записями." else null
        return result to note
    }

    private fun readAssemblyReferences(
        bytes: ByteArray, base: Int, info: TablesInfo, streams: StreamInfo,
        rows: LongArray, heapStart: Int, heapEnd: Int
    ): Pair<List<ClrAssemblyReferenceSummary>, String?> {
        if (rows[35] == 0L) return emptyList<ClrAssemblyReferenceSummary>() to null
        val stringWidth = if ((info.heapSizes and 1) != 0) 4 else 2
        val blobWidth = if ((info.heapSizes and 4) != 0) 4 else 2
        val rowSize = 12L + blobWidth + 2L * stringWidth + blobWidth
        val start = base.toLong() + info.tableOffsets[35]
        if (start > Int.MAX_VALUE || rowSize != info.tableSizes[35]) {
            return emptyList<ClrAssemblyReferenceSummary>() to "assembly-reference-table-unavailable"
        }
        val count = minOf(rows[35], MAX_ASSEMBLY_REFS.toLong()).toInt()
        val result = ArrayList<ClrAssemblyReferenceSummary>()
        for (i in 0 until count) {
            val row = start + i * rowSize
            if (row + rowSize > bytes.size || row > Int.MAX_VALUE) break
            var p = row.toInt()
            val major = readU16(bytes, p) ?: break; p += 2
            val minor = readU16(bytes, p) ?: break; p += 2
            val build = readU16(bytes, p) ?: break; p += 2
            val revision = readU16(bytes, p) ?: break; p += 2
            p += 4 + blobWidth
            val nameIndex = readIndex(bytes, p, stringWidth) ?: break; p += stringWidth
            val cultureIndex = readIndex(bytes, p, stringWidth) ?: break
            val name = readHeapString(bytes, heapStart, heapEnd, nameIndex) ?: continue
            val culture = readHeapString(bytes, heapStart, heapEnd, cultureIndex) ?: ""
            result.add(ClrAssemblyReferenceSummary(name, major, minor, build, revision, culture))
        }
        val note = if (rows[35] > MAX_ASSEMBLY_REFS) "AssemblyRef ограничены первыми $MAX_ASSEMBLY_REFS записями." else null
        return result to note
    }

    private fun readHeapString(bytes: ByteArray, start: Int, end: Int, index: Long): String? {
        if (index == 0L) return ""
        if (index < 0L || index > Int.MAX_VALUE) return null
        val offset = start.toLong() + index
        if (offset < start || offset >= end || end > bytes.size || offset > Int.MAX_VALUE) return null
        val from = offset.toInt()
        val limit = minOf(end, from + MAX_HEAP_STRING_BYTES)
        var to = from
        while (to < limit && bytes[to].toInt() != 0) to++
        if (to == limit) return null
        return String(bytes, from, to - from, Charsets.UTF_8)
    }

    private fun emptySummary(runtime: String?, flags: Long?, status: String, note: String? = null) =
        ClrAssemblySummary(null, null, null, runtime, flags, 0, emptyList(), emptyList(), status, note)

    private fun readIndex(bytes: ByteArray, offset: Int, width: Int): Long? =
        if (width == 2) readU16(bytes, offset)?.toLong() else readU32(bytes, offset)

    private fun readU16(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset.toLong() + 2 > bytes.size) return null
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset.toLong() + 4 > bytes.size) return null
        return (bytes[offset].toInt() and 0xff).toLong() or
            ((bytes[offset + 1].toInt() and 0xff).toLong() shl 8) or
            ((bytes[offset + 2].toInt() and 0xff).toLong() shl 16) or
            ((bytes[offset + 3].toInt() and 0xff).toLong() shl 24)
    }

    private class Cursor(private val bytes: ByteArray, start: Int, private val end: Int) {
        var position: Int = start
            private set

        fun u8(): Int? {
            if (position < 0 || position >= end || position >= bytes.size) return null
            return bytes[position++].toInt() and 0xff
        }
        fun u16(): Int? {
            val a = u8() ?: return null
            val b = u8() ?: return null
            return a or (b shl 8)
        }
        fun u32(): Long? {
            val a = u8()?.toLong() ?: return null
            val b = u8()?.toLong() ?: return null
            val c = u8()?.toLong() ?: return null
            val d = u8()?.toLong() ?: return null
            return a or (b shl 8) or (c shl 16) or (d shl 24)
        }
        fun u64(): Long? {
            val low = u32() ?: return null
            val high = u32() ?: return null
            return if (high <= 0x7fffffffL) low or (high shl 32) else null
        }
        fun skip(count: Int): Boolean {
            if (count < 0 || position.toLong() + count > end) return false
            position += count
            return true
        }
        fun cString(maximum: Int): String? {
            val start = position
            val limit = minOf(end, position + maximum)
            while (position < limit && bytes[position].toInt() != 0) position++
            if (position >= limit) return null
            val result = String(bytes, start, position - start, Charsets.US_ASCII)
            position++
            return result
        }
        fun align4() {
            val aligned = (position + 3) and -4
            position = minOf(aligned, end)
        }
    }
}
