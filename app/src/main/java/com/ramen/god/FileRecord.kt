package com.ramen.god

import org.json.JSONObject
import java.security.MessageDigest

/** Where a file record's stable identity came from. Raw provider keys are never exported. */
internal enum class FileIdentitySource(val id: String) {
    PROVIDER_KEY("provider-key"),
    RELATIVE_PATH("relative-path")
}

/**
 * Status of inventory discovery only. Parser outcomes remain in analyzer coverage
 * and must not be inferred from this field.
 */
internal enum class FileInventoryStatus(val id: String) {
    DISCOVERED("discovered"),
    ENUMERATED("enumerated"),
    PARTIAL("partial"),
    INACCESSIBLE("inaccessible")
}

/**
 * Platform-independent identity and hierarchy for one discovered game entry.
 *
 * Provider keys (for example, a SAF document URI) are hashed so reports do not
 * reveal them. Nodes without a provider key use their relative path and the
 * occurrence of that name among the same directory's children.
 */
internal data class FileRecord(
    val id: String,
    val parentId: String?,
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val identitySource: FileIdentitySource,
    val inventoryStatus: FileInventoryStatus = FileInventoryStatus.DISCOVERED
) {
    fun toJsonObject(): JSONObject = JSONObject()
        .put("id", id)
        .put("parentId", parentId ?: JSONObject.NULL)
        .put("path", path)
        .put("directory", directory)
        .put("size", size)
        .put("identitySource", identitySource.id)
        .put("inventoryStatus", inventoryStatus.id)

    companion object {
        fun create(
            path: String,
            name: String,
            directory: Boolean,
            size: Long,
            parentId: String?,
            providerKey: String?,
            siblingOccurrence: Int = 0
        ): FileRecord {
            require(siblingOccurrence >= 0) { "siblingOccurrence must not be negative." }
            val usableProviderKey = providerKey?.takeIf { it.isNotBlank() }
            val source = if (usableProviderKey == null) {
                FileIdentitySource.RELATIVE_PATH
            } else {
                FileIdentitySource.PROVIDER_KEY
            }
            val identityInput = usableProviderKey?.let { "provider-key:${it.length}:$it" }
                ?: buildString {
                    val parentIdentity = parentId.orEmpty()
                    append("relative-path:")
                    append(parentIdentity.length).append(':').append(parentIdentity)
                    append(path.length).append(':').append(path)
                    append('#').append(siblingOccurrence)
                }
            return FileRecord(
                id = stableId(identityInput),
                parentId = parentId,
                path = path,
                name = name,
                directory = directory,
                size = if (directory) 0L else size.coerceAtLeast(0L),
                identitySource = source
            )
        }

        private fun stableId(identityInput: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(identityInput.toByteArray(Charsets.UTF_8))
            val hex = buildString(digest.size * 2) {
                val alphabet = "0123456789abcdef"
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(alphabet[value ushr 4])
                    append(alphabet[value and 0x0f])
                }
            }
            return "file:$hex"
        }
    }
}
