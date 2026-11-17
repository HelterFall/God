package com.ramen.god

internal data class PeInfo(\n    val machine: String,\n    val managed: Boolean,\n    val imports: List<String>,\n    val note: String? = null,\n    val clrMetadataOffset: Int? = null,\n    val clrMetadataSize: Long? = null,\n    val clrRuntimeVersion: String? = null,\n    val clrFlags: Long? = null\n)

internal object PeParser {
    private data class Section(val virtualSize: Long, val virtualAddress: Long, val rawSize: Long, val rawPointer: Long)

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
        val directory = when (u16(bytes, optional)) {
            0x10b -> optional + 96
            0x20b -> optional + 112
            else -> return PeInfo(machine, false, emptyList(), "unknown optional header")
        }
        if (!has(bytes, directory, 15 * 8)) return PeInfo(machine, false, emptyList(), "data directories truncated")

        val importRva = u32(bytes, directory + 8)
        val clrRva = u32(bytes, directory + 14 * 8)
        val clrDirectorySize = u32(bytes, directory + 14 * 8 + 4)
        val sectionsStart = optional + optionalSize
        val sections = mutableListOf<Section>()
        for (index in 0 until minOf(sectionCount, 96)) {
            val offset = sectionsStart + index * 40
            if (!has(bytes, offset, 40)) break
            sections.add(Section(
                u32(bytes, offset + 8), u32(bytes, offset + 12),
                u32(bytes, offset + 16), u32(bytes, offset + 20)
            ))
        }

        val imports = if (importRva == 0L) emptyList() else parseImports(bytes, importRva, sections)
        val note = if (importRva != 0L && imports.isEmpty()) "import table outside read window or undecodable" else null
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
        return PeInfo(machine, clrRva != 0L, imports, note, metadataOffset, metadataSize, runtimeVersion, clrFlags)
    }

    private fun parseImports(bytes: ByteArray, rva: Long, sections: List<Section>): List<String> {
        val start = rvaToOffset(rva, sections) ?: return emptyList()
        val result = linkedSetOf<String>()
        var cursor = start
        repeat(512) {
            if (!has(bytes, cursor, 20)) return result.toList()
            val fields = (0..4).map { u32(bytes, cursor + it * 4) }
            if (fields.all { it == 0L }) return result.toList()
            val nameOffset = rvaToOffset(fields[3], sections)
            if (nameOffset != null) {
                val name = readCString(bytes, nameOffset, 260)
                if (name.isNotBlank()) result.add(name)
            }
            cursor += 20
        }
        return result.toList()
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
}
