package com.swipedelete.core.storage.saf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * An in-memory [DocumentTreeAccess] for host tests.
 *
 * It is deliberately deterministic: children are returned in insertion order,
 * metadata is derived from the stored node, and every failure is armed per
 * document id so a test can reproduce a deleted child, an unreadable directory
 * or a refused delete exactly. It replaces the brief's "Robolectric
 * `DocumentsContract` tree fixtures": Robolectric cannot fake a real
 * `DocumentsProvider`, so the seam is faked instead and the walker/gateway are
 * tested on the JVM with no Android runtime.
 */
class FakeDocumentTree(
    val treeUri: String = "tree://test",
    val rootDocumentId: String = "root",
) : DocumentTreeAccess {

    private class Entry(
        var node: DocumentNode,
        var content: ByteArray,
        val children: MutableList<String> = mutableListOf(),
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val childrenFailures = LinkedHashMap<String, String>()
    private val metadataFailures = LinkedHashMap<String, String>()
    private val deleteFailures = LinkedHashMap<String, String>()

    /** Number of provider listing calls, so tests can assert a walk is not rescanning. */
    var childrenCalls: Int = 0
        private set

    /** Number of per-document metadata calls. */
    var metadataCalls: Int = 0
        private set

    init {
        entries[rootDocumentId] = Entry(
            node = DocumentNode(treeUri, rootDocumentId, "", MIME_DIR, 0L, 0L, isDirectory = true),
            content = ByteArray(0),
        )
    }

    // --- Fixture construction ---

    fun addDirectory(parentDocumentId: String, documentId: String, displayName: String): DocumentNode {
        val node = DocumentNode(treeUri, documentId, displayName, MIME_DIR, 0L, 0L, isDirectory = true)
        attach(parentDocumentId, node, ByteArray(0))
        return node
    }

    fun addFile(
        parentDocumentId: String,
        documentId: String,
        displayName: String,
        mime: String = mimeFor(displayName),
        content: ByteArray = displayName.toByteArray(),
    ): DocumentNode {
        val node = DocumentNode(treeUri, documentId, displayName, mime, content.size.toLong(), 0L, isDirectory = false)
        attach(parentDocumentId, node, content)
        return node
    }

    fun failChildren(documentId: String, reason: String = "unreadable_children") {
        childrenFailures[documentId] = reason
    }

    fun failMetadata(documentId: String, reason: String = "unreadable_metadata") {
        metadataFailures[documentId] = reason
    }

    fun failDelete(documentId: String, reason: String = "delete_refused") {
        deleteFailures[documentId] = reason
    }

    fun document(documentId: String): DocumentNode? = entries[documentId]?.node

    fun contentOf(documentId: String): ByteArray? = entries[documentId]?.content

    fun childIds(documentId: String): List<String> = entries[documentId]?.children?.toList().orEmpty()

    // --- DocumentTreeAccess ---

    override suspend fun children(treeUri: String, parentDocumentId: String): List<DocumentNode> {
        childrenCalls++
        childrenFailures[parentDocumentId]?.let { throw IOException(it) }
        val parent = entries[parentDocumentId] ?: throw FileNotFoundException(parentDocumentId)
        return parent.children.map { childId ->
            entries[childId]?.node ?: throw FileNotFoundException(childId)
        }
    }

    override suspend fun metadata(node: DocumentNode): DocumentMetadata {
        metadataCalls++
        metadataFailures[node.documentId]?.let { throw IOException(it) }
        val stored = entries[node.documentId] ?: throw FileNotFoundException(node.documentId)
        return DocumentMetadata(
            sizeBytes = stored.node.sizeBytes,
            mime = stored.node.mime,
            lastModified = stored.node.lastModified,
            displayName = stored.node.displayName,
        )
    }

    override fun openStream(node: DocumentNode): InputStream {
        val stored = entries[node.documentId] ?: throw FileNotFoundException(node.documentId)
        return ByteArrayInputStream(stored.content)
    }

    override fun openOutputStream(node: DocumentNode): OutputStream {
        val stored = entries[node.documentId] ?: throw FileNotFoundException(node.documentId)
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                stored.content = toByteArray()
                stored.node = stored.node.copy(sizeBytes = stored.content.size.toLong())
            }
        }
    }

    override suspend fun delete(node: DocumentNode): Boolean {
        deleteFailures[node.documentId]?.let { return false }
        if (node.documentId == rootDocumentId) return false
        val removed = entries.remove(node.documentId) ?: return false
        entries.values.forEach { it.children.remove(removed.node.documentId) }
        return true
    }

    override suspend fun createDocument(
        treeUri: String,
        parentDocumentId: String,
        displayName: String,
        mime: String,
    ): DocumentNode {
        val documentId = "$parentDocumentId/$displayName"
        val node = DocumentNode(treeUri, documentId, displayName, mime, 0L, 0L, isDirectory = mime == MIME_DIR)
        attach(parentDocumentId, node, ByteArray(0))
        return node
    }

    private fun attach(parentDocumentId: String, node: DocumentNode, content: ByteArray) {
        val parent = entries[parentDocumentId] ?: throw FileNotFoundException(parentDocumentId)
        if (entries.containsKey(node.documentId)) throw IOException("duplicate document ${node.documentId}")
        entries[node.documentId] = Entry(node, content)
        parent.children += node.documentId
    }

    companion object {
        const val MIME_DIR: String = "vnd.android.document/directory"

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "gif" -> "image/gif"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
