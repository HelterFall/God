package com.ramen.god

import java.util.Locale

internal data class UnityExternalPathMatch<T>(
    val item: T?,
    val ambiguous: Boolean
)

/**
 * Matches an external SerializedFile path to the scanned inventory.
 * A basename match is accepted only when it is unique.
 */
internal object UnityExternalPathResolver {
    fun <T> resolve(
        externalPath: String,
        candidates: List<T>,
        pathOf: (T) -> String,
        nameOf: (T) -> String
    ): UnityExternalPathMatch<T> {
        var normalized = externalPath.trim().replace('\\', '/')
        val archiveMarker = normalized.indexOf("archive:/", ignoreCase = true)
        if (archiveMarker >= 0) {
            normalized = normalized.substring(archiveMarker + "archive:/".length)
        }
        normalized = normalized.trimStart('/').lowercase(Locale.ROOT)
        if (normalized.isBlank()) return UnityExternalPathMatch(null, false)

        val pathMatches = candidates.filter { item ->
            val candidate = pathOf(item).replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
            normalized == candidate || normalized.endsWith("/" + candidate)
        }
        if (pathMatches.size == 1) return UnityExternalPathMatch(pathMatches.single(), false)
        if (pathMatches.size > 1) return UnityExternalPathMatch(null, true)

        val baseName = normalized.substringAfterLast('/')
        val nameMatches = candidates.filter { nameOf(it).equals(baseName, ignoreCase = true) }
        return when {
            nameMatches.size == 1 -> UnityExternalPathMatch(nameMatches.single(), false)
            nameMatches.size > 1 -> UnityExternalPathMatch(null, true)
            else -> UnityExternalPathMatch(null, false)
        }
    }
}
