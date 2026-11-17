package com.ramen.god

/** Binds decoded MonoBehaviour.m_Script PPtrs to parsed MonoScript payloads. */
internal object UnityMonoBehaviourScriptResolver {
    private const val MAX_BINDINGS_PER_FILE = 1024

    fun resolve(
        metadataByPath: Map<String, UnityMetadataSummary>,
        resolveExternalPath: (String) -> String?
    ): Map<String, List<UnityMonoBehaviourScriptBinding>> {
        return metadataByPath.mapValues { (ownerPath, owner) ->
            val refs = owner.externalReferences.associateBy { it.fileId }
            owner.decodedObjects.asSequence()
                .filter { it.classId == 114 && it.script != null }
                .take(MAX_BINDINGS_PER_FILE)
                .map { behaviour ->
                    val pointer = requireNotNull(behaviour.script)
                    val targetPath = when {
                        pointer.pathId == 0L -> null
                        pointer.fileId == 0 -> ownerPath
                        else -> refs[pointer.fileId]?.let { resolveExternalPath(it.path) }
                    }
                    val targetMetadata = targetPath?.let { metadataByPath[it] }
                    val script = targetMetadata?.monoScripts?.firstOrNull { it.pathId == pointer.pathId }
                    val objectSample = targetMetadata?.monoScriptObjects?.firstOrNull { it.pathId == pointer.pathId }
                    val status = when {
                        pointer.pathId == 0L -> "null-script-reference"
                        pointer.fileId != 0 && refs[pointer.fileId] == null -> "external-fileID-not-in-reference-table"
                        targetPath == null -> "external-file-path-unmatched-or-ambiguous"
                        targetMetadata == null -> "target-file-not-parsed"
                        script != null -> "resolved-monoscript-class"
                        objectSample != null -> "monoscript-object-found-payload-unavailable"
                        else -> "monoscript-not-in-object-sample"
                    }
                    UnityMonoBehaviourScriptBinding(
                        sourcePathId = behaviour.pathId, fileId = pointer.fileId, scriptPathId = pointer.pathId,
                        targetFilePath = targetPath, monoScriptName = script?.name, className = script?.className,
                        namespace = script?.namespace, assemblyName = script?.assemblyName,
                        executionOrder = script?.executionOrder, status = status
                    )
                }.toList()
        }
    }
}
