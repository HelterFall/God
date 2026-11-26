package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class UnityObjectDecoderTest {
    @Test
    fun rejectsDuplicatePathIdsInsteadOfSilentlyChoosingFirstTarget() {
        val objectSummary = UnityObjectSummary(7L, 0L, 1L, 0, 1)
        val typeTree = tree(1, listOf(node(0, "GameObject", "Base")))
        val result = UnityObjectDecoder.readObjects(
            input = java.io.ByteArrayInputStream(byteArrayOf()),
            dataOffset = 0L,
            fileSize = 1L,
            targets = listOf(
                UnityObjectDecodeTarget(objectSummary, typeTree),
                UnityObjectDecodeTarget(objectSummary.copy(byteStart = 1L), typeTree)
            ),
            littleEndian = true
        )

        assertEquals(emptyList<UnityDecodedObjectSummary>(), result.objects)
        assertEquals(2, result.skippedCount)
        assertEquals(
            "Duplicate Unity PathID in decoder targets; decoding stopped to avoid ambiguous object selection.",
            result.note
        )
    }

    @Test
    fun observesCancellationWhileSkippingToSceneObjectPayload() {
        val payload = byteArrayOf(1)
        val byteStart = 64L * 1024L
        val bytes = ByteArray(byteStart.toInt()) + payload
        val typeTree = tree(1, listOf(
            node(0, "GameObject", "Base"),
            node(1, "bool", "m_IsActive")
        ))
        var checks = 0
        val error = runCatching {
            UnityObjectDecoder.readObjects(
                input = java.io.ByteArrayInputStream(bytes),
                dataOffset = 0L,
                fileSize = bytes.size.toLong(),
                targets = listOf(UnityObjectDecodeTarget(
                    UnityObjectSummary(9L, byteStart, payload.size.toLong(), 0, 1), typeTree
                )),
                littleEndian = true,
                shouldCancel = { ++checks >= 2 }
            )
        }.exceptionOrNull()

        assertTrue(error is ScanCancelledException)
        assertEquals(2, checks)
    }

    @Test
    fun observesCancellationDuringChunkedSceneObjectPayloadReads() {
        val payload = ByteArray(16 * 1024)
        val typeTree = tree(1, listOf(
            node(0, "GameObject", "Base"),
            node(1, "bool", "m_IsActive")
        ))
        val input = object : java.io.InputStream() {
            private val delegate = java.io.ByteArrayInputStream(payload)
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                delegate.read(buffer, offset, minOf(length, 1024))
        }
        var checks = 0
        val error = runCatching {
            UnityObjectDecoder.readObjects(
                input = input,
                dataOffset = 0L,
                fileSize = payload.size.toLong(),
                targets = listOf(UnityObjectDecodeTarget(
                    UnityObjectSummary(10L, 0L, payload.size.toLong(), 0, 1), typeTree
                )),
                littleEndian = true,
                shouldCancel = { ++checks >= 4 }
            )
        }.exceptionOrNull()

        assertTrue(error is ScanCancelledException)
        assertEquals(4, checks)
    }

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
    fun decodesTransformAndRectTransformParentChildPointers() {
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
        val nodes = listOf(
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
        )

        for (classId in listOf(4, 224)) {
            val tree = tree(classId, nodes)
            val result = UnityObjectDecoder.decodePayload(payload, 20L, classId, tree, false)

            assertEquals(UnityObjectPointerSummary(0, 10L), result?.gameObject)
            assertEquals(UnityObjectPointerSummary(0, 5L), result?.father)
            assertEquals(
                listOf(UnityObjectPointerSummary(0, 11L), UnityObjectPointerSummary(0, 12L)),
                result?.children
            )
        }
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
