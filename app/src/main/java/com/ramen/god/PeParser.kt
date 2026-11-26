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
    private data class RvaMapping(val offset: Int, val availableBytes: Int)
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

    fun parse(
        bytes: ByteArray,
        shouldCancel: () -> Boolean = { false }
    ): PeInfo? {
        if (shouldCancel()) throw ScanCancelledException()
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
        val numberOfDirectoriesOffset = if (is64Bit) 108 else 92
        val directoryOffset = if (is64Bit) 112 else 96
        if (optionalSize < numberOfDirectoriesOffset + 4) {
            return PeInfo(machine, false, emptyList(), "optional header is truncated")
        }
        val declaredDirectoryCount = u32(bytes, optional + numberOfDirectoriesOffset)
        val availableDirectoryCount = (optionalSize - directoryOffset).coerceAtLeast(0) / 8
        val readableDirectoryCount = minOf(declaredDirectoryCount, availableDirectoryCount.toLong())
        val directoryNote = if (declaredDirectoryCount > availableDirectoryCount) {
            "data directories truncated"
        } else null
        val directory = optional + directoryOffset

        // Never read a data-directory slot unless it is both declared by the
        // image and contained inside SizeOfOptionalHeader.
        val importRva = if (readableDirectoryCount > 1L) u32(bytes, directory + 8) else 0L
        val importDirectorySize = if (readableDirectoryCount > 1L) u32(bytes, directory + 12) else 0L
        val clrRva = if (readableDirectoryCount > 14L) u32(bytes, directory + 14 * 8) else 0L
        val clrDirectorySize = if (readableDirectoryCount > 14L) u32(bytes, directory + 14 * 8 + 4) else 0L
        val sectionsStart = optional + optionalSize
        val sections = mutableListOf<Section>()
        val sectionsToRead = minOf(sectionCount, MAX_SECTIONS)
        var sectionTableTruncated = false
        for (index in 0 until sectionsToRead) {
            if (shouldCancel()) throw ScanCancelledException()
            val offset = sectionsStart + index * 40
            if (!has(bytes, offset, 40)) {
                sectionTableTruncated = true
                break
            }
            sections.add(Section(
                u32(bytes, offset + 8), u32(bytes, offset + 12),
                u32(bytes, offset + 16), u32(bytes, offset + 20)
            ))
        }
        val sectionNote = when {
            sectionTableTruncated -> "section table is truncated"
            sectionCount > MAX_SECTIONS -> "section count exceeds parser limit ($MAX_SECTIONS)"
            else -> null
        }

        val importData = if (importRva == 0L) {
            ImportParseResult(emptyList(), emptyList())
        } else {
            parseImports(bytes, importRva, importDirectorySize, sections, is64Bit, shouldCancel)
        }
        val importNote = when {
            importData.note != null -> importData.note
            importRva != 0L && importData.dllNames.isEmpty() -> "import table outside read window or undecodable"
            else -> null
        }
        var metadataOffset: Int? = null
        var metadataSize: Long? = null
        var runtimeVersion: String? = null
        var clrFlags: Long? = null
        var clrNote: String? = null
        var clrHeaderParsed = false
        if (clrRva != 0L) {
            val clrMapping = rvaMapping(clrRva, sections, bytes)
            if (clrDirectorySize < 72L || clrMapping == null || clrMapping.availableBytes < 72) {
                clrNote = "CLR header is outside readable section data or truncated"
            } else {
                val clrOffset = clrMapping.offset
                val declaredClrHeaderSize = u32(bytes, clrOffset)
                if (declaredClrHeaderSize < 72L ||
                    declaredClrHeaderSize > clrDirectorySize ||
                    declaredClrHeaderSize > clrMapping.availableBytes.toLong()) {
                    clrNote = "CLR header size is invalid or truncated"
                } else {
                    clrHeaderParsed = true
                    val runtimeMajor = u16(bytes, clrOffset + 4)
                    val runtimeMinor = u16(bytes, clrOffset + 6)
                    runtimeVersion = "$runtimeMajor.$runtimeMinor"
                    val metadataRva = u32(bytes, clrOffset + 8)
                    val declaredMetadataSize = u32(bytes, clrOffset + 12)
                    clrFlags = u32(bytes, clrOffset + 16)
                    metadataSize = declaredMetadataSize
                    val metadataMapping = if (declaredMetadataSize > 0L) {
                        rvaMapping(metadataRva, sections, bytes)
                    } else null
                    if (metadataMapping != null &&
                        declaredMetadataSize <= metadataMapping.availableBytes.toLong()) {
                        metadataOffset = metadataMapping.offset
                    } else {
                        clrNote = "CLR metadata directory extends beyond readable section data"
                    }
                }
            }
        }
        val note = listOfNotNull(directoryNote, sectionNote, importNote, clrNote)
            .distinct().joinToString("; ").ifBlank { null }
        return PeInfo(
            machine = machine,
            managed = clrHeaderParsed,
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
        directorySize: Long,
        sections: List<Section>,
        is64Bit: Boolean,
        shouldCancel: () -> Boolean
    ): ImportParseResult {
        if (shouldCancel()) throw ScanCancelledException()
        if (directorySize <= 0L) {
            return ImportParseResult(emptyList(), emptyList(), "import directory has an empty declared size")
        }
        val mapping = rvaMapping(rva, sections, bytes)
            ?: return ImportParseResult(emptyList(), emptyList(), "import directory RVA is outside readable sections")
        val readableDirectoryBytes = minOf(directorySize, mapping.availableBytes.toLong())
        val readableDescriptorCount = (readableDirectoryBytes / 20L)
            .coerceAtMost(MAX_IMPORT_DESCRIPTORS.toLong()).toInt()
        if (readableDescriptorCount == 0) {
            return ImportParseResult(
                emptyList(), emptyList(),
                if (readableDirectoryBytes < directorySize) "import descriptor table is truncated by section/read window"
                else "import directory is smaller than one descriptor"
            )
        }
        val start = mapping.offset

        val dllNames = linkedSetOf<String>()
        val symbols = mutableListOf<PeImportedSymbol>()
        val notes = mutableListOf<String>()
        var descriptorTerminated = false
        var descriptorTableTruncated = false

        for (descriptorIndex in 0 until readableDescriptorCount) {
            if (shouldCancel()) throw ScanCancelledException()
            val descriptorOffsetLong = start.toLong() + descriptorIndex.toLong() * 20L
            if (descriptorOffsetLong > Int.MAX_VALUE || !has(bytes, descriptorOffsetLong.toInt(), 20)) {
                descriptorTableTruncated = true
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
            val nameMapping = rvaMapping(nameRva, sections, bytes)
            val dllName = nameMapping?.let {
                readCString(bytes, it.offset, minOf(MAX_DLL_NAME_BYTES, it.availableBytes))
            }.orEmpty()
            if (dllName.isBlank()) {
                notes.add("import descriptor has an unreadable DLL name")
                continue
            }
            dllNames.add(dllName)

            // OriginalFirstThunk is the lookup table. Some binaries leave it
            // empty; in that case the on-disk FirstThunk is the fallback.
            val lookupTableRva = if (originalFirstThunkRva != 0L) originalFirstThunkRva else firstThunkRva
            val thunkMapping = rvaMapping(lookupTableRva, sections, bytes)
            if (thunkMapping == null) {
                notes.add("thunk table for $dllName is outside readable sections")
                continue
            }
            val thunkStart = thunkMapping.offset

            val pointerSize = if (is64Bit) 8 else 4
            val ordinalFlag = if (is64Bit) Long.MIN_VALUE else 0x80000000L
            var thunkTerminated = false
            var thunkTableTruncated = false
            for (thunkIndex in 0 until MAX_THUNKS_PER_DLL) {
                if (shouldCancel()) throw ScanCancelledException()
                val thunkOffsetLong = thunkStart.toLong() + thunkIndex.toLong() * pointerSize
                val thunkRelativeOffset = thunkIndex.toLong() * pointerSize
                if (thunkOffsetLong > Int.MAX_VALUE ||
                    thunkRelativeOffset + pointerSize > thunkMapping.availableBytes.toLong() ||
                    !has(bytes, thunkOffsetLong.toInt(), pointerSize)) {
                    thunkTableTruncated = true
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
                val importByNameMapping = rvaMapping(thunkValue, sections, bytes)
                if (importByNameMapping == null || importByNameMapping.availableBytes < 2) {
                    notes.add("import-by-name record for $dllName is outside readable sections")
                    continue
                }
                val importByNameOffset = importByNameMapping.offset
                val hint = u16(bytes, importByNameOffset)
                val functionName = readCString(
                    bytes, importByNameOffset + 2,
                    minOf(MAX_FUNCTION_NAME_BYTES, importByNameMapping.availableBytes - 2)
                )
                if (functionName.isNotBlank()) {
                    symbols.add(PeImportedSymbol(dllName = dllName, name = functionName, hint = hint))
                } else {
                    notes.add("import-by-name record for $dllName has an empty name")
                }
            }
            if (!thunkTerminated && !thunkTableTruncated) {
                notes.add("thunk table for $dllName reached the $MAX_THUNKS_PER_DLL entry limit")
            }
        }
        if (!descriptorTerminated && !descriptorTableTruncated) {
            when {
                readableDescriptorCount == MAX_IMPORT_DESCRIPTORS -> {
                    notes.add("import descriptor table reached the $MAX_IMPORT_DESCRIPTORS entry limit")
                }
                readableDirectoryBytes < directorySize -> {
                    notes.add("import descriptor table is truncated by section/read window")
                }
                directorySize % 20L != 0L -> {
                    notes.add("import descriptor table has an incomplete trailing record")
                }
                else -> notes.add("import descriptor table has no terminator within declared directory size")
            }
        }
        return ImportParseResult(dllNames.toList(), symbols, notes.distinct().take(16).joinToString("; ").ifBlank { null })
    }

    /**
     * Maps an RVA to bytes that are both in a section's raw data and inside the
     * supplied read window. Consumers must not continue linearly past this span.
     */
    private fun rvaMapping(rva: Long, sections: List<Section>, bytes: ByteArray): RvaMapping? {
        // Overlapping virtual ranges make RVA ownership ambiguous. Do not let
        // section order silently decide which raw bytes are interpreted.
        val matches = sections.filter { section ->
            val span = maxOf(section.virtualSize, section.rawSize)
            rva >= section.virtualAddress && rva - section.virtualAddress < span
        }
        if (matches.size != 1) return null
        val section = matches.single()
        val delta = rva - section.virtualAddress
        if (delta >= section.rawSize) return null
        val rawOffset = section.rawPointer + delta
        if (rawOffset < 0L || rawOffset >= bytes.size.toLong() || rawOffset > Int.MAX_VALUE) return null
        val remainingInSection = section.rawSize - delta
        val remainingInFile = bytes.size.toLong() - rawOffset
        val available = minOf(remainingInSection, remainingInFile)
        if (available <= 0L) return null
        return RvaMapping(rawOffset.toInt(), available.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun readCString(bytes: ByteArray, offset: Int, maximum: Int): String {
        if (offset < 0 || offset >= bytes.size || maximum <= 0) return ""
        val limit = minOf(bytes.size.toLong(), offset.toLong() + maximum.toLong()).toInt()
        var end = offset
        while (end < limit && bytes[end].toInt() != 0) end++
        if (end == limit) return ""
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
