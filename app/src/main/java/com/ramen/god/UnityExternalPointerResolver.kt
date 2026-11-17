package com.ramen.god

/** Resolves sampled non-local PPtrs only when fileID maps to one known external file. */
internal object UnityExternalPointerResolver {
    private const val MAX_RESOLUTIONS = 2048

    fun resolve(metadataByPath: Map<String, UnityMetadataSummary>, resolvePath: (String) -> String?): Map<String, List<UnityExternalPointerResolution>> {
        val output = linkedMapOf<String, List<UnityExternalPointerResolution>>()
        metadataByPath.forEach { (ownerPath, metadata) ->
            val refs = metadata.externalReferences.associateBy { it.fileId }
            val results = mutableListOf<UnityExternalPointerResolution>()
            metadata.decodedObjects.forEach { source ->
                val pointers = buildList {
                    source.gameObject?.let { add("gameObject" to it) }
                    source.father?.let { add("father" to it) }
                    source.children.forEach { add("children" to it) }
                    source.components.forEach { add("components" to it) }
                    source.script?.let { add("m_Script" to it) }
                }
                pointers.forEach pointerLoop@{ (field, pointer) ->
                    if (pointer.fileId == 0 || pointer.pathId == 0L || results.size >= MAX_RESOLUTIONS) return@pointerLoop
                    val external = refs[pointer.fileId]
                    val targetPath = external?.let { resolvePath(it.path) }
                    val targetMetadata = targetPath?.let { metadataByPath[it] }
                    val target = targetMetadata?.decodedObjects?.firstOrNull { it.pathId == pointer.pathId }
                    val sample = targetMetadata?.objectSamples?.firstOrNull { it.pathId == pointer.pathId }
                    val status = when {
                        external == null -> "external-fileID-not-in-reference-table"
                        targetPath == null -> "external-file-path-unmatched-or-ambiguous"
                        targetMetadata == null -> "external-file-not-parsed"
                        target != null -> "resolved-decoded-object"
                        sample != null -> "resolved-object-record-not-decoded"
                        else -> "pathID-not-in-object-sample"
                    }
                    results += UnityExternalPointerResolution(source.pathId, field, pointer.fileId, pointer.pathId, targetPath, target?.classId ?: sample?.classId, target?.name, status)
                }
            }
            output[ownerPath] = results
        }
        return output
    }
}
