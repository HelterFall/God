package com.ramen.god

internal data class UnitySceneGraphNode(
    val gameObjectPathId: Long,
    val name: String?,
    val isActive: Boolean?,
    val transformPathId: Long?,
    val parentGameObjectPathId: Long?,
    val childGameObjectPathIds: List<Long>,
    val componentPathIds: List<Long>,
    val unresolvedReferenceCount: Int
)

internal data class UnitySceneGraphResult(
    val nodes: List<UnitySceneGraphNode>,
    val unresolvedReferenceCount: Int,
    val externalReferenceCount: Int
)

/**
 * Resolves only local (fileID == 0) PPtrs. Cross-file references remain explicit
 * unresolved links until a matching SerializedFile is loaded and indexed.
 */
internal object UnitySceneGraphResolver {
    private const val MAX_GRAPH_NODES = 128
    private const val MAX_LINKS_PER_OBJECT = 1024

    fun resolve(objects: List<UnityDecodedObjectSummary>): UnitySceneGraphResult {
        val byPathId = objects.associateBy { it.pathId }
        val transforms = objects.filter { it.classId == 4 }
        val transformByGameObject = transforms.mapNotNull { transform ->
            val gameObject = transform.gameObject
            if (gameObject?.fileId == 0 && byPathId[gameObject.pathId]?.classId == 1) {
                gameObject.pathId to transform
            } else null
        }.toMap()

        var unresolvedTotal = 0
        var externalTotal = 0
        val nodes = objects.asSequence()
            .filter { it.classId == 1 }
            .take(MAX_GRAPH_NODES)
            .map { gameObject ->
                var unresolved = 0
                var external = 0
                fun resolveLocal(pointer: UnityObjectPointerSummary?): UnityDecodedObjectSummary? {
                    if (pointer == null || pointer.pathId == 0L) return null
                    if (pointer.fileId != 0) {
                        unresolved++
                        external++
                        return null
                    }
                    val target = byPathId[pointer.pathId]
                    if (target == null) unresolved++
                    return target
                }

                val componentPointers = gameObject.components.take(MAX_LINKS_PER_OBJECT)
                val componentObjects = componentPointers.mapNotNull { resolveLocal(it) }
                if (gameObject.components.size > MAX_LINKS_PER_OBJECT) {
                    unresolved += gameObject.components.size - MAX_LINKS_PER_OBJECT
                }
                val transform = componentObjects.firstOrNull { it.classId == 4 }
                    ?: transformByGameObject[gameObject.pathId]
                val parentTransform = resolveLocal(transform?.father)
                val parentGameObject = if (parentTransform?.classId == 4) {
                    resolveLocal(parentTransform.gameObject)?.takeIf { it.classId == 1 }
                } else null
                val childGameObjects = transform?.children.orEmpty()
                    .take(MAX_LINKS_PER_OBJECT)
                    .mapNotNull { childPointer ->
                        val childTransform = resolveLocal(childPointer)
                        if (childTransform?.classId == 4) {
                            resolveLocal(childTransform.gameObject)?.takeIf { it.classId == 1 }?.pathId
                        } else null
                    }.distinct()
                if (transform?.children.orEmpty().size > MAX_LINKS_PER_OBJECT) {
                    unresolved += transform.children.size - MAX_LINKS_PER_OBJECT
                }
                unresolvedTotal += unresolved
                externalTotal += external
                UnitySceneGraphNode(
                    gameObjectPathId = gameObject.pathId,
                    name = gameObject.name,
                    isActive = gameObject.isActive,
                    transformPathId = transform?.pathId,
                    parentGameObjectPathId = parentGameObject?.pathId,
                    childGameObjectPathIds = childGameObjects,
                    componentPathIds = componentObjects.map { it.pathId }.distinct(),
                    unresolvedReferenceCount = unresolved
                )
            }.toList()
        return UnitySceneGraphResult(nodes, unresolvedTotal, externalTotal)
    }
}
