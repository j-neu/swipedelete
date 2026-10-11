package com.swipedelete.core.storage.saf

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
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
        // The provider is installed at process start, *before* the test
        // instrumentation is registered, so ApplicationProvider (which needs a
        // registered instrumentation) throws here. Resolve the base directory
        // from the provider's own context instead, and publish it so the test
        // body reads back the identical path rather than recomputing it.
        val base = File(requireNotNull(context).cacheDir, BASE_DIR_NAME)
        resolvedBaseDir = base
        base.mkdirs()
        ROOTS.forEach { File(base, it).mkdirs() }
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

        /** The `cacheDir` subdirectory backing every root; shared with the test body. */
        const val BASE_DIR_NAME: String = "saf-tree"

        /**
         * The one backing directory, published by [TestDocumentsProvider.onCreate]
         * from the provider context. It is set before any test body runs because
         * the provider is installed at process start; the fallback only matters if
         * a caller reads the path before the provider has ever been created, and
         * resolves to the same `cacheDir` (the provider and the instrumentation
         * target share one application).
         */
        @Volatile
        private var resolvedBaseDir: File? = null

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
         * The **one** backing directory, published by the provider at install
         * time (see [resolvedBaseDir]) so the provider and the test body always
         * resolve the same path. The `ApplicationProvider` fallback resolves to
         * the same `cacheDir` when read before the provider exists.
         */
        fun baseDir(): File =
            resolvedBaseDir
                ?: File(ApplicationProvider.getApplicationContext<Context>().cacheDir, BASE_DIR_NAME)

        /** The directory backing [root] (`baseDir()/<root>`). */
        fun rootDir(root: String): File = File(baseDir(), root)

        /**
         * The authority of the provider this test APK installs, resolved at
         * runtime from the installed providers rather than reconstructed from a
         * package name — the manifest and this lookup then cannot disagree about
         * `<applicationId>`.
         *
         * It is deliberately **not** `queryContentProviders(null, uid, 0)` plus a
         * `.documents` suffix match: on this platform that call returns every
         * provider in the system (the uid argument does not filter), and several
         * system authorities end with `.documents` (`externalstorage`,
         * `downloads`, `media`). The first match was therefore the external
         * storage provider, and every tree query silently returned nothing. The
         * lookup below asks for the `DOCUMENTS_PROVIDER` action and keeps only
         * *this package's* registration, so the test provider is the only
         * candidate.
         */
        fun resolveAuthority(context: Context): String {
            val own: List<String> = context.packageManager
                .queryIntentContentProviders(Intent(TestDocumentsContract.ACTION_DOCUMENTS_PROVIDER), 0)
                .mapNotNull { it.providerInfo }
                .filter { it.packageName == context.packageName }
                .mapNotNull { it.authority }
            val expected = context.packageName + TestDocumentsContract.AUTHORITY_SUFFIX
            return own.firstOrNull { it == expected }
                ?: error(
                    "no provider '$expected' is installed for ${context.packageName}; " +
                        "found ${own.ifEmpty { listOf("<none>") }}",
                )
        }
    }
}
