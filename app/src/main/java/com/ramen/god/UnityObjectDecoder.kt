package com.ramen.god

import java.io.InputStream

internal data class UnityObjectPointerSummary(
    val fileId: Int,
    val pathId: Long
)

internal data class UnityDecodedObjectSummary(
    val pathId: Long,
    val classId: Int,
    val name: String? = null,
    val isActive: Boolean? = null,
    val isEnabled: Boolean? = null,
    val gameObject: UnityObjectPointerSummary? = null,
    val father: UnityObjectPointerSummary? = null,
    val children: List<UnityObjectPointerSummary> = emptyList(),
    val components: List<UnityObjectPointerSummary> = emptyList(),
    val script: UnityObjectPointerSummary? = null
)

internal data class UnityObjectDecodeTarget(
    val objectSummary: UnityObjectSummary,
    val typeTree: UnityTypeTreeSummary
)

internal data class UnityObjectDecodeResult(
    val objects: List<UnityDecodedObjectSummary>,
    val skippedCount: Int,
    val note: String? = null
)

/**
 * Small, bounded TypeTree interpreter for Unity's core scene objects.
 *
 * This intentionally refuses incomplete/oversized TypeTrees. It decodes a
 * useful subset of primitive values, strings, structs, arrays and PPtrs;
 * unknown variable-length nodes are not guessed.
 */
internal object UnityObjectDecoder {
    private const val MAX_OBJECT_BYTES = 256 * 1024
    private const val MAX_OBJECTS_PER_FILE = 128
    private const val MAX_SCAN_DISTANCE = 64L * 1024L * 1024L
    private const val MAX_TREE_NODES = 4096
    private const val MAX_DEPTH = 64
    private const val MAX_ARRAY_ITEMS = 1024
    private const val MAX_STRING_BYTES = 16 * 1024
    private const val ALIGN_BYTES_FLAG = 0x4000

    private data class TreeNode(
        val summary: UnityTypeTreeNodeSummary,
        val children: MutableList<TreeNode> = mutableListOf()
    )

    fun decodePayload(
        payload: ByteArray,
        pathId: Long,
        classId: Int,
        typeTree: UnityTypeTreeSummary,
        littleEndian: Boolean
    ): UnityDecodedObjectSummary? {
        if (!typeTree.completeForDecoding ||
            typeTree.classId != classId ||
            typeTree.decoderNodes.isEmpty() ||
            typeTree.decoderNodes.size > MAX_TREE_NODES ||
            payload.isEmpty() || payload.size > MAX_OBJECT_BYTES) return null

        val root = buildTree(typeTree.decoderNodes) ?: return null
        val cursor = PayloadCursor(payload, littleEndian)
        val decoded = try {
            decodeNode(root, cursor, 0)
        } catch (_: DecodeFailure) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        if (decoded !is Map<*, *>) return null

        val componentValue = findNamed(decoded, "m_Component")
        val childValue = findNamed(decoded, "m_Children")
        return UnityDecodedObjectSummary(
            pathId = pathId,
            classId = classId,
            name = findNamed(decoded, "m_Name") as? String,
            isActive = findNamed(decoded, "m_IsActive").asBoolean(),
            isEnabled = findNamed(decoded, "m_Enabled").asBoolean(),
            gameObject = findNamed(decoded, "m_GameObject").asPointer(),
            father = findNamed(decoded, "m_Father").asPointer(),
            children = collectPointers(childValue).distinct(),
            components = collectPointers(componentValue).distinct(),
            script = findNamed(decoded, "m_Script").asPointer()
        )
    }

    fun readObjects(
        input: InputStream,
        dataOffset: Long,
        fileSize: Long,
        targets: List<UnityObjectDecodeTarget>,
        littleEndian: Boolean
    ): UnityObjectDecodeResult {
        if (dataOffset < 0L || fileSize < dataOffset) {
            return UnityObjectDecodeResult(emptyList(), targets.size, "Некорректные границы data section.")
        }
        val ordered = targets.distinctBy { it.objectSummary.pathId }
            .sortedBy { it.objectSummary.byteStart }
        val limited = ordered.take(MAX_OBJECTS_PER_FILE)
        var skipped = (ordered.size - limited.size).coerceAtLeast(0)
        var position = 0L
        val decoded = ArrayList<UnityDecodedObjectSummary>()
        val buffer = ByteArray(8192)

        for ((index, target) in limited.withIndex()) {
            val objectInfo = target.objectSummary
            if (!target.typeTree.completeForDecoding ||
                objectInfo.byteStart < 0L ||
                objectInfo.byteSize !in 1L..MAX_OBJECT_BYTES.toLong()) {
                skipped++
                continue
            }
            if (dataOffset > Long.MAX_VALUE - objectInfo.byteStart) {
                skipped++
                continue
            }
            val absoluteStart = dataOffset + objectInfo.byteStart
            if (absoluteStart > fileSize || objectInfo.byteSize > fileSize - absoluteStart) {
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
            if (!skipFully(input, absoluteStart - position, buffer)) {
                skipped += limited.size - index
                return UnityObjectDecodeResult(decoded, skipped, "Поток закончился до scene object.")
            }
            position = absoluteStart

            val payload = readExactly(input, objectInfo.byteSize.toInt()) ?: run {
                skipped += limited.size - index
                return UnityObjectDecodeResult(decoded, skipped, "Scene object обрезан.")
            }
            position += payload.size
            val result = decodePayload(payload, objectInfo.pathId, objectInfo.classId ?: target.typeTree.classId,
                target.typeTree, littleEndian)
            if (result == null) {
                skipped++
            } else {
                decoded.add(result)
            }
        }

        val note = when {
            skipped == 0 -> null
            decoded.isEmpty() -> "GameObject/Transform/MonoBehaviour не удалось декодировать в установленных лимитах."
            else -> "Декодировано ${decoded.size} scene objects; пропущено $skipped по лимитам или из-за неподдерживаемых полей."
        }
        return UnityObjectDecodeResult(decoded, skipped, note)
    }

    private fun buildTree(nodes: List<UnityTypeTreeNodeSummary>): TreeNode? {
        if (nodes.isEmpty() || nodes.size > MAX_TREE_NODES || nodes[0].level != 0) return null
        val stack = mutableListOf<TreeNode>()
        var root: TreeNode? = null
        for ((index, item) in nodes.withIndex()) {
            if (item.level < 0 || item.level > MAX_DEPTH) return null
            while (stack.size > item.level) stack.removeAt(stack.lastIndex)
            if (stack.size != item.level) return null
            val current = TreeNode(item)
            if (item.level == 0) {
                if (root != null || index != 0) return null
                root = current
            } else {
                val parent = stack.lastOrNull() ?: return null
                parent.children.add(current)
            }
            stack.add(current)
        }
        return root
    }

    private fun decodeNode(node: TreeNode, cursor: PayloadCursor, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw DecodeFailure()
        val typeName = node.summary.typeName.orEmpty()
        val value: Any? = when {
            typeName == "string" -> cursor.readAlignedString()
            typeName == "Array" -> decodeArray(node, cursor, depth)
            typeName == "vector" || typeName == "staticvector" ||
                typeName == "dynamic_array" || typeName == "set" -> {
                val arrayNode = node.children.firstOrNull { it.summary.typeName == "Array" }
                    ?: node.children.firstOrNull()
                if (arrayNode == null) throw DecodeFailure()
                decodeNode(arrayNode, cursor, depth + 1)
            }
            isPrimitive(typeName) -> cursor.readPrimitive(typeName)
            node.children.isNotEmpty() -> {
                val result = linkedMapOf<String, Any?>()
                node.children.forEachIndexed { index, child ->
                    val key = child.summary.fieldName?.takeIf { it.isNotBlank() } ?: "_field$index"
                    result[key] = decodeNode(child, cursor, depth + 1)
                }
                result
            }
            node.summary.byteSize >= 0 -> {
                cursor.skip(node.summary.byteSize)
                null
            }
            else -> throw DecodeFailure()
        }
        if ((node.summary.metaFlags and ALIGN_BYTES_FLAG) != 0) cursor.align4()
        return value
    }

    private fun decodeArray(node: TreeNode, cursor: PayloadCursor, depth: Int): List<Any?> {
        val sizeNode = node.children.firstOrNull { it.summary.fieldName == "size" }
            ?: node.children.firstOrNull()
            ?: throw DecodeFailure()
        val dataNode = node.children.firstOrNull { it.summary.fieldName == "data" }
            ?: node.children.getOrNull(1)
            ?: throw DecodeFailure()
        val countValue = decodeNode(sizeNode, cursor, depth + 1)
        val count = (countValue as? Number)?.toLong() ?: throw DecodeFailure()
        if (count !in 0L..MAX_ARRAY_ITEMS.toLong()) throw DecodeFailure()
        val result = ArrayList<Any?>(count.toInt())
        repeat(count.toInt()) {
            result.add(decodeNode(dataNode, cursor, depth + 1))
        }
        if ((node.summary.metaFlags and ALIGN_BYTES_FLAG) != 0) cursor.align4()
        return result
    }

    private fun isPrimitive(typeName: String): Boolean = typeName in setOf(
        "bool", "char", "SInt8", "UInt8", "SInt16", "UInt16", "short",
        "int", "SInt32", "UInt32", "unsigned int", "long long",
        "SInt64", "UInt64", "unsigned long long", "float", "double"
    )

    private fun findNamed(value: Any?, name: String): Any? {
        when (value) {
            is Map<*, *> -> {
                if (value.containsKey(name)) return value[name]
                value.values.forEach { child ->
                    val found = findNamed(child, name)
                    if (found != null) return found
                }
            }
            is List<*> -> value.forEach { child ->
                val found = findNamed(child, name)
                if (found != null) return found
            }
        }
        return null
    }

    private fun Any?.asBoolean(): Boolean? = when (this) {
        is Boolean -> this
        is Number -> this.toLong() != 0L
        else -> null
    }

    private fun Any?.asPointer(): UnityObjectPointerSummary? {
        val map = this as? Map<*, *> ?: return null
        val fileId = (map["m_FileID"] as? Number)?.toInt() ?: return null
        val pathId = (map["m_PathID"] as? Number)?.toLong() ?: return null
        return UnityObjectPointerSummary(fileId, pathId)
    }

    private fun collectPointers(value: Any?): List<UnityObjectPointerSummary> {
        val result = mutableListOf<UnityObjectPointerSummary>()
        fun visit(item: Any?) {
            when (item) {
                is Map<*, *> -> {
                    val pointer = item.asPointer()
                    if (pointer != null) {
                        result.add(pointer)
                    } else {
                        item.values.forEach(::visit)
                    }
                }
                is List<*> -> item.forEach(::visit)
            }
        }
        visit(value)
        return result
    }

    private fun skipFully(input: InputStream, count: Long, buffer: ByteArray): Boolean {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) return false
                remaining -= read.toLong()
            }
        }
        return true
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        if (count < 0 || count > MAX_OBJECT_BYTES) return null
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read <= 0) return null
            offset += read
        }
        return result
    }

    private class DecodeFailure : RuntimeException()

    private class PayloadCursor(
        private val bytes: ByteArray,
        private val littleEndian: Boolean
    ) {
        private var position = 0

        fun readPrimitive(typeName: String): Any {
            return when (typeName) {
                "bool" -> readUnsigned(1).toInt() != 0
                "char", "SInt8" -> readSigned(1).toInt()
                "UInt8" -> readUnsigned(1).toInt()
                "SInt16", "short" -> readSigned(2).toInt()
                "UInt16" -> readUnsigned(2).toInt()
                "int", "SInt32" -> readSigned(4).toInt()
                "UInt32", "unsigned int" -> readUnsigned(4)
                "long long", "SInt64" -> readSigned(8)
                "UInt64", "unsigned long long" -> readUnsigned(8)
                "float" -> Float.fromBits(readSigned(4).toInt())
                "double" -> Double.fromBits(readSigned(8))
                else -> throw DecodeFailure()
            }
        }

        fun readAlignedString(): String {
            val length = readSigned(4).toInt()
            if (length < 0 || length > MAX_STRING_BYTES || position.toLong() + length > bytes.size) {
                throw DecodeFailure()
            }
            val value = String(bytes, position, length, Charsets.UTF_8)
            position += length
            align4()
            return value
        }

        fun skip(count: Int) {
            if (count < 0 || position.toLong() + count > bytes.size) throw DecodeFailure()
            position += count
        }

        fun align4() {
            val aligned = (position + 3) and -4
            if (aligned > bytes.size) throw DecodeFailure()
            position = aligned
        }

        private fun readUnsigned(count: Int): Long {
            if (count !in 1..8 || position.toLong() + count > bytes.size) throw DecodeFailure()
            var value = 0L
            if (littleEndian) {
                for (index in count - 1 downTo 0) {
                    value = (value shl 8) or (bytes[position + index].toLong() and 0xffL)
                }
            } else {
                for (index in 0 until count) {
                    value = (value shl 8) or (bytes[position + index].toLong() and 0xffL)
                }
            }
            position += count
            return value
        }

        private fun readSigned(count: Int): Long {
            val value = readUnsigned(count)
            if (count == 8) return value
            val shift = 64 - count * 8
            return (value shl shift) shr shift
        }
    }
}
