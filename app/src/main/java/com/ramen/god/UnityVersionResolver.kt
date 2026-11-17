package com.ramen.god

internal data class UnityVersionEvidence(
    val version: String,
    val source: String,
    val path: String,
    val confidence: String
)

internal data class UnityVersionResult(
    val version: String?,
    val status: String,
    val confidence: String?,
    val evidence: List<UnityVersionEvidence>
)

/** Combines exact Unity version strings without guessing when evidence conflicts. */
internal object UnityVersionResolver {
    private val versionPattern = Regex(
        """(?<![A-Za-z0-9])((?:[3-9]\\.\\d{1,2}\\.\\d{1,3}|(?:19|20)\\d{2}\\.\\d{1,2}\\.\\d{1,3}|[5-9]\\d{3}\\.\\d{1,2}\\.\\d{1,3})[abfp]\\d{1,4}(?:c\\d+)?)(?![A-Za-z0-9])"""
    )

    fun extractVersions(value: String): List<String> =
        versionPattern.findAll(value).map { it.groupValues[1] }.distinct().toList()

    fun resolve(evidence: List<UnityVersionEvidence>): UnityVersionResult {
        val normalized = evidence.flatMap { item ->
            extractVersions(item.version).map { version -> item.copy(version = version) }
        }.distinctBy { Triple(it.version, it.source, it.path) }
        val rootMetadata = normalized.filter { it.source == "globalgamemanagers-metadata" }
        val rootText = normalized.filter { it.source == "globalgamemanagers-raw" }
        val selected = when {
            rootMetadata.isNotEmpty() -> rootMetadata
            rootText.isNotEmpty() -> rootText
            else -> normalized
        }
        val versions = selected.map { it.version }.distinct()
        if (versions.isEmpty()) {
            return UnityVersionResult(null, "not-found", null, normalized)
        }
        if (versions.size > 1) {
            val status = if (rootMetadata.isNotEmpty() || rootText.isNotEmpty()) {
                "conflicting-root-evidence"
            } else {
                "conflicting-supporting-evidence"
            }
            return UnityVersionResult(null, status, null, normalized)
        }
        val version = versions.single()
        val confidence = when {
            selected.any { it.confidence == "high" } -> "high"
            selected.any { it.confidence == "medium" } -> "medium"
            else -> "low"
        }
        val status = when {
            rootMetadata.isNotEmpty() -> "root-metadata"
            rootText.isNotEmpty() -> "root-text"
            else -> "supporting-evidence"
        }
        return UnityVersionResult(version, status, confidence, normalized)
    }
}
