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
    fun resolve(
        unityMetadata: Map<String, UnityMetadataSummary>,
        managedAssemblies: Map<String, ClrAssemblySummary>
    ): List<UnityManagedTypeResolution> {
        val results = mutableListOf<UnityManagedTypeResolution>()
        unityMetadata.forEach { (sourcePath, metadata) ->
            metadata.monoBehaviourScriptBindings.forEach { binding ->
                val className = binding.className?.takeIf { it.isNotBlank() }
                val namespace = binding.namespace.orEmpty()
                val assemblyName = normalizeAssemblyName(binding.assemblyName)
                if (className == null || assemblyName == null) {
                    results.add(UnityManagedTypeResolution(
                        sourcePath, binding.sourcePathId, className, binding.namespace,
                        binding.assemblyName, null, null, "mono-script-class-unavailable"
                    ))
                    return@forEach
                }

                val candidates = managedAssemblies.filter { (_, summary) ->
                    normalizeAssemblyName(summary.name) == assemblyName
                }.keys
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
                        val assemblyPath = candidates.single()
                        val assembly = managedAssemblies.getValue(assemblyPath)
                        val typeMatches = assembly.types.filter {
                            it.name == className && it.namespace == namespace
                        }
                        val status = when {
                            typeMatches.size == 1 -> "resolved-managed-type"
                            typeMatches.size > 1 -> "managed-type-ambiguous"
                            assembly.status != "parsed" -> "managed-assembly-metadata-partial"
                            assembly.types.size < (assembly.typeCount - 1).coerceAtLeast(0) ->
                                "managed-type-outside-sample"
                            else -> "managed-type-not-found"
                        }
                        results.add(UnityManagedTypeResolution(
                            sourcePath, binding.sourcePathId, className, binding.namespace,
                            binding.assemblyName, assemblyPath,
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
        return trimmed.removeSuffix(".dll").removeSuffix(".DLL").lowercase()
    }
}
