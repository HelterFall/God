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

    @Test
    fun resolvesExternalPointersOnlyThroughUniqueFileIdAndPathId() {
        val owner = UnityMetadataSummary(null, null, true, 0,
            externalReferences = listOf(UnityExternalReference(1, "shared.assets", 0)),
            decodedObjects = listOf(UnityDecodedObjectSummary(10, 114, script = ptr(1, 77))))
        val target = UnityMetadataSummary(null, null, true, 0,
            decodedObjects = listOf(UnityDecodedObjectSummary(77, 115, name = "PlayerLogic")))
        val result = UnityExternalPointerResolver.resolve(mapOf("level.assets" to owner, "shared.assets" to target)) { it }
        val link = result.getValue("level.assets").single()
        assertEquals("resolved-decoded-object", link.status)
        assertEquals(115, link.targetClassId)
        assertEquals("PlayerLogic", link.targetName)
        val ambiguous = UnityExternalPointerResolver.resolve(mapOf("level.assets" to owner, "shared.assets" to target)) { null }
        assertEquals("external-file-path-unmatched-or-ambiguous", ambiguous.getValue("level.assets").single().status)
    }

    @Test
    fun bindsMonoBehaviourScriptPointerToParsedMonoScriptClass() {
        val owner = UnityMetadataSummary(null, null, true, 0,
            decodedObjects = listOf(UnityDecodedObjectSummary(11, 114, script = ptr(0, 90))))
        val script = UnityMonoScriptSummary(90, "Player", 0, "PlayerController", "Game", "Assembly-CSharp", "scripts.assets")
        val target = UnityMetadataSummary(null, null, true, 0, monoScripts = listOf(script))
        val result = UnityMonoBehaviourScriptResolver.resolve(mapOf("scene.assets" to owner, "scripts.assets" to target)) { it }
        val binding = result.getValue("scene.assets").single()
        assertEquals("resolved-monoscript-class", binding.status)
        assertEquals("PlayerController", binding.className)
        assertEquals("Game", binding.namespace)
        assertEquals("Assembly-CSharp", binding.assemblyName)
    }

    @Test
    fun reportsUnparsedMonoScriptPayloadWithoutGuessingClassName() {
        val owner = UnityMetadataSummary(null, null, true, 0,
            externalReferences = listOf(UnityExternalReference(1, "scripts.assets", 0)),
            decodedObjects = listOf(UnityDecodedObjectSummary(11, 114, script = ptr(1, 90))))
        val target = UnityMetadataSummary(null, null, true, 0,
            monoScriptObjects = listOf(UnityObjectSummary(90, 12, 40, 0, 115)))
        val result = UnityMonoBehaviourScriptResolver.resolve(mapOf("scene.assets" to owner, "scripts.assets" to target)) { it }
        assertEquals("monoscript-object-found-payload-unavailable",
            result.getValue("scene.assets").single().status)
        assertEquals(null, result.getValue("scene.assets").single().className)
    }

    private fun ptr(fileId: Int, pathId: Long) =
        UnityObjectPointerSummary(fileId, pathId)
}
