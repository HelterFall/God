package com.ramen.god

import org.json.JSONObject
import java.security.MessageDigest

/** Per-file outcome of one analyzer within one scan. */
internal enum class AnalyzerRunStatus(val id: String) {
    QUEUED("queued"),
    ANALYZED("analyzed"),
    PARTIAL("partial"),
    SKIPPED("skipped"),
    UNSUPPORTED("unsupported"),
    FAILED("failed")
}

/**
 * Links one analyzer outcome to the exact inventory record it inspected.
 *
 * The ID is unique within a scan and deterministic for the tuple
 * (scanId, analyzerId, inputFileId). A new scan receives a new scanId.
 */
internal data class AnalyzerRun(
    val id: String,
    val analyzerId: String,
    val inputFileId: String,
    val status: AnalyzerRunStatus,
    val reason: String? = null
) {
    fun toJsonObject(): JSONObject = JSONObject()
        .put("id", id)
        .put("analyzerId", analyzerId)
        .put("inputFileId", inputFileId)
        .put("status", status.id)
        .put("reason", reason ?: JSONObject.NULL)

    companion object {
        fun create(
            scanId: String,
            analyzerId: String,
            inputFileId: String,
            status: AnalyzerRunStatus = AnalyzerRunStatus.QUEUED,
            reason: String? = null
        ): AnalyzerRun {
            require(scanId.isNotBlank()) { "scanId must not be blank." }
            require(analyzerId.isNotBlank()) { "analyzerId must not be blank." }
            require(inputFileId.isNotBlank()) { "inputFileId must not be blank." }
            val identity = buildString {
                append("analyzer-run:")
                append(scanId.length).append(':').append(scanId)
                append(analyzerId.length).append(':').append(analyzerId)
                append(inputFileId.length).append(':').append(inputFileId)
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
            return AnalyzerRun(
                id = "run:$hex",
                analyzerId = analyzerId,
                inputFileId = inputFileId,
                status = status,
                reason = reason
            )
        }
    }
}
