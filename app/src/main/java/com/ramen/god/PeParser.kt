package com.ramen.god

internal data class PeImportedSymbol(
    val dllName: String,
    val name: String? = null,
    val ordinal: Int? = null,
    val hint: Int? = null
)

internal data class PeInfo(
    val machine: String,
    val managed: Boolean,
    val imports: List<String>,
    val note: String? = null,
    val clrMetadataOffset: Int? = null,
    val clrMetadataSize: Long? = null,
    val clrRuntimeVersion: String? = null,
    val clrFlags: Long? = null,
    val importedSymbols: List<PeImportedSymbol> = emptyList()
)

internal object PeParser {
    private data class Section(val virtualSize: Long, val virtualAddress: Long, val rawSize: Long, val rawPointer: Long)
    private data class ImportParseResult(
        val dllNames: List<String>,
        val symbols: List<PeImportedSymbol>,
        val note: String? = null
    )

    private const val MAX_SECTIONS = 96
    private const val MAX_IMPORT_DESCRIPTORS = 512
    private const val MAX_THUNKS_PER_DLL = 4096
    private const val MAX_DLL_NAME_BYTES = 260
    private const val MAX_FUNCTION_NAME_BYTES = 512

    fun parse(bytes: ByteArray): PeInfo? {
        if (bytes.size < 64 || u8(bytes, 0) != 0x4d || u8(bytes, 1) != 0x5a) return null
        val peLong = u32(bytes, 0x3c)
        if (peLong > Int.MAX_VALUE) return null
        val pe = peLong.toInt()
        if (!has(bytes, pe, 24)) return null
        if (u8(bytes, pe) != 0x50 || u8(bytes, pe + 1) != 0x45 || u8(bytes, pe + 2) != 0 || u8(bytes, pe + 3) != 0) return null

        val machineCode = u16(bytes, pe + 4)
        val sectionCount = u16(bytes, pe + 6)
        val optionalSize = u16(bytes, pe + 20)
        val optional = pe + 24
        if (!has(bytes, optional, optionalSize) || optionalSize < 96) return null

        val machine = when (machineCode) {
            0x014c -> "x86"
            0x8664 -> "x86-64"
            0xaa64 -> "ARM64"
            else -> "unknown (0x" + machineCode.toString(16) + ")"
        }
        val is64Bit = when (u16(bytes, optional)) {
            0x10b -> false
            0x20b -> true
            else -> return PeInfo(machine, false, emptyList(), "unknown optional header")
        }
        val directory = if (is64Bit) optional + 112 else optional + 96
        if (!has(bytes, directory, 15 * 8)) return PeInfo(machine, false, emptyList(), "data directories truncated")

        val importRva = u32(bytes, directory + 8)
        val clrRva = u32(bytes, directory + 14 * 8)
        val clrDirectorySize = u32(bytes, directory + 14 * 8 + 4)
        val sectionsStart = optional + optionalSize
        val sections = mutableListOf<Section>()
        for (index in 0 until minOf(sectionCount, MAX_SECTIONS)) {
            val offset = sectionsStart + index * 40
            if (!has(bytes, offset, 40)) break
            sections.add(Section(
                u32(bytes, offset + 8), u32(bytes, offset + 12),
                u32(bytes, offset + 16), u32(bytes, offset + 20)
            ))
        }

        val importData = if (importRva == 0L) {
            ImportParseResult(emptyList(), emptyList())
        } else {
            parseImports(bytes, importRva, sections, is64Bit)
        }
        val note = when {
            importData.note != null -> importData.note
            importRva != 0L && importData.dllNames.isEmpty() -> "import table outside read window or undecodable"
            else -> null
        }

        var metadataOffset: Int? = null
        var metadataSize: Long? = null
        var runtimeVersion: String? = null
        var clrFlags: Long? = null
        if (clrRva != 0L && clrDirectorySize >= 16L) {
            val clrOffset = rvaToOffset(clrRva, sections)
            if (clrOffset != null && has(bytes, clrOffset, 16)) {
                val runtimeMajor = u16(bytes, clrOffset + 4)
                val runtimeMinor = u16(bytes, clrOffset + 6)
                runtimeVersion = "$runtimeMajor.$runtimeMinor"
                val metadataRva = u32(bytes, clrOffset + 8)
                metadataSize = u32(bytes, clrOffset + 12)
                metadataOffset = rvaToOffset(metadataRva, sections)
                if (has(bytes, clrOffset, 20)) clrFlags = u32(bytes, clrOffset + 16)
            }
        }
        return PeInfo(
            machine = machine,
            managed = clrRva != 0L,
            imports = importData.dllNames,
            note = note,
            clrMetadataOffset = metadataOffset,
            clrMetadataSize = metadataSize,
            clrRuntimeVersion = runtimeVersion,
            clrFlags = clrFlags,
            importedSymbols = importData.symbols
        )
    }

    private fun parseImports(
        bytes: ByteArray,
        rva: Long,
        sections: List<Section>,
        is64Bit: Boolean
    ): ImportParseResult {
        val start = rvaToOffset(rva, sections)
            ?: return ImportParseResult(emptyList(), emptyList(), "import directory RVA is outside readable sections")

        val dllNames = linkedSetOf<String>()
        val symbols = mutableListOf<PeImportedSymbol>()
        val notes = mutableListOf<String>()
        var descriptorTerminated = false

        for (descriptorIndex in 0 until MAX_IMPORT_DESCRIPTORS) {
            val descriptorOffsetLong = start.toLong() + descriptorIndex.toLong() * 20L
            if (descriptorOffsetLong > Int.MAX_VALUE || !has(bytes, descriptorOffsetLong.toInt(), 20)) {
                notes.add("import descriptor table is truncated")
                break
            }
            val descriptorOffset = descriptorOffsetLong.toInt()
            val fields = (0..4).map { u32(bytes, descriptorOffset + it * 4) }
            if (fields.all { it == 0L }) {
                descriptorTerminated = true
                break
            }

            val originalFirstThunkRva = fields[0]
            val nameRva = fields[3]
            val firstThunkRva = fields[4]
            val nameOffset = rvaToOffset(nameRva, sections)
            val dllName = nameOffset?.let { readCString(bytes, it, MAX_DLL_NAME_BYTES) }.orEmpty()
            if (dllName.isBlank()) {
                notes.add("import descriptor has an unreadable DLL name")
                continue
            }
            dllNames.add(dllName)

            // OriginalFirstThunk is the lookup table. Some binaries leave it
            // empty; in that case the on-disk FirstThunk is the fallback.
            val lookupTableRva = if (originalFirstThunkRva != 0L) originalFirstThunkRva else firstThunkRva
            val thunkStart = rvaToOffset(lookupTableRva, sections)
            if (thunkStart == null) {
                notes.add("thunk table for $dllName is outside readable sections")
                continue
            }

            val pointerSize = if (is64Bit) 8 else 4
            val ordinalFlag = if (is64Bit) Long.MIN_VALUE else 0x80000000L
            var thunkTerminated = false
            for (thunkIndex in 0 until MAX_THUNKS_PER_DLL) {
                val thunkOffsetLong = thunkStart.toLong() + thunkIndex.toLong() * pointerSize
                if (thunkOffsetLong > Int.MAX_VALUE || !has(bytes, thunkOffsetLong.toInt(), pointerSize)) {
                    notes.add("thunk table for $dllName is truncated")
                    break
                }
                val thunkOffset = thunkOffsetLong.toInt()
                val thunkValue = if (is64Bit) u64(bytes, thunkOffset) else u32(bytes, thunkOffset)
                if (thunkValue == 0L) {
                    thunkTerminated = true
                    break
                }

                if ((thunkValue and ordinalFlag) != 0L) {
                    symbols.add(
                        PeImportedSymbol(
                            dllName = dllName,
                            ordinal = (thunkValue and 0xffffL).toInt()
                        )
                    )
                    continue
                }

                // IMAGE_THUNK_DATA contains an RVA to IMAGE_IMPORT_BY_NAME
                // when the high-bit ordinal flag is not set.
                if (thunkValue > 0xffffffffL) {
                    notes.add("invalid import-by-name RVA in thunk table for $dllName")
                    continue
                }
                val importByNameOffset = rvaToOffset(thunkValue, sections)
                if (importByNameOffset == null || !has(bytes, importByNameOffset, 2)) {
                    notes.add("import-by-name record for $dllName is outside readable sections")
                    continue
                }
                val hint = u16(bytes, importByNameOffset)
                val functionName = readCString(bytes, importByNameOffset + 2, MAX_FUNCTION_NAME_BYTES)
                if (functionName.isNotBlank()) {
                    symbols.add(PeImportedSymbol(dllName = dllName, name = functionName, hint = hint))
                } else {
                    notes.add("import-by-name record for $dllName has an empty name")
                }
            }
            if (!thunkTerminated) {
                notes.add("thunk table for $dllName reached the $MAX_THUNKS_PER_DLL entry limit")
            }
        }
        if (!descriptorTerminated && notes.none { it.contains("descriptor") || it.contains("truncated") }) {
            notes.add("import descriptor table reached the $MAX_IMPORT_DESCRIPTORS entry limit")
        }
        return ImportParseResult(dllNames.toList(), symbols, notes.distinct().take(16).joinToString("; ").ifBlank { null })
    }

    private fun rvaToOffset(rva: Long, sections: List<Section>): Int? {
        for (section in sections) {
            val span = maxOf(section.virtualSize, section.rawSize)
            if (rva >= section.virtualAddress && rva < section.virtualAddress + span) {
                val delta = rva - section.virtualAddress
                if (delta >= section.rawSize) return null
                val offset = section.rawPointer + delta
                if (offset > Int.MAX_VALUE) return null
                return offset.toInt()
            }
        }
        return null
    }

    private fun readCString(bytes: ByteArray, offset: Int, maximum: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        val limit = minOf(bytes.size, offset + maximum)
        var end = offset
        while (end < limit && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.ISO_8859_1)
    }

    private fun has(bytes: ByteArray, offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size.toLong()

    private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 0xff
    private fun u16(bytes: ByteArray, offset: Int) = u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long =
        u8(bytes, offset).toLong() or (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or (u8(bytes, offset + 3).toLong() shl 24)

    private fun u64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = value or (u8(bytes, offset + index).toLong() shl (index * 8))
        }
        return value
    }
}
