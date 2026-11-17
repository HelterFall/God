package com.ramen.god

internal data class ClrMethodSignature(
    val returnType: String,
    val parameterTypes: List<String>,
    val callingConvention: String,
    val hasThis: Boolean,
    val genericParameterCount: Int,
    val status: String = "parsed"
) {
    fun display(methodName: String): String {
        val prefix = if (hasThis) "instance " else ""
        val generic = if (genericParameterCount > 0) "<$genericParameterCount>" else ""
        return prefix + returnType + " " + methodName + generic +
            parameterTypes.joinToString(prefix = "(", postfix = ")")
    }
}

internal data class ClrFieldSignature(
    val fieldType: String,
    val status: String = "parsed"
)

/** Bounded decoder for common ECMA-335 field and method signature element types. */
internal object ClrSignatureParser {
    private const val MAX_PARAMETERS = 256
    private const val MAX_GENERIC_ARGUMENTS = 64
    private const val MAX_ARRAY_RANK = 32
    private const val MAX_DEPTH = 12

    fun parseField(blob: ByteArray): ClrFieldSignature? {
        val reader = Reader(blob)
        if (reader.u8() != 0x06) return null // FIELD calling convention
        val fieldType = readType(reader, allowVoid = false, depth = 0) ?: return null
        if (!reader.finished()) return null
        return ClrFieldSignature(fieldType)
    }

    fun parseMethod(blob: ByteArray): ClrMethodSignature? {
        val reader = Reader(blob)
        val callConv = reader.u8() ?: return null
        val convention = callConv and 0x0f
        if (convention !in setOf(0, 1, 2, 3, 4, 5, 9, 11)) return null
        val genericCount = if ((callConv and 0x10) != 0) {
            reader.compressedUInt()?.takeIf { it <= MAX_GENERIC_ARGUMENTS.toLong() }?.toInt() ?: return null
        } else 0
        val parameterCount = reader.compressedUInt()
            ?.takeIf { it <= MAX_PARAMETERS.toLong() }?.toInt() ?: return null
        val returnType = readType(reader, allowVoid = true, depth = 0) ?: return null
        val parameters = ArrayList<String>(parameterCount)
        var sentinelSeen = false
        repeat(parameterCount) {
            if (reader.peek() == 0x41) {
                if (sentinelSeen) return null
                reader.u8()
                sentinelSeen = true
            }
            val parameterType = readType(reader, allowVoid = false, depth = 0) ?: return null
            parameters.add(parameterType)
        }
        if (!reader.finished()) return null
        val label = when (convention) {
            0 -> "default"
            1 -> "cdecl"
            2 -> "stdcall"
            3 -> "thiscall"
            4 -> "fastcall"
            5 -> "vararg"
            9 -> "unmanaged"
            11 -> "nativevararg"
            else -> "unknown"
        }
        return ClrMethodSignature(
            returnType, parameters, label, (callConv and 0x20) != 0, genericCount
        )
    }

    private fun readType(reader: Reader, allowVoid: Boolean, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        while (reader.peek() == 0x1f || reader.peek() == 0x20) {
            reader.u8()
            if (reader.compressedUInt() == null) return null // custom modifier TypeDefOrRef
        }
        if (reader.peek() == 0x45) reader.u8() // PINNED
        return when (val element = reader.u8() ?: return null) {
            0x01 -> if (allowVoid) "Void" else null
            0x02 -> "Boolean"
            0x03 -> "Char"
            0x04 -> "SByte"
            0x05 -> "Byte"
            0x06 -> "Int16"
            0x07 -> "UInt16"
            0x08 -> "Int32"
            0x09 -> "UInt32"
            0x0a -> "Int64"
            0x0b -> "UInt64"
            0x0c -> "Single"
            0x0d -> "Double"
            0x0e -> "String"
            0x0f -> (readType(reader, true, depth + 1) ?: return null) + "*"
            0x10 -> (readType(reader, false, depth + 1) ?: return null) + "&"
            0x11 -> "valuetype " + typeToken(reader)
            0x12 -> "class " + typeToken(reader)
            0x13 -> "!" + (reader.compressedUInt() ?: return null)
            0x14 -> {
                val item = readType(reader, false, depth + 1) ?: return null
                val rank = reader.compressedUInt()?.takeIf { it <= MAX_ARRAY_RANK } ?: return null
                val sizes = reader.compressedUInt()?.takeIf { it <= MAX_ARRAY_RANK } ?: return null
                repeat(sizes.toInt()) { if (reader.compressedUInt() == null) return null }
                val lowerBounds = reader.compressedUInt()?.takeIf { it <= MAX_ARRAY_RANK } ?: return null
                repeat(lowerBounds.toInt()) { if (reader.compressedSigned() == null) return null }
                item + "[" + if (rank == 0L) "" else ",".repeat((rank - 1).toInt()) + "]"
            }
            0x15 -> {
                val kind = reader.u8() ?: return null
                if (kind != 0x11 && kind != 0x12) return null
                val name = if (kind == 0x11) "valuetype " else "class "
                val token = typeToken(reader)
                val count = reader.compressedUInt()?.takeIf { it <= MAX_GENERIC_ARGUMENTS } ?: return null
                val args = ArrayList<String>(count.toInt())
                repeat(count.toInt()) { args.add(readType(reader, false, depth + 1) ?: return null) }
                name + token + args.joinToString(prefix = "<", postfix = ">")
            }
            0x16 -> "TypedReference"
            0x18 -> "IntPtr"
            0x19 -> "UIntPtr"
            0x1b -> return null // function-pointer signatures need a nested calling-convention parser
            0x1c -> "Object"
            0x1d -> (readType(reader, false, depth + 1) ?: return null) + "[]"
            0x1e -> "!!" + (reader.compressedUInt() ?: return null)
            else -> {
                // Preserve unknown element-type markers as unsupported instead of guessing.
                if (element == 0x00) null else null
            }
        }
    }

    private fun typeToken(reader: Reader): String {
        val coded = reader.compressedUInt() ?: return "?"
        val row = coded shr 2
        val table = when ((coded and 0x03).toInt()) {
            0 -> "TypeDef"
            1 -> "TypeRef"
            2 -> "TypeSpec"
            else -> "InvalidTypeToken"
        }
        return "$table#$row"
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0
        fun finished() = offset == bytes.size
        fun peek(): Int? = if (offset < bytes.size) bytes[offset].toInt() and 0xff else null
        fun u8(): Int? {
            if (offset >= bytes.size) return null
            return bytes[offset++].toInt() and 0xff
        }
        fun compressedUInt(): Long? {
            val first = u8() ?: return null
            return when {
                (first and 0x80) == 0 -> first.toLong()
                (first and 0xc0) == 0x80 -> {
                    val second = u8() ?: return null
                    (((first and 0x3f) shl 8) or second).toLong()
                }
                (first and 0xe0) == 0xc0 -> {
                    val b1 = u8() ?: return null
                    val b2 = u8() ?: return null
                    val b3 = u8() ?: return null
                    ((first and 0x1f).toLong() shl 24) or
                        (b1.toLong() shl 16) or (b2.toLong() shl 8) or b3.toLong()
                }
                else -> null
            }
        }
        fun compressedSigned(): Long? {
            val first = peek() ?: return null
            val width = when {
                (first and 0x80) == 0 -> 1
                (first and 0xc0) == 0x80 -> 2
                (first and 0xe0) == 0xc0 -> 4
                else -> return null
            }
            val raw = compressedUInt() ?: return null
            val bits = when (width) { 1 -> 7; 2 -> 14; else -> 29 }
            val value = raw ushr 1
            return if ((raw and 1L) != 0L) value - (1L shl (bits - 1)) else value
        }
    }
}
