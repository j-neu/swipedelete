package com.swipedelete.core.storage.saf

import java.io.InputStream
import java.io.OutputStream

/**
 * The testable seam over a DocumentsProvider tree.
 *
 * `DocumentsContract` cannot be exercised on the host JVM (Robolectric cannot
 * fake a real `DocumentsProvider`), so the SAF gateway never talks to it
 * directly. Every provider interaction goes through this interface:
 * [SafDocumentsContractAccess] is the production adapter and the **only** class
 * in the module that touches `DocumentsContract` / `ContentResolver`, and
 * [com.swipedelete.core.storage.saf.FakeDocumentTree] (test fixtures) lets the
 * walker and the gateway be tested on the host with no Android runtime.
 *
 * A [DocumentNode] is a snapshot of one document. [DocumentTreeAccess.children]
 * is a directory listing; [DocumentTreeAccess.metadata] is a single-document
 * query. `treeUri` identifies the granted tree, `documentId` identifies the
 * document inside it (opaque to callers).
 */
interface DocumentTreeAccess {
    /** The direct children of [parentDocumentId], in provider order. */
    suspend fun children(treeUri: String, parentDocumentId: String): List<DocumentNode>

    /** The authoritative metadata for [node] (a listing may omit size/mtime). */
    suspend fun metadata(node: DocumentNode): DocumentMetadata

    /** Opens [node] for reading. The caller owns the returned stream. */
    fun openStream(node: DocumentNode): InputStream

    /**
     * Opens a document the caller just created via [createDocument] for writing.
     * Required to implement copy-then-delete [SafStorageGateway.move]: SAF offers
     * no raw rename, so the bytes must be written into a new destination
     * document. The caller owns the returned stream.
     */
    fun openOutputStream(node: DocumentNode): OutputStream

    /** Deletes [node]; returns false when the provider refused. */
    suspend fun delete(node: DocumentNode): Boolean

    /** Creates a new document under [parentDocumentId] and returns it. */
    suspend fun createDocument(
        treeUri: String,
        parentDocumentId: String,
        displayName: String,
        mime: String,
    ): DocumentNode
}

/** One document in a tree. [treeUri] is the tree the node was reached through. */
data class DocumentNode(
    val treeUri: String,
    val documentId: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
)

/** The authoritative metadata of a single [DocumentNode]. */
data class DocumentMetadata(
    val sizeBytes: Long,
    val mime: String,
    val lastModified: Long,
    val displayName: String,
)
