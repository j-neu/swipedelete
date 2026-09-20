package com.swipedelete.app.di

import android.content.Context
import android.net.Uri
import com.swipedelete.core.storage.Reconciler
import java.io.File

/**
 * Carry-forward 4: [Reconciler] needs to know whether an item's *current* URI can
 * still be opened, and M0-T18 deliberately has no default for that probe because
 * "exists" is platform-specific.
 *
 * - A `content://` URI exists when it can be opened. A missing document, a
 *   revoked grant or an unplugged volume makes `openInputStream` throw, so the
 *   whole probe is wrapped in `runCatching` and a throw is reported as `false`.
 *   A probe that threw would abort the entire startup pass; reporting `false`
 *   instead lets the reconciler conclude "abandoned" for that row and keep going.
 * - Anything else is a filesystem path and defers to [File.exists].
 *
 * Opening a stream to answer the question costs one binder round-trip and no
 * read: the stream is closed immediately by `use`.
 */
class ContentResolverExistenceProbe(private val context: Context) {

    suspend fun exists(candidate: String): Boolean {
        if (candidate.startsWith(CONTENT_SCHEME)) {
            return runCatching {
                context.contentResolver.openInputStream(Uri.parse(candidate))?.use { true } ?: false
            }.getOrDefault(false)
        }
        return File(candidate).exists()
    }

    private companion object {
        const val CONTENT_SCHEME = "content://"
    }
}
