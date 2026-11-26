package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun resolvesRectTransformObjectsAsUiParentAndChildTransforms() {
        val objects = listOf(
            UnityDecodedObjectSummary(
                pathId = 100L,
                classId = 1,
                name = "CanvasRoot",
                components = listOf(ptr(0, 220))
            ),
            UnityDecodedObjectSummary(
                pathId = 101L,
                classId = 1,
                name = "Button",
                components = listOf(ptr(0, 221))
            ),
            UnityDecodedObjectSummary(
                pathId = 220L,
                classId = 224,
                gameObject = ptr(0, 100),
                father = ptr(0, 0),
                children = listOf(ptr(0, 221))
            ),
            UnityDecodedObjectSummary(
                pathId = 221L,
                classId = 224,
                gameObject = ptr(0, 101),
                father = ptr(0, 220)
            )
        )

        val graph = UnitySceneGraphResolver.resolve(objects)
        val root = graph.nodes.single { it.gameObjectPathId == 100L }
        val child = graph.nodes.single { it.gameObjectPathId == 101L }

        assertEquals(220L, root.transformPathId)
        assertEquals(listOf(101L), root.childGameObjectPathIds)
        assertEquals(100L, child.parentGameObjectPathId)
        assertEquals(221L, child.transformPathId)
        assertEquals(0, graph.unresolvedReferenceCount)
    }

    @Test
    fun marksGameObjectWithoutItsRequiredTransformAsUnresolved() {
        val graph = UnitySceneGraphResolver.resolve(
            listOf(UnityDecodedObjectSummary(pathId = 100L, classId = 1, name = "NoTransform"))
        )

        assertNull(graph.nodes.single().transformPathId)
        assertEquals(1, graph.nodes.single().unresolvedReferenceCount)
        assertEquals(1, graph.unresolvedReferenceCount)
    }

    @Test
    fun doesNotChooseArbitraryTransformWhenGameObjectHasAmbiguousAssociations() {
        val objects = listOf(
            UnityDecodedObjectSummary(
                pathId = 100L,
                classId = 1,
                name = "Ambiguous",
                components = listOf(ptr(0, 200))
            ),
            UnityDecodedObjectSummary(
                pathId = 200L,
                classId = 4,
                gameObject = ptr(0, 100)
            ),
            UnityDecodedObjectSummary(
                pathId = 201L,
                classId = 224,
                gameObject = ptr(0, 100)
            )
        )

        val graph = UnitySceneGraphResolver.resolve(objects)
        val node = graph.nodes.single()

        assertNull(node.transformPathId)
        assertEquals(1, node.unresolvedReferenceCount)
        assertEquals(1, graph.unresolvedReferenceCount)
    }

    @Test
    fun countsLocalParentAndChildPointersThatResolveToWrongClasses() {
        val graph = UnitySceneGraphResolver.resolve(
            listOf(
                UnityDecodedObjectSummary(
                    pathId = 100L,
                    classId = 1,
                    name = "Root",
                    components = listOf(ptr(0, 200L))
                ),
                UnityDecodedObjectSummary(
                    pathId = 200L,
                    classId = 4,
                    gameObject = ptr(0, 100L),
                    father = ptr(0, 300L),
                    children = listOf(ptr(0, 400L))
                ),
                UnityDecodedObjectSummary(pathId = 300L, classId = 114),
                UnityDecodedObjectSummary(pathId = 400L, classId = 114)
            )
        )

        val root = graph.nodes.single()
        assertEquals(200L, root.transformPathId)
        assertNull(root.parentGameObjectPathId)
        assertTrue(root.childGameObjectPathIds.isEmpty())
        assertEquals(2, root.unresolvedReferenceCount)
        assertEquals(2, graph.unresolvedReferenceCount)
    }

    @Test
    fun marksNonReciprocalParentLinkAsUnresolvedInsteadOfAddingHierarchyEdge() {
        val objects = listOf(
            UnityDecodedObjectSummary(
                pathId = 100L,
                classId = 1,
                name = "Parent",
                components = listOf(ptr(0, 200L))
            ),
            UnityDecodedObjectSummary(
                pathId = 101L,
                classId = 1,
                name = "Child",
                components = listOf(ptr(0, 201L))
            ),
            UnityDecodedObjectSummary(
                pathId = 200L,
                classId = 4,
                gameObject = ptr(0, 100L)
                // The parent's children array does not contain Transform 201.
            ),
            UnityDecodedObjectSummary(
                pathId = 201L,
                classId = 4,
                gameObject = ptr(0, 101L),
                father = ptr(0, 200L)
            )
        )

        val graph = UnitySceneGraphResolver.resolve(objects)
        val parent = graph.nodes.single { it.gameObjectPathId == 100L }
        val child = graph.nodes.single { it.gameObjectPathId == 101L }

        assertTrue(parent.childGameObjectPathIds.isEmpty())
        assertNull(child.parentGameObjectPathId)
        assertEquals(1, child.unresolvedReferenceCount)
        assertEquals(1, graph.unresolvedReferenceCount)
    }

    @Test
    fun detectsCyclesInOtherwiseReciprocalTransformHierarchy() {
        val graph = UnitySceneGraphResolver.resolve(
            listOf(
                UnityDecodedObjectSummary(
                    pathId = 100L,
                    classId = 1,
                    name = "First",
                    components = listOf(ptr(0, 200L))
                ),
                UnityDecodedObjectSummary(
                    pathId = 101L,
                    classId = 1,
                    name = "Second",
                    components = listOf(ptr(0, 201L))
                ),
                UnityDecodedObjectSummary(
                    pathId = 200L,
                    classId = 4,
                    gameObject = ptr(0, 100L),
                    father = ptr(0, 201L),
                    children = listOf(ptr(0, 201L))
                ),
                UnityDecodedObjectSummary(
                    pathId = 201L,
                    classId = 4,
                    gameObject = ptr(0, 101L),
                    father = ptr(0, 200L),
                    children = listOf(ptr(0, 200L))
                )
            )
        )

        assertEquals(2, graph.unresolvedReferenceCount)
        graph.nodes.forEach { node ->
            assertNull(node.parentGameObjectPathId)
            assertTrue(node.childGameObjectPathIds.isEmpty())
            assertEquals(1, node.unresolvedReferenceCount)
        }
    }

    @Test
    fun preservesExternalAndMissingLinksAsUnresolved() {
        val objects = listOf(
            UnityDecodedObjectSummary(
                pathId = 1L,
                classId = 1,
                components = listOf(ptr(2, 77), ptr(0, 999))
            ),
            UnityDecodedObjectSummary(
                pathId = 88L,
                classId = 4,
                gameObject = ptr(0, 1)
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
    fun externalPointerResolverHonorsCancellationInsidePointerLoop() {
        val owner = UnityMetadataSummary(
            null, null, true, 0,
            externalReferences = listOf(UnityExternalReference(1, "shared.assets", 0)),
            decodedObjects = listOf(
                UnityDecodedObjectSummary(
                    10, 114,
                    children = listOf(ptr(1, 77), ptr(1, 78))
                )
            )
        )
        var checks = 0
        val error = runCatching {
            UnityExternalPointerResolver.resolve(
                metadataByPath = mapOf("scene.assets" to owner),
                resolvePath = { "shared.assets" },
                shouldCancel = { ++checks >= 4 }
            )
        }.exceptionOrNull()

        assertTrue(error is ScanCancelledException)
        assertEquals(4, checks)
    }

    @Test
    fun monoBehaviourResolverHonorsCancellationBetweenBindings() {
        val owner = UnityMetadataSummary(
            null, null, true, 0,
            externalReferences = listOf(UnityExternalReference(1, "scripts.assets", 0)),
            decodedObjects = listOf(
                UnityDecodedObjectSummary(11, 114, script = ptr(1, 90)),
                UnityDecodedObjectSummary(12, 114, script = ptr(1, 91))
            )
        )
        var checks = 0
        val error = runCatching {
            UnityMonoBehaviourScriptResolver.resolve(
                metadataByPath = mapOf("scene.assets" to owner),
                resolveExternalPath = { "scripts.assets" },
                shouldCancel = { ++checks >= 3 }
            )
        }.exceptionOrNull()

        assertTrue(error is ScanCancelledException)
        assertEquals(3, checks)
    }

    @Test
    fun bindsMonoBehaviourScriptPointerToParsedMonoScriptClass() {
        val owner = UnityMetadataSummary(null, null, true, 0,
            externalReferences = listOf(UnityExternalReference(1, "scripts.assets", 0)),
            decodedObjects = listOf(UnityDecodedObjectSummary(11, 114, script = ptr(1, 90))))
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

    @Test
    fun excludesDuplicatePathIdsFromSceneGraphLookup() {
        val graph = UnitySceneGraphResolver.resolve(
            listOf(
                UnityDecodedObjectSummary(pathId = 100L, classId = 1, name = "First"),
                UnityDecodedObjectSummary(pathId = 100L, classId = 1, name = "Second"),
                UnityDecodedObjectSummary(pathId = 200L, classId = 4, gameObject = ptr(0, 100L))
            )
        )

        assertTrue(graph.nodes.isEmpty())
        assertEquals(1, graph.unresolvedReferenceCount)
    }

    @Test
    fun externalPointerResolverRejectsDuplicateTargetPathIds() {
        val owner = UnityMetadataSummary(
            null, null, true, 0,
            externalReferences = listOf(UnityExternalReference(1, "target.assets", 0)),
            decodedObjects = listOf(
                UnityDecodedObjectSummary(10L, 1, components = listOf(ptr(1, 77L)))
            )
        )
        val target = UnityMetadataSummary(
            null, null, true, 0,
            decodedObjects = listOf(
                UnityDecodedObjectSummary(77L, 114),
                UnityDecodedObjectSummary(77L, 115)
            )
        )

        val result = UnityExternalPointerResolver.resolve(
            metadataByPath = mapOf("owner.assets" to owner, "target.assets" to target),
            resolvePath = { it }
        )

        assertEquals(
            "target-path-id-ambiguous",
            result.getValue("owner.assets").single().status
        )
    }

    @Test
    fun monoBehaviourScriptResolverRejectsDuplicateMonoScriptPathIds() {
        val owner = UnityMetadataSummary(
            null, null, true, 0,
            externalReferences = listOf(UnityExternalReference(1, "scripts.assets", 0)),
            decodedObjects = listOf(
                UnityDecodedObjectSummary(10L, 114, script = ptr(1, 77L))
            )
        )
        val duplicateScriptA = UnityMonoScriptSummary(77L, "One", 0, "One", "Game", "Assembly-CSharp")
        val duplicateScriptB = UnityMonoScriptSummary(77L, "Two", 0, "Two", "Game", "Assembly-CSharp")
        val target = UnityMetadataSummary(
            null, null, true, 0,
            monoScripts = listOf(duplicateScriptA, duplicateScriptB)
        )

        val result = UnityMonoBehaviourScriptResolver.resolve(
            metadataByPath = mapOf("owner.assets" to owner, "scripts.assets" to target),
            resolveExternalPath = { it }
        )

        assertEquals(
            "monoscript-path-id-ambiguous",
            result.getValue("owner.assets").single().status
        )
        assertNull(result.getValue("owner.assets").single().className)
    }

    private fun ptr(fileId: Int, pathId: Long) =
        UnityObjectPointerSummary(fileId, pathId)
}
