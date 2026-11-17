package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class UnityObjectDecoderTest {
    @Test
    fun decodesGameObjectNameActiveStateAndComponentPointer() {
        val tree = tree(1, listOf(
            node(0, "GameObject", "Base"),
            node(1, "string", "m_Name"),
            node(1, "bool", "m_IsActive"),
            node(1, "vector", "m_Component"),
            node(2, "Array", "Array"),
            node(3, "int", "size"),
            node(3, "PPtr<Component>", "data"),
            node(4, "int", "m_FileID"),
            node(4, "SInt64", "m_PathID")
        ))
        val payload = payload {
            alignedString("Hero")
            writeByte(1)
            writeInt(1)
            writeInt(0)
            writeLong(42L)
        }

        val result = UnityObjectDecoder.decodePayload(payload, 100L, 1, tree, false)

        assertEquals("Hero", result?.name)
        assertEquals(true, result?.isActive)
        assertEquals(listOf(UnityObjectPointerSummary(0, 42L)), result?.components)
    }

    @Test
    fun decodesTransformParentAndChildrenPointers() {
        val tree = tree(4, listOf(
            node(0, "Transform", "Base"),
            node(1, "PPtr<GameObject>", "m_GameObject"),
            node(2, "int", "m_FileID"),
            node(2, "SInt64", "m_PathID"),
            node(1, "PPtr<Transform>", "m_Father"),
            node(2, "int", "m_FileID"),
            node(2, "SInt64", "m_PathID"),
            node(1, "vector", "m_Children"),
            node(2, "Array", "Array"),
            node(3, "int", "size"),
            node(3, "PPtr<Transform>", "data"),
            node(4, "int", "m_FileID"),
            node(4, "SInt64", "m_PathID")
        ))
        val payload = payload {
            writeInt(0)
            writeLong(10L)
            writeInt(0)
            writeLong(5L)
            writeInt(2)
            writeInt(0)
            writeLong(11L)
            writeInt(0)
            writeLong(12L)
        }

        val result = UnityObjectDecoder.decodePayload(payload, 20L, 4, tree, false)

        assertEquals(UnityObjectPointerSummary(0, 10L), result?.gameObject)
        assertEquals(UnityObjectPointerSummary(0, 5L), result?.father)
        assertEquals(
            listOf(UnityObjectPointerSummary(0, 11L), UnityObjectPointerSummary(0, 12L)),
            result?.children
        )
    }

    @Test
    fun refusesIncompleteTypeTreeInsteadOfGuessing() {
        val complete = tree(1, listOf(
            node(0, "GameObject", "Base"),
            node(1, "bool", "m_IsActive")
        ))
        val incomplete = complete.copy(completeForDecoding = false, decoderNodes = emptyList())

        val result = UnityObjectDecoder.decodePayload(byteArrayOf(1), 1L, 1, incomplete, false)

        assertNull(result)
    }

    @Test
    fun rejectsOversizedArrayCount() {
        val tree = tree(1, listOf(
            node(0, "GameObject", "Base"),
            node(1, "vector", "m_Component"),
            node(2, "Array", "Array"),
            node(3, "int", "size"),
            node(3, "int", "data")
        ))
        val payload = payload { writeInt(1025) }

        val result = UnityObjectDecoder.decodePayload(payload, 1L, 1, tree, false)

        assertNull(result)
    }

    private fun tree(classId: Int, nodes: List<UnityTypeTreeNodeSummary>) =
        UnityTypeTreeSummary(
            classId = classId,
            nodeCount = nodes.size.toLong(),
            rootType = nodes.first().typeName,
            rootName = nodes.first().fieldName,
            fieldNames = nodes.mapNotNull { it.fieldName }.distinct(),
            nodes = nodes.take(128),
            decoderNodes = nodes,
            completeForDecoding = true
        )

    private fun node(level: Int, type: String, name: String) =
        UnityTypeTreeNodeSummary(level, type, name, -1, 0, 0)

    private fun payload(write: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use(write)
        return output.toByteArray()
    }

    private fun DataOutputStream.alignedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
        while (size() % 4 != 0) writeByte(0)
    }
}
