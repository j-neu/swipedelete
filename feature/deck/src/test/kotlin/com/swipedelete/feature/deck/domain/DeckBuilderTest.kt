package com.swipedelete.feature.deck.domain

import com.swipedelete.core.model.Category
import com.swipedelete.core.model.ItemId
import com.swipedelete.core.model.SessionId
import com.swipedelete.core.model.SourceId
import com.swipedelete.core.storage.PageToken
import com.swipedelete.core.storage.fake.FakeStorageGateway
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §5.1 deck construction. A deck is materialised from the read-only scan port
 * (D1), filtered per smart rule, and ordered by the documented default: oldest
 * first for folder/age decks, largest first for space decks.
 */
class DeckBuilderTest {

    // --- Folder decks ---------------------------------------------------------

    @Test
    fun `folder deck resolves the source and orders items oldest first`() = runTest {
        val source = sourceRef("memes")
        val newest = mediaItem("new", "content://tree/memes/new.jpg", dateAdded = 3_000L)
        val oldest = mediaItem("old", "content://tree/memes/old.jpg", dateAdded = 1_000L)
        val middle = mediaItem("mid", "content://tree/memes/mid.jpg", dateAdded = 2_000L)
        val gateway = FakeStorageGateway().apply { addSource(source) }
        // The scanner yields newest-first to prove the builder applies the default.
        val port = FakeScanPort(page(listOf(newest, middle, oldest)))
        val builder = DeckBuilder({ port }, gateway, FixedClock(9_000L))

        val snapshot = builder.build(DeckQuery.Folder(source.id))

        assertEquals(listOf(ItemId("old"), ItemId("mid"), ItemId("new")), snapshot.items.map { it.id })
        assertEquals(DeckQuery.Folder(source.id), snapshot.query)
        assertEquals(9_000L, snapshot.builtAt)
    }

    @Test
    fun `folder deck collects every page the scan port emits`() = runTest {
        val source = sourceRef("s")
        val first = mediaItem("a", "content://tree/s/a.jpg", dateAdded = 1L)
        val second = mediaItem("b", "content://tree/s/b.jpg", dateAdded = 2L)
        val port = FakeScanPort(
            listOf(
                page(listOf(first), next = PageToken("p2")),
                page(listOf(second), next = null, truncated = true),
            ),
        )
        val gateway = FakeStorageGateway().apply { addSource(source) }
        val builder = DeckBuilder({ port }, gateway, FixedClock(0L))

        val snapshot = builder.build(DeckQuery.Folder(source.id))

        assertEquals(listOf(ItemId("a"), ItemId("b")), snapshot.items.map { it.id })
    }

    @Test
    fun `folder deck of a source that was never granted is empty`() = runTest {
        val builder = DeckBuilder({ FakeScanPort(page(emptyList())) }, FakeStorageGateway(), FixedClock(0L))

        assertTrue(builder.build(DeckQuery.Folder(SourceId("never-granted"))).items.isEmpty())
    }

    @Test
    fun `a truncated scan is carried on the snapshot so a partial deck is observable`() = runTest {
        val source = sourceRef("s")
        val capped = FakeScanPort(
            page(listOf(mediaItem("a", "content://tree/s/a.jpg")), next = null, truncated = true),
        )
        val gateway = FakeStorageGateway().apply { addSource(source) }

        val snapshot = DeckBuilder({ capped }, gateway, FixedClock(0L)).build(DeckQuery.Folder(source.id))

        assertTrue(snapshot.truncated, "Task 30: a capped walk must say so, not look complete")
        assertEquals(1, snapshot.scanned, "the snapshot must carry how much the walk examined")
    }

    @Test
    fun `a complete scan is not marked truncated`() = runTest {
        val source = sourceRef("s")
        val gateway = FakeStorageGateway().apply { addSource(source) }
        val port = FakeScanPort(page(listOf(mediaItem("a", "content://tree/s/a.jpg"))))

        val snapshot = DeckBuilder({ port }, gateway, FixedClock(0L)).build(DeckQuery.Folder(source.id))

        assertTrue(!snapshot.truncated)
    }

    @Test
    fun `a smart deck aggregates truncation across every source it scans`() = runTest {
        val first = sourceRef("first")
        val second = sourceRef("second")
        val gateway = FakeStorageGateway().apply {
            addSource(first)
            addSource(second)
        }
        val ports = mapOf(
            first.id to FakeScanPort(page(listOf(mediaItem("a", "content://tree/first/a.jpg", sourceId = first.id)))),
            second.id to FakeScanPort(
                page(listOf(mediaItem("b", "content://tree/second/b.jpg", sourceId = second.id)), truncated = true),
            ),
        )
        val builder = DeckBuilder({ source -> ports.getValue(source.id) }, gateway, FixedClock(0L))

        val snapshot = builder.build(DeckQuery.Smart(SmartRule.LARGEST_FIRST))

        assertTrue(snapshot.truncated, "one truncated source makes the whole deck partial")
        assertEquals(2, snapshot.scanned)
    }

    // --- Smart decks ----------------------------------------------------------

    @Test
    fun `screenshots rule keeps screenshot items and drops everything else`() = runTest {
        val source = sourceRef("s")
        val byBucket = mediaItem(
            "shot-bucket",
            "content://tree/s/DCIM/Screenshots/1.png",
            bucket = "Screenshots",
            relativePath = "DCIM/Screenshots",
            mime = "image/png",
            dateAdded = 2L,
        )
        val byPath = mediaItem(
            "shot-path",
            "content://tree/s/Pictures/Screenshots/2.png",
            bucket = null,
            relativePath = "Pictures/Screenshots",
            mime = "image/png",
            dateAdded = 1L,
        )
        val camera = mediaItem(
            "camera",
            "content://tree/s/DCIM/Camera/3.jpg",
            bucket = "Camera",
            relativePath = "DCIM/Camera",
        )
        val port = FakeScanPort(page(listOf(byBucket, camera, byPath)))
        val gateway = FakeStorageGateway().apply { addSource(source) }

        val snapshot = DeckBuilder({ port }, gateway, FixedClock(0L))
            .build(DeckQuery.Smart(SmartRule.SCREENSHOTS))

        // Oldest first, and the camera item is excluded.
        assertEquals(listOf(ItemId("shot-path"), ItemId("shot-bucket")), snapshot.items.map { it.id })
    }

    @Test
    fun `videos over the threshold are kept and ordered largest first`() = runTest {
        val source = sourceRef("s")
        val justUnder = mediaItem("small", "content://tree/s/small.mp4", mime = "video/mp4", sizeBytes = 99L * MB)
        val over = mediaItem("over", "content://tree/s/over.mp4", mime = "video/mp4", sizeBytes = 200L * MB)
        val bigger = mediaItem("bigger", "content://tree/s/bigger.mp4", mime = "video/mp4", sizeBytes = 500L * MB)
        val image = mediaItem("image", "content://tree/s/big.jpg", mime = "image/jpeg", sizeBytes = 900L * MB)
        val port = FakeScanPort(page(listOf(justUnder, over, image, bigger)))
        val gateway = FakeStorageGateway().apply { addSource(source) }

        val snapshot = DeckBuilder({ port }, gateway, FixedClock(0L))
            .build(DeckQuery.Smart(SmartRule.VIDEOS_OVER))

        assertEquals(listOf(ItemId("bigger"), ItemId("over")), snapshot.items.map { it.id })
    }

    @Test
    fun `older than rule uses the clock to keep items past the window, oldest first`() = runTest {
        val source = sourceRef("s")
        val now = 100L * DAY_MS
        val ancient = mediaItem("ancient", "content://tree/s/ancient.jpg", dateAdded = now - 400L * DAY_MS)
        val justPast = mediaItem("past", "content://tree/s/past.jpg", dateAdded = now - 91L * DAY_MS)
        val boundary = mediaItem("boundary", "content://tree/s/boundary.jpg", dateAdded = now - 90L * DAY_MS)
        val fresh = mediaItem("fresh", "content://tree/s/fresh.jpg", dateAdded = now - 10L * DAY_MS)
        val port = FakeScanPort(page(listOf(justPast, fresh, boundary, ancient)))
        val gateway = FakeStorageGateway().apply { addSource(source) }

        val snapshot = DeckBuilder({ port }, gateway, FixedClock(now))
            .build(DeckQuery.Smart(SmartRule.OLDER_THAN))

        // Strictly older than 90 days: the exact 90-day boundary stays.
        assertEquals(listOf(ItemId("ancient"), ItemId("past")), snapshot.items.map { it.id })
    }

    @Test
    fun `unreviewed keeps every candidate until the review ledger is wired`() = runTest {
        val source = sourceRef("s")
        val first = mediaItem("a", "content://tree/s/a.jpg", dateAdded = 1L)
        val second = mediaItem("b", "content://tree/s/b.jpg", dateAdded = 2L)
        val port = FakeScanPort(page(listOf(second, first)))
        val gateway = FakeStorageGateway().apply { addSource(source) }

        val snapshot = DeckBuilder({ port }, gateway, FixedClock(0L))
            .build(DeckQuery.Smart(SmartRule.UNREVIEWED))

        assertEquals(listOf(ItemId("a"), ItemId("b")), snapshot.items.map { it.id })
    }

    @Test
    fun `largest first scans every granted source and orders by size descending`() = runTest {
        val first = sourceRef("first")
        val second = sourceRef("second")
        val gateway = FakeStorageGateway().apply {
            addSource(first)
            addSource(second)
        }
        val ports = mapOf(
            first.id to FakeScanPort(
                page(
                    listOf(
                        mediaItem("a-small", "content://tree/first/a.jpg", sizeBytes = 1L * MB, sourceId = first.id),
                        mediaItem("a-large", "content://tree/first/b.jpg", sizeBytes = 8L * MB, sourceId = first.id),
                    ),
                ),
            ),
            second.id to FakeScanPort(
                page(listOf(mediaItem("b-mid", "content://tree/second/c.jpg", sizeBytes = 4L * MB, sourceId = second.id))),
            ),
        )
        val builder = DeckBuilder({ source -> ports.getValue(source.id) }, gateway, FixedClock(0L))

        val snapshot = builder.build(DeckQuery.Smart(SmartRule.LARGEST_FIRST))

        assertEquals(
            listOf(ItemId("a-large"), ItemId("b-mid"), ItemId("a-small")),
            snapshot.items.map { it.id },
        )
    }

    // --- Module / resume bridges ---------------------------------------------

    @Test
    fun `module deck is empty until M3 supplies the module output`() = runTest {
        val builder = DeckBuilder({ FakeScanPort(page(emptyList())) }, FakeStorageGateway(), FixedClock(0L))

        val snapshot = builder.build(DeckQuery.Module(Category.EXACT_DUPLICATE))

        assertTrue(snapshot.items.isEmpty())
        assertEquals(DeckQuery.Module(Category.EXACT_DUPLICATE), snapshot.query)
    }

    @Test
    fun `resume deck is materialised by the session layer, not the builder`() = runTest {
        val builder = DeckBuilder({ FakeScanPort(page(emptyList())) }, FakeStorageGateway(), FixedClock(0L))

        val failure = assertFailsWith<IllegalStateException> {
            builder.build(DeckQuery.Resume(SessionId("session-1")))
        }

        assertTrue(failure.message!!.contains("Resume"), failure.message)
    }

    // --- Provenance -----------------------------------------------------------

    @Test
    fun `provenance renders why, where, how big and when`() {
        val added = LocalDate.of(2021, 3, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val screenshot = mediaItem(
            "shot",
            "content://tree/s/Pictures/Screenshots/IMG_1.png",
            bucket = "Screenshots",
            relativePath = "Pictures/Screenshots",
            mime = "image/png",
            sizeBytes = 4_404_019L,
            dateAdded = added,
        )

        assertEquals("Screenshots · /Pictures/Screenshots · 4.2 MB · 14 Mar 2021", provenance(screenshot))
    }

    @Test
    fun `provenance falls back to the folder name and relative path without a bucket`() {
        val added = LocalDate.of(2021, 3, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val plain = mediaItem(
            "plain",
            "content://tree/s/Download/installer.apk",
            bucket = null,
            relativePath = "Download",
            mime = "application/vnd.android.package-archive",
            sizeBytes = 2L * MB,
            dateAdded = added,
        )

        assertEquals("Download · /Download · 2.0 MB · 14 Mar 2021", provenance(plain))
    }

    @Test
    fun `provenance falls back to the modification date when there is no add date`() {
        val modified = LocalDate.of(2020, 1, 2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val item = mediaItem(
            "no-add-date",
            "content://tree/s/note.txt",
            bucket = "Documents",
            relativePath = "Documents",
            mime = "text/plain",
            sizeBytes = 0L,
            dateAdded = 0L,
            dateModified = modified,
        )

        assertEquals("Documents · /Documents · 0 B · 2 Jan 2020", provenance(item))
    }
}
