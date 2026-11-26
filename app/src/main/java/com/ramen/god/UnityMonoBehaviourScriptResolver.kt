package com.ramen.god

/** Binds decoded MonoBehaviour.m_Script PPtrs to parsed MonoScript payloads. */
internal object UnityMonoBehaviourScriptResolver {
    private const val MAX_BINDINGS_PER_FILE = 1024

    fun resolve(
        metadataByPath: Map<String, UnityMetadataSummary>,
        shouldCancel: () -> Boolean = { false },
        resolveExternalPath: (String) -> String?
    ): Map<String, List<UnityMonoBehaviourScriptBinding>> {
        val output = linkedMapOf<String, List<UnityMonoBehaviourScriptBinding>>()
        for ((ownerPath, owner) in metadataByPath) {
            if (shouldCancel()) throw ScanCancelledException()
            val refs = owner.externalReferences.associateBy { it.fileId }
            val bindings = mutableListOf<UnityMonoBehaviourScriptBinding>()
            for (behaviour in owner.decodedObjects) {
                if (shouldCancel()) throw ScanCancelledException()
                if (bindings.size >= MAX_BINDINGS_PER_FILE) break
                if (behaviour.classId != 114 || behaviour.script == null) continue
                val pointer = requireNotNull(behaviour.script)
                val targetPath = when {
                    pointer.pathId == 0L -> null
                    pointer.fileId == 0 -> ownerPath
                    else -> refs[pointer.fileId]?.let { resolveExternalPath(it.path) }
                }
                val targetMetadata = targetPath?.let { metadataByPath[it] }
                val scriptMatches = targetMetadata?.monoScripts.orEmpty()
                    .filter { it.pathId == pointer.pathId }
                val objectMatches = targetMetadata?.monoScriptObjects.orEmpty()
                    .filter { it.pathId == pointer.pathId }
                val targetAmbiguous = scriptMatches.size > 1 || objectMatches.size > 1
                val script = scriptMatches.singleOrNull()
                val objectSample = objectMatches.singleOrNull()
                val status = when {
                    pointer.pathId == 0L -> "null-script-reference"
                    pointer.fileId != 0 && refs[pointer.fileId] == null -> "external-fileID-not-in-reference-table"
                    targetPath == null -> "external-file-path-unmatched-or-ambiguous"
                    targetMetadata == null -> "target-file-not-parsed"
                    targetAmbiguous -> "monoscript-path-id-ambiguous"
                    script != null -> "resolved-monoscript-class"
                    objectSample != null -> "monoscript-object-found-payload-unavailable"
                    else -> "monoscript-not-in-object-sample"
                }
                bindings += UnityMonoBehaviourScriptBinding(
                    sourcePathId = behaviour.pathId, fileId = pointer.fileId, scriptPathId = pointer.pathId,
                    targetFilePath = targetPath, monoScriptName = script?.name, className = script?.className,
                    namespace = script?.namespace, assemblyName = script?.assemblyName,
                    executionOrder = script?.executionOrder, status = status
                )
            }
            output[ownerPath] = bindings
        }
        return output
    }
}
