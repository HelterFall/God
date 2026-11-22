package com.ramen.god

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

/**
 * Minimal read-only view of one entry in a scan tree. Keeping Android's
 * DocumentFile outside GameScanner allows the real scanner pipeline to run
 * against deterministic in-memory trees in JVM tests.
 */
internal interface ScanNode {
    val name: String?
    val isDirectory: Boolean
    val size: Long
    /** Stable provider-specific key, when one is available. Never written to reports. */
    val stableKey: String?
        get() = null
    fun listFiles(): List<ScanNode>
}

/** Opens file content for a node without exposing Android URIs to analyzers. */
internal fun interface ScanFileAccess {
    fun openInputStream(node: ScanNode): InputStream?
}

internal class SafScanNode(
    private val documentFile: DocumentFile
) : ScanNode {
    override val name: String?
        get() = documentFile.name

    override val isDirectory: Boolean
        get() = documentFile.isDirectory

    override val size: Long
        get() = documentFile.length()

    override fun listFiles(): List<ScanNode> =
        documentFile.listFiles().map { child -> SafScanNode(child) }

    internal val uri
        get() = documentFile.uri

    override val stableKey: String
        get() = uri.toString()
}

internal class SafScanFileAccess(
    private val contentResolver: ContentResolver
) : ScanFileAccess {
    override fun openInputStream(node: ScanNode): InputStream? {
        val safNode = node as? SafScanNode
            ?: throw IllegalArgumentException("SAF file access requires a SafScanNode.")
        return contentResolver.openInputStream(safNode.uri)
    }
}
