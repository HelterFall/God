package com.ramen.god

/** Resolves sampled non-local PPtrs only when fileID maps to one known external file. */
internal object UnityExternalPointerResolver {
    private const val MAX_RESOLUTIONS = 2048

    fun resolve(
        metadataByPath: Map<String, UnityMetadataSummary>,
        shouldCancel: () -> Boolean = { false },
        resolvePath: (String) -> String?
    ): Map<String, List<UnityExternalPointerResolution>> {
        val output = linkedMapOf<String, List<UnityExternalPointerResolution>>()
        for ((ownerPath, metadata) in metadataByPath) {
            if (shouldCancel()) throw ScanCancelledException()
            val refs = metadata.externalReferences.associateBy { it.fileId }
            val results = mutableListOf<UnityExternalPointerResolution>()
            for (source in metadata.decodedObjects) {
                if (shouldCancel()) throw ScanCancelledException()
                if (results.size >= MAX_RESOLUTIONS) break
                val pointers = buildList {
                    source.gameObject?.let { add("gameObject" to it) }
                    source.father?.let { add("father" to it) }
                    source.children.forEach { add("children" to it) }
                    source.components.forEach { add("components" to it) }
                    source.script?.let { add("m_Script" to it) }
                }
                for ((field, pointer) in pointers) {
                    if (shouldCancel()) throw ScanCancelledException()
                    if (results.size >= MAX_RESOLUTIONS) break
                    if (pointer.fileId == 0 || pointer.pathId == 0L) continue
                    val external = refs[pointer.fileId]
                    val targetPath = external?.let { resolvePath(it.path) }
                    val targetMetadata = targetPath?.let { metadataByPath[it] }
                    val targetMatches = targetMetadata?.decodedObjects.orEmpty()
                        .filter { it.pathId == pointer.pathId }
                    val sampleMatches = targetMetadata?.objectSamples.orEmpty()
                        .filter { it.pathId == pointer.pathId }
                    val targetAmbiguous = targetMatches.size > 1 || sampleMatches.size > 1
                    val target = targetMatches.singleOrNull()
                    val sample = sampleMatches.singleOrNull()
                    val status = when {
                        external == null -> "external-fileID-not-in-reference-table"
                        targetPath == null -> "external-file-path-unmatched-or-ambiguous"
                        targetMetadata == null -> "external-file-not-parsed"
                        targetAmbiguous -> "target-path-id-ambiguous"
                        target != null -> "resolved-decoded-object"
                        sample != null -> "resolved-object-record-not-decoded"
                        else -> "pathID-not-in-object-sample"
                    }
                    results += UnityExternalPointerResolution(
                        source.pathId, field, pointer.fileId, pointer.pathId, targetPath,
                        target?.classId ?: sample?.classId, target?.name, status
                    )
                }
            }
            output[ownerPath] = results
        }
        return output
    }
}
