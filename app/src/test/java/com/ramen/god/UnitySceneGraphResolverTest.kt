package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitySceneGraphResolverTest {
    @Test
    fun resolvesParentChildAndComponentLinksFromLocalPointers() {
        val objects = listOf(
            UnityDecodedObjectSummary(
                pathId = 100L,
                classId = 1,
                name = "Root",
                isActive = true,
                components = listOf(ptr(0, 200), ptr(0, 300))
            ),
            UnityDecodedObjectSummary(
                pathId = 101L,
                classId = 1,
                name = "Child",
                isActive = false,
                components = listOf(ptr(0, 201))
            ),
            UnityDecodedObjectSummary(
                pathId = 200L,
                classId = 4,
                gameObject = ptr(0, 100),
                father = ptr(0, 0),
                children = listOf(ptr(0, 201))
            ),
            UnityDecodedObjectSummary(
                pathId = 201L,
                classId = 4,
                gameObject = ptr(0, 101),
                father = ptr(0, 200)
            ),
            UnityDecodedObjectSummary(pathId = 300L, classId = 114, script = ptr(0, 900))
        )

        val graph = UnitySceneGraphResolver.resolve(objects)
        val root = graph.nodes.single { it.gameObjectPathId == 100L }
        val child = graph.nodes.single { it.gameObjectPathId == 101L }

        assertEquals("Root", root.name)
        assertEquals(200L, root.transformPathId)
        assertEquals(listOf(101L), root.childGameObjectPathIds)
        assertEquals(listOf(200L, 300L), root.componentPathIds)
        assertNull(root.parentGameObjectPathId)
        assertEquals(100L, child.parentGameObjectPathId)
        assertEquals(201L, child.transformPathId)
        assertEquals(0, graph.unresolvedReferenceCount)
    }

    @Test
    fun preservesExternalAndMissingLinksAsUnresolved() {
        val objects = listOf(
            UnityDecodedObjectSummary(
                pathId = 1L,
                classId = 1,
                components = listOf(ptr(2, 77), ptr(0, 999))
            )
        )

        val graph = UnitySceneGraphResolver.resolve(objects)

        assertEquals(2, graph.unresolvedReferenceCount)
        assertEquals(1, graph.externalReferenceCount)
        assertEquals(2, graph.nodes.single().unresolvedReferenceCount)
    }

    private fun ptr(fileId: Int, pathId: Long) =
        UnityObjectPointerSummary(fileId, pathId)
}
