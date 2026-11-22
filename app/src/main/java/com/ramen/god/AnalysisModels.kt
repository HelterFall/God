package com.ramen.god

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Confidence of a game-analysis conclusion, separate from scan diagnostics. */
internal enum class AnalysisConfidence(val id: String) {
    CONFIRMED("confirmed"),
    STRONG("strong"),
    PROBABLE("probable"),
    WEAK("weak"),
    UNKNOWN("unknown");

    companion object {
        fun fromId(id: String): AnalysisConfidence =
            values().firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown analysis confidence: $id")
    }
}

/** State of a finding or a graph relationship, not the state of an analyzer job. */
internal enum class FindingStatus(val id: String) {
    DECODED("decoded"),
    PARTIAL("partial"),
    METADATA_ONLY("metadata-only"),
    SKIPPED("skipped"),
    UNSUPPORTED("unsupported"),
    FAILED("failed"),
    INACCESSIBLE("inaccessible"),
    UNRESOLVED("unresolved");

    companion object {
        fun fromId(id: String): FindingStatus =
            values().firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown finding status: $id")
    }
}

/** How an item of evidence was obtained. */
internal enum class EvidenceMethod(val id: String) {
    PARSED("parsed"),
    SIGNATURE("signature"),
    STRING_HEURISTIC("string-heuristic"),
    STATIC_REFERENCE("static-reference"),
    CALL_SITE("call-site"),
    RESOLVED_POINTER("resolved-pointer"),
    INFERENCE("inference"),
    MANUAL("manual");

    companion object {
        fun fromId(id: String): EvidenceMethod =
            values().firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown evidence method: $id")
    }
}

/**
 * Precise source location within a FileRecord.
 *
 * These address spaces are deliberately separate: RVA, metadata token, Unity PathID
 * and IL offset are not file byte offsets and must never be interchanged.
 * An empty locator is valid for file-level evidence.
 */
internal data class SourceLocator(
    val byteOffset: Long? = null,
    val byteLength: Long? = null,
    val rva: Long? = null,
    val metadataToken: Long? = null,
    val pathId: Long? = null,
    val ilOffset: Long? = null
) {
    init {
        require(byteOffset == null || byteOffset >= 0L) { "byteOffset must not be negative." }
        require(byteLength == null || byteLength > 0L) { "byteLength must be positive." }
        require(byteLength == null || byteOffset != null) { "byteLength requires byteOffset." }
        if (byteOffset != null && byteLength != null) {
            require(byteOffset <= Long.MAX_VALUE - byteLength) { "byteOffset + byteLength overflows." }
        }
        require(rva == null || rva in 0L..MAX_UNSIGNED_32) { "rva must fit an unsigned 32-bit value." }
        require(metadataToken == null || metadataToken in 0L..MAX_UNSIGNED_32) {
            "metadataToken must fit an unsigned 32-bit value."
        }
        require(ilOffset == null || ilOffset >= 0L) { "ilOffset must not be negative." }
    }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("byteOffset", byteOffset ?: JSONObject.NULL)
        .put("byteLength", byteLength ?: JSONObject.NULL)
        .put("rva", rva ?: JSONObject.NULL)
        .put("metadataToken", metadataToken ?: JSONObject.NULL)
        .put("pathId", pathId ?: JSONObject.NULL)
        .put("ilOffset", ilOffset ?: JSONObject.NULL)

    companion object {
        private const val MAX_UNSIGNED_32 = 0xffff_ffffL

        fun fromJsonObject(json: JSONObject): SourceLocator = SourceLocator(
            byteOffset = json.optionalLong("byteOffset"),
            byteLength = json.optionalLong("byteLength"),
            rva = json.optionalLong("rva"),
            metadataToken = json.optionalLong("metadataToken"),
            pathId = json.optionalLong("pathId"),
            ilOffset = json.optionalLong("ilOffset")
        )
    }
}

/** A parser-neutral conclusion tied to the exact analyzer run and inventory file. */
internal data class Finding(
    val id: String,
    val analyzerRunId: String,
    val sourceFileId: String,
    val category: String,
    val title: String,
    val summary: String,
    val confidence: AnalysisConfidence,
    val status: FindingStatus,
    val reason: String? = null,
    val evidenceIds: List<String> = emptyList(),
    val graphNodeIds: List<String> = emptyList(),
    val graphEdgeIds: List<String> = emptyList(),
    val parserVersion: String? = null,
    val supportedFormatRange: String? = null
) {
    init {
        requireNonBlank(id, "id")
        requireNonBlank(analyzerRunId, "analyzerRunId")
        requireNonBlank(sourceFileId, "sourceFileId")
        requireNonBlank(category, "category")
        requireNonBlank(title, "title")
        requireNonBlank(summary, "summary")
        requireValidReferences(evidenceIds, "evidenceIds")
        requireValidReferences(graphNodeIds, "graphNodeIds")
        requireValidReferences(graphEdgeIds, "graphEdgeIds")
        requireOptionalNonBlank(reason, "reason")
        requireOptionalNonBlank(parserVersion, "parserVersion")
        requireOptionalNonBlank(supportedFormatRange, "supportedFormatRange")
    }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("id", id)
        .put("analyzerRunId", analyzerRunId)
        .put("sourceFileId", sourceFileId)
        .put("category", category)
        .put("title", title)
        .put("summary", summary)
        .put("confidence", confidence.id)
        .put("status", status.id)
        .put("reason", reason ?: JSONObject.NULL)
        .put("evidenceIds", JSONArray(evidenceIds))
        .put("graphNodeIds", JSONArray(graphNodeIds))
        .put("graphEdgeIds", JSONArray(graphEdgeIds))
        .put("parserVersion", parserVersion ?: JSONObject.NULL)
        .put("supportedFormatRange", supportedFormatRange ?: JSONObject.NULL)

    companion object {
        fun create(
            scanId: String,
            findingKey: String,
            analyzerRunId: String,
            sourceFileId: String,
            category: String,
            title: String,
            summary: String,
            confidence: AnalysisConfidence = AnalysisConfidence.UNKNOWN,
            status: FindingStatus = FindingStatus.DECODED,
            reason: String? = null,
            evidenceIds: List<String> = emptyList(),
            graphNodeIds: List<String> = emptyList(),
            graphEdgeIds: List<String> = emptyList(),
            parserVersion: String? = null,
            supportedFormatRange: String? = null
        ): Finding = Finding(
            id = AnalysisModelIdentity.create("finding", scanId, findingKey),
            analyzerRunId = analyzerRunId,
            sourceFileId = sourceFileId,
            category = category,
            title = title,
            summary = summary,
            confidence = confidence,
            status = status,
            reason = reason,
            evidenceIds = evidenceIds,
            graphNodeIds = graphNodeIds,
            graphEdgeIds = graphEdgeIds,
            parserVersion = parserVersion,
            supportedFormatRange = supportedFormatRange
        )

        fun fromJsonObject(json: JSONObject): Finding = Finding(
            id = json.requiredString("id"),
            analyzerRunId = json.requiredString("analyzerRunId"),
            sourceFileId = json.requiredString("sourceFileId"),
            category = json.requiredString("category"),
            title = json.requiredString("title"),
            summary = json.requiredString("summary"),
            confidence = AnalysisConfidence.fromId(json.requiredString("confidence")),
            status = FindingStatus.fromId(json.requiredString("status")),
            reason = json.optionalString("reason"),
            evidenceIds = json.requiredStringList("evidenceIds"),
            graphNodeIds = json.requiredStringList("graphNodeIds"),
            graphEdgeIds = json.requiredStringList("graphEdgeIds"),
            parserVersion = json.optionalString("parserVersion"),
            supportedFormatRange = json.optionalString("supportedFormatRange")
        )
    }
}

/** A traceable piece of source evidence supporting one or more findings. */
internal data class Evidence(
    val id: String,
    val findingId: String,
    val analyzerRunId: String,
    val sourceFileId: String,
    val method: EvidenceMethod,
    val confidence: AnalysisConfidence,
    val description: String,
    val locator: SourceLocator? = null
) {
    init {
        requireNonBlank(id, "id")
        requireNonBlank(findingId, "findingId")
        requireNonBlank(analyzerRunId, "analyzerRunId")
        requireNonBlank(sourceFileId, "sourceFileId")
        requireNonBlank(description, "description")
    }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("id", id)
        .put("findingId", findingId)
        .put("analyzerRunId", analyzerRunId)
        .put("sourceFileId", sourceFileId)
        .put("method", method.id)
        .put("confidence", confidence.id)
        .put("description", description)
        .put("locator", locator?.toJsonObject() ?: JSONObject.NULL)

    companion object {
        fun create(
            scanId: String,
            evidenceKey: String,
            findingId: String,
            analyzerRunId: String,
            sourceFileId: String,
            method: EvidenceMethod,
            confidence: AnalysisConfidence,
            description: String,
            locator: SourceLocator? = null
        ): Evidence = Evidence(
            id = AnalysisModelIdentity.create("evidence", scanId, evidenceKey),
            findingId = findingId,
            analyzerRunId = analyzerRunId,
            sourceFileId = sourceFileId,
            method = method,
            confidence = confidence,
            description = description,
            locator = locator
        )

        fun fromJsonObject(json: JSONObject): Evidence {
            val locatorJson = json.optJSONObject("locator")
            return Evidence(
                id = json.requiredString("id"),
                findingId = json.requiredString("findingId"),
                analyzerRunId = json.requiredString("analyzerRunId"),
                sourceFileId = json.requiredString("sourceFileId"),
                method = EvidenceMethod.fromId(json.requiredString("method")),
                confidence = AnalysisConfidence.fromId(json.requiredString("confidence")),
                description = json.requiredString("description"),
                locator = if (json.isNull("locator")) null else
                    SourceLocator.fromJsonObject(locatorJson ?: throw IllegalArgumentException("locator must be a JSON object or null."))
            )
        }
    }
}

/** One typed vertex in the future cross-analyzer dependency graph. */
internal data class GraphNode(
    val id: String,
    val nodeType: String,
    val key: String,
    val label: String,
    val fileRecordId: String? = null,
    val locator: SourceLocator? = null,
    val findingIds: List<String> = emptyList(),
    val evidenceIds: List<String> = emptyList()
) {
    init {
        requireNonBlank(id, "id")
        requireNonBlank(nodeType, "nodeType")
        requireNonBlank(key, "key")
        requireNonBlank(label, "label")
        requireOptionalNonBlank(fileRecordId, "fileRecordId")
        requireValidReferences(findingIds, "findingIds")
        requireValidReferences(evidenceIds, "evidenceIds")
    }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("id", id)
        .put("nodeType", nodeType)
        .put("key", key)
        .put("label", label)
        .put("fileRecordId", fileRecordId ?: JSONObject.NULL)
        .put("locator", locator?.toJsonObject() ?: JSONObject.NULL)
        .put("findingIds", JSONArray(findingIds))
        .put("evidenceIds", JSONArray(evidenceIds))

    companion object {
        fun create(
            scanId: String,
            nodeType: String,
            key: String,
            label: String,
            fileRecordId: String? = null,
            locator: SourceLocator? = null,
            findingIds: List<String> = emptyList(),
            evidenceIds: List<String> = emptyList()
        ): GraphNode = GraphNode(
            id = AnalysisModelIdentity.create("node", scanId, "$nodeType:$key"),
            nodeType = nodeType,
            key = key,
            label = label,
            fileRecordId = fileRecordId,
            locator = locator,
            findingIds = findingIds,
            evidenceIds = evidenceIds
        )

        fun fromJsonObject(json: JSONObject): GraphNode {
            val locatorJson = json.optJSONObject("locator")
            return GraphNode(
                id = json.requiredString("id"),
                nodeType = json.requiredString("nodeType"),
                key = json.requiredString("key"),
                label = json.requiredString("label"),
                fileRecordId = json.optionalString("fileRecordId"),
                locator = if (json.isNull("locator")) null else
                    SourceLocator.fromJsonObject(locatorJson ?: throw IllegalArgumentException("locator must be a JSON object or null.")),
                findingIds = json.requiredStringList("findingIds"),
                evidenceIds = json.requiredStringList("evidenceIds")
            )
        }
    }
}

/**
 * A directed relation between graph nodes.
 *
 * targetNodeId may be null for an unresolved external target; targetHint preserves
 * the observed identifier without inventing a graph node.
 */
internal data class GraphEdge(
    val id: String,
    val sourceNodeId: String,
    val targetNodeId: String?,
    val relationType: String,
    val confidence: AnalysisConfidence,
    val status: FindingStatus,
    val evidenceIds: List<String> = emptyList(),
    val findingIds: List<String> = emptyList(),
    val analyzerRunId: String? = null,
    val targetHint: String? = null
) {
    init {
        requireNonBlank(id, "id")
        requireNonBlank(sourceNodeId, "sourceNodeId")
        requireOptionalNonBlank(targetNodeId, "targetNodeId")
        requireNonBlank(relationType, "relationType")
        requireValidReferences(evidenceIds, "evidenceIds")
        requireValidReferences(findingIds, "findingIds")
        requireOptionalNonBlank(analyzerRunId, "analyzerRunId")
        requireOptionalNonBlank(targetHint, "targetHint")
        require(targetNodeId != null || !targetHint.isNullOrBlank()) {
            "An unresolved graph edge requires targetHint when targetNodeId is absent."
        }
    }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("id", id)
        .put("sourceNodeId", sourceNodeId)
        .put("targetNodeId", targetNodeId ?: JSONObject.NULL)
        .put("relationType", relationType)
        .put("confidence", confidence.id)
        .put("status", status.id)
        .put("evidenceIds", JSONArray(evidenceIds))
        .put("findingIds", JSONArray(findingIds))
        .put("analyzerRunId", analyzerRunId ?: JSONObject.NULL)
        .put("targetHint", targetHint ?: JSONObject.NULL)

    companion object {
        fun create(
            scanId: String,
            edgeKey: String,
            sourceNodeId: String,
            targetNodeId: String?,
            relationType: String,
            confidence: AnalysisConfidence,
            status: FindingStatus,
            evidenceIds: List<String> = emptyList(),
            findingIds: List<String> = emptyList(),
            analyzerRunId: String? = null,
            targetHint: String? = null
        ): GraphEdge = GraphEdge(
            id = AnalysisModelIdentity.create("edge", scanId, edgeKey),
            sourceNodeId = sourceNodeId,
            targetNodeId = targetNodeId,
            relationType = relationType,
            confidence = confidence,
            status = status,
            evidenceIds = evidenceIds,
            findingIds = findingIds,
            analyzerRunId = analyzerRunId,
            targetHint = targetHint
        )

        fun fromJsonObject(json: JSONObject): GraphEdge = GraphEdge(
            id = json.requiredString("id"),
            sourceNodeId = json.requiredString("sourceNodeId"),
            targetNodeId = json.optionalString("targetNodeId"),
            relationType = json.requiredString("relationType"),
            confidence = AnalysisConfidence.fromId(json.requiredString("confidence")),
            status = FindingStatus.fromId(json.requiredString("status")),
            evidenceIds = json.requiredStringList("evidenceIds"),
            findingIds = json.requiredStringList("findingIds"),
            analyzerRunId = json.optionalString("analyzerRunId"),
            targetHint = json.optionalString("targetHint")
        )
    }
}

/** Stable, scan-scoped IDs shared by the parser-neutral analysis models. */
private object AnalysisModelIdentity {
    fun create(prefix: String, scanId: String, key: String): String {
        requireNonBlank(scanId, "scanId")
        requireNonBlank(key, "identity key")
        val identity = buildString {
            listOf("god-analysis-model", scanId, prefix, key).forEach { part ->
                append(part.length)
                append(':')
                append(part)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            val alphabet = "0123456789abcdef"
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
        return prefix + ":" + hex
    }
}

private fun requireNonBlank(value: String, field: String) {
    require(value.isNotBlank()) { "$field must not be blank." }
}

private fun requireOptionalNonBlank(value: String?, field: String) {
    require(value == null || value.isNotBlank()) { "$field must be null or non-blank." }
}

private fun requireValidReferences(values: List<String>, field: String) {
    require(values.all { it.isNotBlank() }) { "$field cannot contain blank IDs." }
    require(values.distinct().size == values.size) { "$field cannot contain duplicate IDs." }
}

private fun JSONObject.requiredString(field: String): String {
    val value = getString(field)
    require(value.isNotBlank()) { "$field must not be blank." }
    return value
}

private fun JSONObject.optionalString(field: String): String? =
    if (!has(field) || isNull(field)) null else getString(field)

private fun JSONObject.optionalLong(field: String): Long? {
    if (!has(field) || isNull(field)) return null
    val value = get(field)
    require(value is Number) { "$field must be a JSON number or null." }
    return when (value) {
        is Float -> {
            require(value.isFinite() && value % 1f == 0f) { "$field must be an integer." }
            value.toLong()
        }
        is Double -> {
            require(value.isFinite() && value % 1.0 == 0.0) { "$field must be an integer." }
            value.toLong()
        }
        else -> value.toLong()
    }
}

private fun JSONObject.requiredStringList(field: String): List<String> {
    val array = getJSONArray(field)
    return (0 until array.length()).map { index ->
        array.getString(index).also { require(it.isNotBlank()) { "$field cannot contain blank IDs." } }
    }
}
