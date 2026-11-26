package com.ramen.god

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnityManagedTypeResolverTest {
    @Test
    fun resolvesExactNamespaceClassAndAssemblyMatch() {
        val unity = mapOf("level0" to metadata(binding("PlayerController", "Game", "Assembly-CSharp.dll")))
        val assemblies = mapOf(
            "Managed/Assembly-CSharp.dll" to assembly(
                "Assembly-CSharp", 2, listOf(ClrTypeSummary("PlayerController", "Game", 1))
            )
        )

        val result = UnityManagedTypeResolver.resolve(unity, assemblies).single()

        assertEquals("resolved-managed-type", result.status)
        assertEquals("Managed/Assembly-CSharp.dll", result.managedAssemblyPath)
        assertEquals(true, result.managedTypeFound)
    }

    @Test
    fun observesCancellationWhileIndexingManagedAssemblyTypes() {
        val unity = mapOf("level0" to metadata(binding("PlayerController", "Game", "Assembly-CSharp")))
        val assemblies = mapOf(
            "Managed/Assembly-CSharp.dll" to assembly(
                "Assembly-CSharp", 3, listOf(
                    ClrTypeSummary("A", "Game", 1),
                    ClrTypeSummary("B", "Game", 1),
                    ClrTypeSummary("C", "Game", 1)
                )
            )
        )
        var checks = 0
        val error = runCatching {
            UnityManagedTypeResolver.resolve(
                unity, assemblies,
                shouldCancel = { ++checks >= 3 }
            )
        }.exceptionOrNull()

        assertTrue(error is ScanCancelledException)
        assertEquals(3, checks)
    }

    @Test
    fun preservesMissingAndAmbiguousAssemblyStates() {
        val unity = mapOf("level0" to metadata(binding("PlayerController", "Game", "Assembly-CSharp")))
        val missing = UnityManagedTypeResolver.resolve(unity, emptyMap()).single()
        assertEquals("managed-assembly-not-found", missing.status)

        val ambiguous = UnityManagedTypeResolver.resolve(unity, mapOf(
            "a/Assembly-CSharp.dll" to assembly("Assembly-CSharp", 2, emptyList()),
            "b/Assembly-CSharp.dll" to assembly("Assembly-CSharp", 2, emptyList())
        )).single()
        assertEquals("managed-assembly-ambiguous", ambiguous.status)
        assertNull(ambiguous.managedAssemblyPath)
    }

    @Test
    fun doesNotTreatTruncatedTypeSampleAsProofThatClassIsMissing() {
        val unity = mapOf("level0" to metadata(binding("NotSampled", "Game", "Assembly-CSharp")))
        val sampledTypes = (0 until 1000).map { ClrTypeSummary("Type$it", "Game", 1) }
        val assemblies = mapOf("Assembly-CSharp.dll" to assembly("Assembly-CSharp", 1002, sampledTypes))

        val result = UnityManagedTypeResolver.resolve(unity, assemblies).single()

        assertEquals("managed-type-outside-sample", result.status)
        assertNull(result.managedTypeFound)
    }

    private fun binding(className: String, namespace: String, assemblyName: String) =
        UnityMonoBehaviourScriptBinding(
            sourcePathId = 123,
            fileId = 0,
            scriptPathId = 456,
            targetFilePath = "scripts.assets",
            monoScriptName = "Player",
            className = className,
            namespace = namespace,
            assemblyName = assemblyName,
            executionOrder = 0,
            status = "resolved-monoscript-class"
        )

    private fun metadata(binding: UnityMonoBehaviourScriptBinding) =
        UnityMetadataSummary(null, null, true, 0, monoBehaviourScriptBindings = listOf(binding))

    private fun assembly(name: String, typeCount: Int, types: List<ClrTypeSummary>) =
        ClrAssemblySummary(name, "1.0.0.0", "$name.dll", "2.5", 1, typeCount,
            types, emptyList(), "parsed")
}
