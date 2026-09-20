package com.swipedelete.core.storage.saf

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * A real, filesystem-backed [DocumentsProvider] for the instrumented SAF test.
 *
 * Robolectric cannot fake a `DocumentsProvider`, so the device pass gets one:
 * the test APK hosts this provider and [SafDocumentsContractAccess] talks to it
 * through the genuine `DocumentsContract` / `ContentResolver` path. Two roots —
 * [PRIMARY] and [EXPORT] — back onto `cacheDir/saf-tree/<root>`, so a test can
 * also create and inspect files directly on the filesystem.
 *
 * **Wiring is single-sourced** so the test and the provider cannot disagree:
 * - the authority suffix is [TestDocumentsContract.AUTHORITY_SUFFIX], which the
 *   androidTest manifest declares and [resolveAuthority] searches for;
 * - the backing directory is [baseDir], computed from the *instrumentation
 *   target* application's `cacheDir` (via `ApplicationProvider`) by both the
 *   provider and the test, so they resolve to the same path even if the provider
 *   and test APK packages differ.
 *
 * Document ids are tree-relative paths: `"primary"` is the root and
 * `"primary/sub/a.gif"` a nested file.
 */
class TestDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean {
        baseDir().mkdirs()
        ROOTS.forEach { File(baseDir(), it).mkdirs() }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(ROOT_COLUMNS).apply {
            ROOTS.forEach { root ->
                val row = newRow()
                row.add(DocumentsContract.Root.COLUMN_ROOT_ID, root)
                row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, root)
                row.add(DocumentsContract.Root.COLUMN_TITLE, "Test $root")
                row.add(
                    DocumentsContract.Root.COLUMN_FLAGS,
                    DocumentsContract.Root.FLAG_LOCAL_ONLY or
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
                )
                row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
                row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, 0L)
            }
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = fileFor(documentId) ?: throw FileNotFoundException(documentId)
        return MatrixCursor(DOCUMENT_COLUMNS).also { addRow(it, documentId, file) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = fileFor(parentDocumentId) ?: throw FileNotFoundException(parentDocumentId)
        val cursor = MatrixCursor(DOCUMENT_COLUMNS)
        parent.listFiles()
            ?.sortedBy { it.name }
            ?.forEach { child -> addRow(cursor, "$parentDocumentId/${child.name}", child) }
        return cursor
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId) ?: throw FileNotFoundException(parentDocumentId)
        val created = File(parent, displayName)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            created.mkdirs()
        } else {
            created.parentFile?.mkdirs()
            created.createNewFile()
        }
        return "$parentDocumentId/$displayName"
    }

    override fun deleteDocument(documentId: String) {
        if (documentId in ROOTS) throw IllegalArgumentException("a root cannot be deleted")
        val file = fileFor(documentId) ?: throw FileNotFoundException(documentId)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileFor(documentId) ?: throw FileNotFoundException(documentId)
        if (!file.exists()) throw FileNotFoundException(file.path)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/")

    override fun getDocumentType(documentId: String): String =
        fileFor(documentId)?.let(::mimeFor) ?: DOCUMENT_MIME

    // --- Internals ---

    private fun fileFor(documentId: String): File? {
        val parts = documentId.split('/').filter { it.isNotEmpty() }
        val root = parts.firstOrNull() ?: return null
        if (root !in ROOTS) return null
        var file = File(baseDir(), root)
        for (index in 1 until parts.size) file = File(file, parts[index])
        return file
    }

    private fun addRow(cursor: MatrixCursor, documentId: String, file: File) {
        val row = cursor.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name.ifEmpty { documentId })
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeFor(file))
        row.add(DocumentsContract.Document.COLUMN_SIZE, if (file.isDirectory) 0L else file.length())
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE,
        )
    }

    private fun mimeFor(file: File): String = when {
        file.isDirectory -> DocumentsContract.Document.MIME_TYPE_DIR
        else -> when (file.name.substringAfterLast('.', "").lowercase()) {
            "gif" -> "image/gif"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            "txt" -> "text/plain"
            else -> DOCUMENT_MIME
        }
    }

    companion object {
        const val PRIMARY: String = "primary"
        const val EXPORT: String = "export"

        val ROOTS = listOf(PRIMARY, EXPORT)

        private const val DOCUMENT_MIME = "application/octet-stream"

        private val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )

        /**
         * The **one** backing directory, pinned to the instrumentation target
         * application's `cacheDir` so the provider (which may run in the test
         * APK's package) and the test body always resolve the same path.
         */
        fun baseDir(): File =
            File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "saf-tree")

        /** The directory backing [root] (`baseDir()/<root>`). */
        fun rootDir(root: String): File = File(baseDir(), root)

        /**
         * The authority of the provider this test APK installs, resolved at
         * runtime from the installed providers rather than reconstructed from a
         * package name — the manifest and this lookup then cannot disagree about
         * `<applicationId>`.
         */
        fun resolveAuthority(context: Context): String {
            val providers: List<ProviderInfo> = context.packageManager
                .queryContentProviders(null, Process.myUid(), 0)
            return providers
                .mapNotNull { it.authority }
                .firstOrNull { it.endsWith(TestDocumentsContract.AUTHORITY_SUFFIX) }
                ?: error(
                    "no provider with authority suffix " +
                        "'${TestDocumentsContract.AUTHORITY_SUFFIX}' is installed for " +
                        context.packageName,
                )
        }
    }
}
