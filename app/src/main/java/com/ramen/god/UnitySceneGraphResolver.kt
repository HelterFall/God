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

    private fun isTransformClass(classId: Int?): Boolean = classId == 4 || classId == 224

    fun resolve(objects: List<UnityDecodedObjectSummary>): UnitySceneGraphResult {
        val byPathId = objects.associateBy { it.pathId }
        val transforms = objects.filter { isTransformClass(it.classId) }
        val transformsByGameObject = transforms.mapNotNull { transform ->
            val gameObject = transform.gameObject
            if (gameObject?.fileId == 0 && byPathId[gameObject.pathId]?.classId == 1) {
                gameObject.pathId to transform
            } else null
        }.groupBy({ it.first }, { it.second })

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
                val componentTransforms = componentObjects.filter { isTransformClass(it.classId) }
                val associatedTransforms = transformsByGameObject[gameObject.pathId].orEmpty()
                val transformCandidates = componentTransforms + associatedTransforms
                val uniqueTransformCandidates = transformCandidates.distinctBy { it.pathId }
                val hasAmbiguousTransform = componentTransforms.size > 1 ||
                    associatedTransforms.map { it.pathId }.distinct().size > 1 ||
                    (componentTransforms.isNotEmpty() && associatedTransforms.isNotEmpty() &&
                        componentTransforms.singleOrNull()?.pathId != associatedTransforms.singleOrNull()?.pathId)
                if (hasAmbiguousTransform) unresolved++
                val transform = if (hasAmbiguousTransform) null else uniqueTransformCandidates.singleOrNull()
                val parentTransform = resolveLocal(transform?.father)
                val parentGameObject = parentTransform
                    ?.takeIf { isTransformClass(it.classId) }
                    ?.let { resolveLocal(it.gameObject) }
                    ?.takeIf { it.classId == 1 }
                val childGameObjects = transform?.children.orEmpty()
                    .take(MAX_LINKS_PER_OBJECT)
                    .mapNotNull { childPointer ->
                        resolveLocal(childPointer)
                            ?.takeIf { isTransformClass(it.classId) }
                            ?.let { resolveLocal(it.gameObject) }
                            ?.takeIf { it.classId == 1 }
                            ?.pathId
                    }.distinct()
                if (transform?.children.orEmpty().size > MAX_LINKS_PER_OBJECT) {
                    unresolved += transform?.children.orEmpty().size - MAX_LINKS_PER_OBJECT
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
