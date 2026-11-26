package com.ramen.god

internal data class UnityManagedTypeResolution(
    val sourceFilePath: String,
    val sourcePathId: Long,
    val className: String?,
    val namespace: String?,
    val assemblyName: String?,
    val managedAssemblyPath: String?,
    val managedTypeFound: Boolean?,
    val status: String
)

/** Links parsed MonoScript identities to exact CLR assembly/type metadata. */
internal object UnityManagedTypeResolver {
    private data class IndexedAssembly(
        val path: String,
        val summary: ClrAssemblySummary,
        val typeCounts: Map<Pair<String, String>, Int>
    )

    fun resolve(
        unityMetadata: Map<String, UnityMetadataSummary>,
        managedAssemblies: Map<String, ClrAssemblySummary>,
        shouldCancel: () -> Boolean = { false }
    ): List<UnityManagedTypeResolution> {
        if (shouldCancel()) throw ScanCancelledException()
        val assembliesByName = linkedMapOf<String, MutableList<IndexedAssembly>>()
        for ((path, summary) in managedAssemblies) {
            if (shouldCancel()) throw ScanCancelledException()
            val assemblyName = normalizeAssemblyName(summary.name) ?: continue
            val typeCounts = linkedMapOf<Pair<String, String>, Int>()
            for (type in summary.types) {
                if (shouldCancel()) throw ScanCancelledException()
                val key = type.name to type.namespace
                typeCounts[key] = (typeCounts[key] ?: 0) + 1
            }
            assembliesByName.getOrPut(assemblyName) { mutableListOf() }
                .add(IndexedAssembly(path, summary, typeCounts))
        }

        val results = mutableListOf<UnityManagedTypeResolution>()
        for ((sourcePath, metadata) in unityMetadata) {
            if (shouldCancel()) throw ScanCancelledException()
            for (binding in metadata.monoBehaviourScriptBindings) {
                if (shouldCancel()) throw ScanCancelledException()
                val className = binding.className?.takeIf { it.isNotBlank() }
                val namespace = binding.namespace.orEmpty()
                val assemblyName = normalizeAssemblyName(binding.assemblyName)
                if (className == null || assemblyName == null) {
                    results.add(UnityManagedTypeResolution(
                        sourcePath, binding.sourcePathId, className, binding.namespace,
                        binding.assemblyName, null, null, "mono-script-class-unavailable"
                    ))
                    continue
                }

                val candidates = assembliesByName[assemblyName].orEmpty()
                when {
                    candidates.isEmpty() -> results.add(UnityManagedTypeResolution(
                        sourcePath, binding.sourcePathId, className, binding.namespace,
                        binding.assemblyName, null, null, "managed-assembly-not-found"
                    ))
                    candidates.size > 1 -> results.add(UnityManagedTypeResolution(
                        sourcePath, binding.sourcePathId, className, binding.namespace,
                        binding.assemblyName, null, null, "managed-assembly-ambiguous"
                    ))
                    else -> {
                        val candidate = candidates.single()
                        val assembly = candidate.summary
                        val typeMatches = candidate.typeCounts[className to namespace] ?: 0
                        val status = when {
                            typeMatches == 1 -> "resolved-managed-type"
                            typeMatches > 1 -> "managed-type-ambiguous"
                            assembly.status != "parsed" -> "managed-assembly-metadata-partial"
                            assembly.types.size < (assembly.typeCount - 1).coerceAtLeast(0) ->
                                "managed-type-outside-sample"
                            else -> "managed-type-not-found"
                        }
                        results.add(UnityManagedTypeResolution(
                            sourcePath, binding.sourcePathId, className, binding.namespace,
                            binding.assemblyName, candidate.path,
                            when (status) {
                                "resolved-managed-type" -> true
                                "managed-type-not-found" -> false
                                else -> null
                            },
                            status
                        ))
                    }
                }
            }
        }
        return results
    }

    private fun normalizeAssemblyName(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.lowercase(java.util.Locale.ROOT).removeSuffix(".dll")
    }
}
