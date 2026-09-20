package com.swipedelete.app.di

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Carry-forward 4: the [Reconciler]'s `exists` probe is supplied by the app layer.
 * It must answer for both `content://` URIs and plain filesystem paths, and a
 * probe that cannot answer (an unknown authority, a revoked grant, a vanished
 * removable volume) must return `false` rather than throw — a thrown probe would
 * otherwise abort the whole startup pass (Task 18 guards per row as well).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentResolverExistenceProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val probe = ContentResolverExistenceProbe(context)

    @Test
    fun `a filesystem path is answered through File`() = runTest {
        val file = Files.createTempFile("sd-probe-", ".bin").toFile()
        try {
            assertTrue(probe.exists(file.absolutePath), "an existing file path must probe true")
            assertFalse(
                probe.exists(File(file.parentFile, "absent-${file.name}").absolutePath),
                "a missing file path must probe false",
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a content uri that cannot be opened is false and never throws`() = runTest {
        val uri = Uri.parse("content://com.swipedelete.absent/nothing/1")
        // Robolectric answers unregistered authorities with an empty stream, so the
        // failure path is armed explicitly: the provider lookup itself throws, which
        // is exactly what a revoked grant or an unplugged volume does on-device.
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            throw FileNotFoundException("no document behind $uri")
        }

        assertFalse(
            probe.exists(uri.toString()),
            "a content URI that cannot be opened must probe false, not throw",
        )
    }
}
