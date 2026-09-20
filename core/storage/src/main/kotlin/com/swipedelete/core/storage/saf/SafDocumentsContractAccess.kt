package com.swipedelete.core.storage.saf

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * The production [DocumentTreeAccess]: the **only** class in `:core:storage`
 * that touches `DocumentsContract` / `ContentResolver`.
 *
 * It stays deliberately thin — URI building/parsing, one cursor read per call
 * (every cursor is closed via `use`), `createDocument` and `deleteDocument` —
 * so all of the walker's and gateway's logic is host-testable behind the seam.
 */
class SafDocumentsContractAccess(context: Context) : DocumentTreeAccess {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun children(treeUri: String, parentDocumentId: String): List<DocumentNode> {
        val parent = Uri.parse(treeUri)
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentDocumentId)
        val nodes = ArrayList<DocumentNode>()
        resolver.query(childUri, PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                nodes += cursor.toNode(treeUri)
            }
        }
        return nodes
    }

    override suspend fun metadata(node: DocumentNode): DocumentMetadata {
        val uri = documentUri(node)
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return DocumentMetadata(
                    sizeBytes = cursor.longOrZero(DocumentsContract.Document.COLUMN_SIZE),
                    mime = cursor.stringOrEmpty(DocumentsContract.Document.COLUMN_MIME_TYPE),
                    lastModified = cursor.longOrZero(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    displayName = cursor.stringOrEmpty(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                )
            }
        }
        throw FileNotFoundException("document not found: ${node.documentId}")
    }

    override fun openStream(node: DocumentNode): InputStream =
        resolver.openInputStream(documentUri(node))
            ?: throw FileNotFoundException("cannot open ${node.documentId} for reading")

    override fun openOutputStream(node: DocumentNode): OutputStream =
        resolver.openOutputStream(documentUri(node))
            ?: throw FileNotFoundException("cannot open ${node.documentId} for writing")

    override suspend fun delete(node: DocumentNode): Boolean = try {
        DocumentsContract.deleteDocument(resolver, documentUri(node))
    } catch (error: Exception) {
        false
    }

    override suspend fun createDocument(
        treeUri: String,
        parentDocumentId: String,
        displayName: String,
        mime: String,
    ): DocumentNode {
        val parent = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), parentDocumentId)
        val created = DocumentsContract.createDocument(resolver, parent, mime, displayName)
            ?: throw FileNotFoundException("provider refused to create $displayName under $parentDocumentId")
        return DocumentNode(
            treeUri = treeUri,
            documentId = DocumentsContract.getDocumentId(created),
            displayName = displayName,
            mime = mime,
            sizeBytes = 0L,
            lastModified = 0L,
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
        )
    }

    private fun documentUri(node: DocumentNode): Uri =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(node.treeUri), node.documentId)

    private fun Cursor.toNode(treeUri: String): DocumentNode {
        val mime = stringOrEmpty(DocumentsContract.Document.COLUMN_MIME_TYPE)
        return DocumentNode(
            treeUri = treeUri,
            documentId = stringOrEmpty(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            displayName = stringOrEmpty(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            mime = mime,
            sizeBytes = longOrZero(DocumentsContract.Document.COLUMN_SIZE),
            lastModified = longOrZero(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
        )
    }

    private fun Cursor.stringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return ""
        return getString(index).orEmpty()
    }

    private fun Cursor.longOrZero(column: String): Long {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return 0L
        return getLong(index)
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
