# SwipeDelete — M1 (The core loop) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A 500-item deck triages end to end at ≥ 60 fps with full undo: MediaStore catalogue, pure-Kotlin deck engine, swipe surface, staged deletes, five-slot dock, viewer, sessions — all degrading to Tier 0 (AC-19) and gated on startup reconciliation.

**Architecture:** M1 sits on the frozen M0 safety spine (`ProtectedRoots → Journaling → Quarantine → concrete`, `docs/M0-ACCEPTANCE.md`). The deck engine is pure Kotlin with no Android imports, so gesture sequences are property-tested. Two M0-deferred seams are opened here — a read-only `SourceScanPort` for scan progress/truncation, and Activity-bound capability request launchers — both without changing the frozen `StorageGateway`/`Page` contract. Every destructive action passes `RecoveryCoordinator.awaitReady()` first.

**Tech Stack:** the resolved M0 catalog (`gradle/libs.versions.toml`): Kotlin 2.3.21, AGP 8.13.2, Compose BOM 2026.06.01, Hilt 2.58, Room 2.8.5, Media3 1.11.1, Coil 3.6.3, navigation 2.9.8, coroutines 1.11.0. No new third-party test dependency is introduced (see D3).

**Spec:** `docs/SPEC.md` v0.2. Plan of record: `docs/tasks.md`. This file expands the M1 section of that plan to execution depth; `docs/tasks.md` remains the milestone map and AC-coverage table.

---

## Global constraints

Inherited unchanged from `docs/tasks.md` → Global Constraints (1–26). The load-bearing ones for M1, restated so a task brief is self-contained:

- **AC-8** operations act on a frozen snapshot; an item that changed on disk aborts with a visible message.
- **AC-9** bytes are measured; quarantine bytes are **reclaimable**, never "freed" (`formatFreed`/`formatReclaimable`).
- **AC-15** cancelling any operation leaves the original untouched.
- **AC-19** every feature works, narrower, without a restricted permission — no dead end, and each narrowed panel names what it excludes and offers an in-context grant.
- Decision 3: **staged delete by default**, explicit Apply. Decision 4: `MOVE` when a destination is set, `TAG` offered. Decision 5: viewer dismisses on swipe-down, dock via a button. Decision 9: audio is in scope.
- No `INTERNET`; no background destructive work; `:core:model` and the deck domain stay Android-free.

---

## Pre-flight decisions (settle blockers D1–D6)

These resolve the four pre-M1 blockers plus two catalog gaps found while planning. Each is an adopted default; the product owner may override before the owning task starts.

### D1 — Scan progress/truncation: a new read-only port, the frozen contract untouched

M0 left no way to surface the SAF walker's truncation flag or scan progress: the only injectable storage type is the decorated `StorageGateway` (`StorageModule.kt:237`), and `SafStorageGateway.enumerateDetailed`/`WalkPage` are unreachable through it (`docs/M0-ACCEPTANCE.md`, Finding 1).

**Decision:** add `SourceScanPort` in `:core:storage` — a **read-only** port returning `ScanPage(items, next, truncated, scanned, total?)`. The frozen `StorageGateway`, `Page` and `StorageGatewayContractTest` are unchanged. `SafStorageGateway` and `RawStorageGateway` implement it (both already walk internally); `MediaStore` uses its cursor count. `StorageGraphHandle` gains a third sanctioned accessor, `scanPort: SourceScanPort?`, because the concrete gateway is constructed inside the provider and cannot be bound (binding it reopens the AC-1/AC-7 bypass). Read-only, so it does not weaken the handle's invariant; `StorageGraphOrderTest`'s expected surface is updated from two entry points to three, with the rationale.

Owner: **Task 28 (M1-T01a)**.

### D2 — Capability requests: Activity-bound launchers at the affordance

`AndroidCapabilityController`'s `requestMediaPermissions` / `openAllFilesAccessSettings` seams are deliberately `null` in the Hilt singleton (`CapabilityModule.kt:23-27`) because they are Activity-bound and an Application-scoped singleton must not hold an Activity.

**Decision:** keep the singleton for reads (`tier()`) and add `rememberCapabilityRequester()` in `:app` — a `@Composable` that builds an `AndroidCapabilityController` from `rememberLauncherForActivityResult` (runtime media permissions) and an `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` intent launcher, suspending on the callback result. Feature UI calls it at the affordance; the Hilt singleton is never a launcher holder. The gate is asked about `Feature.WholeLibraryDecks` (not `DeckTriage`) for whole-library decks (M0 carry-forward, `progress.md:114`).

Owner: **Task 41 (M1-T14)**, consumed by Task 30 (M1-T03) and Task 39 (M1-T13).

### D3 — No new test dependency: seeded randomized property tests

`docs/tasks.md` names Kotest and Turbine, but neither is in `gradle/libs.versions.toml` and adding unverified versions contradicts the M0 catalog discipline. **Decision:** property tests use `kotlin.test` + `kotlin.random.Random(seed)` with fixed seeds and a bounded iteration count; Flow assertions use `kotlinx-coroutines-test` (`runTest`, `.first()`), already present. If the owner later wants Kotest, it becomes a catalog task, not an M1 assumption.

### D4 — Task numbering

Tasks are numbered **Task 28 … Task 41**, continuing after the `Task 27` boundary marker, preserving the `M1-Txx` mapping used by the spec-coverage tables.

### D5 — Deck engine location: `:feature:deck` `domain`, pure Kotlin

`DeckState`/`Gesture`/`DeckReducer` live in `feature/deck/src/main/kotlin/com/swipedelete/feature/deck/domain/` and import no `android.*` (M1-T02). `:feature:deck` is currently an empty library module with no dependencies (`feature/deck/build.gradle.kts`); Tasks 28–41 add Compose/Hilt/test deps as the first consumer needs them, not up front.

### D6 — Recovery readiness is a hard precondition

Every destructive entry point (`quarantine`, `move`, `restore`, `deletePermanently`) is reached only after `RecoveryCoordinator.awaitReady()` returns (`RecoveryCoordinator.kt:70`). If reconciliation failed, readiness stays `false` and mutation is blocked (the safe direction). Task 40 (M1-T13) adds the architecture test that a deck cannot mutate before readiness.

### D7 — Coil is unusable at compileSdk 36 (discovered during wave-1 integration)

`io.coil-kt.coil3:coil-compose:3.6.3` (the catalog pin) resolves `androidx.compose.*:1.12.0`, which requires `compileSdk 37` **and** AGP 9.1.0. This project is pinned to `compileSdk 36` / AGP 8.13.2 (`gradle/libs.versions.toml`), so adding Coil fails the AAR-metadata check outright (28 issues, verified on 2026-09-24). The `coil` catalog entries stay unused until the toolchain moves to AGP 9 + compileSdk 37. **Card thumbnails decode with `ImageDecoder` at an explicit target size**, the same path `:feature:viewer` already uses. Any M1 task that reaches for Coil must not.

---

## File structure

**Created**

- `core/storage/.../scan/SourceScanPort.kt`, `ScanPage.kt` — the read-only scan port (D1).
- `feature/deck/.../domain/DeckState.kt`, `Gesture.kt`, `DeckReducer.kt` — pure engine.
- `feature/deck/.../domain/DeckBuilder.kt`, `DeckQuery.kt` — deck construction + frozen snapshot.
- `feature/deck/.../session/SessionController.kt`, `UndoStack.kt` — session model + undo (pure Kotlin).
- `feature/deck/.../ui/DeckScreen.kt`, `SwipeCard.kt`, `StagingStrip.kt`, `SlotDock.kt`, `DeckActionBar.kt`.
- `feature/deck/.../ui/DeckViewModel.kt`, `SessionSummaryScreen.kt`.
- `feature/viewer/.../ViewerScreen.kt`, `ImageViewer.kt`, `GifViewer.kt`, `AudioViewer.kt`, `VideoViewer.kt`, `ApkCard.kt`, `UnsupportedCard.kt`.
- `app/.../permissions/CapabilityRequester.kt` — Compose launcher seam (D2).

**Modified**

- `gradle/libs.versions.toml`, `feature/deck/build.gradle.kts`, `feature/viewer/build.gradle.kts` — Compose/Hilt/Media3/Coil + test wiring.
- `core/storage/.../saf/SafStorageGateway.kt`, `raw/RawStorageGateway.kt` — implement `SourceScanPort`.
- `app/.../di/StorageModule.kt` — handle carries `scanPort`.
- `app/src/test/.../di/StorageGraphOrderTest.kt` — expected handle surface becomes three entry points (D1).
- `app/.../nav/AppNavHost.kt` — wire `deck`, `viewer/{itemId}` destinations.

---

### Task 28 (M1-T01a): `SourceScanPort` and scan progress (D1)

**Files:**
- Create: `core/storage/src/main/kotlin/com/swipedelete/core/storage/scan/SourceScanPort.kt`, `ScanPage.kt`
- Modify: `core/storage/src/main/kotlin/com/swipedelete/core/storage/saf/SafStorageGateway.kt` (implement port; delegate to `enumerateDetailed`)
- Modify: `core/storage/src/main/kotlin/com/swipedelete/core/storage/raw/RawStorageGateway.kt`
- Modify: `app/src/main/kotlin/com/swipedelete/app/di/StorageModule.kt` (`StorageGraphHandle.scanPort`)
- Modify: `app/src/test/kotlin/com/swipedelete/app/di/StorageGraphOrderTest.kt`
- Test: `core/storage/src/test/kotlin/com/swipedelete/core/storage/scan/SourceScanPortTest.kt`

**Interfaces:**
- Produces:
```kotlin
/** A page of a scan, carrying the progress and truncation the frozen Page cannot. */
data class ScanPage(
    val items: List<MediaItem>,
    val next: PageToken?,
    val truncated: Boolean,
    val scanned: Int,
)

/** Read-only; never mutates. Sorted stably for a given source. */
interface SourceScanPort {
    fun scan(source: SourceRef, page: PageToken? = null): Flow<ScanPage>
}
```
- `StorageGraphHandle` gains `val scanPort: SourceScanPort?` (null when the concrete gateway cannot scan, e.g. a future MediaStore-only build).

- [ ] **Step 1: Write the failing test**

```kotlin
class SourceScanPortTest {
    @Test fun `scan reports truncation when the walker hits its entry cap`() = runTest {
        val gateway = SafStorageGateway(context, grantStore, quarantineStore = null)
        val pages = gateway.scan(source, null).toList()
        assertTrue(pages.last().truncated, "a capped walk must say so, not look complete")
    }
    @Test fun `scan yields the same items as the frozen enumerate`() = runTest {
        assertEquals(gateway.enumerate(source, null).toList().flatMap { it.items },
                     gateway.scan(source, null).toList().flatMap { it.items })
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `.\gradlew.bat :core:storage:testDebugUnitTest --tests "*SourceScanPortTest"` → FAIL, unresolved `scan`.
- [ ] **Step 3: Implement** — `SafStorageGateway.scan` maps its existing `enumerateDetailed` `WalkPage` to `ScanPage` (`truncated` from the walker flag; `scanned` cumulative). `RawStorageGateway` does the same for its own walk. Read-only: no decorator, no journal.
- [ ] **Step 4: Pass** — the same command → PASS.
- [ ] **Step 5: Wire the handle and update the frozen-surface test**

`StorageModule` passes `scanPort = concrete as? SourceScanPort`; `StorageGraphHandle` exposes it. In `StorageGraphOrderTest`, the expected exposed set becomes `setOf(StorageGateway::class.java, Reconciler::class.java, SourceScanPort::class.java)` with the comment that the port is read-only.

- [ ] **Step 6: Commit** — `feat(core-storage): read-only scan port for progress and truncation (D1)`.

---

### Task 29 (M1-T01): `MediaStoreStorageGateway`

**Files:**
- Create: `core/storage/src/main/kotlin/com/swipedelete/core/storage/mediastore/MediaStoreStorageGateway.kt`, `MediaStoreCursors.kt`
- Test: `core/storage/src/test/kotlin/com/swipedelete/core/storage/mediastore/MediaStoreCursorsTest.kt` (Robolectric cursor fixtures)
- Test: `core/storage/src/androidTest/kotlin/com/swipedelete/core/storage/mediastore/MediaStoreStorageGatewayInstrumentedTest.kt`

**Interfaces:**
- Produces: `class MediaStoreStorageGateway(context: Context) : StorageGateway`. Projection from §4.6: `_ID, RELATIVE_PATH, SIZE, DATE_ADDED, DATE_MODIFIED, MIME_TYPE, WIDTH, HEIGHT, DURATION, BUCKET_DISPLAY_NAME, IS_TRASHED, IS_PENDING, OWNER_PACKAGE_NAME`. `watch()` uses a `ContentObserver` registered on the collection URI. `MediaItem.mediaStoreId` is the `_ID`; `MediaItem.id` is `"mediastore:<_ID>"`.
- **AC-10 enforcement point:** this gateway enumerates media only. It is the documented alternative to All Files Access for media (SPEC §4.2 condition 2).

- [ ] **Step 1: Write the failing cursor test** — build a `MatrixCursor` with the projection, assert `MediaStoreCursors.toMediaItem(cursor)` maps every column and paging is stable.
- [ ] **Step 2: Run to verify it fails** — `.\gradlew.bat :core:storage:testDebugUnitTest --tests "*MediaStoreCursorsTest"` → FAIL.
- [ ] **Step 3: Implement** — cursors wrapped so the whole query is paged (`LIMIT/OFFSET` on `_ID` ordering) and cancellable between pages; `IS_TRASHED`/`IS_PENDING` items are excluded from decks.
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Extend the frozen contract on a device** — `MediaStoreStorageGateway` runs `StorageGatewayContractTest` (instrumented), overriding `armCancellation` per the M0 carry-forward (`progress.md:75`).
- [ ] **Step 6: Commit** — `feat(core-storage): mediastore gateway with paged cursors (AC-10)`.

---

### Task 30 (M1-T03): Deck builder and frozen snapshot (AC-8)

**Files:**
- Create: `feature/deck/src/main/kotlin/com/swipedelete/feature/deck/domain/DeckQuery.kt`, `DeckBuilder.kt`
- Test: `feature/deck/src/test/kotlin/com/swipedelete/feature/deck/domain/DeckBuilderTest.kt`, `StaleItemAbortTest.kt`

**Interfaces:**
- Consumes: `SourceScanPort`, `StorageGateway.metadata`, `SourceGrantRepository`.
- Produces:
```kotlin
sealed interface DeckQuery {
    data class Folder(val source: SourceId) : DeckQuery
    data class Smart(val rule: SmartRule) : DeckQuery   // SCREENSHOTS, VIDEOS_OVER(sizeMb), OLDER_THAN(days), UNREVIEWED, LARGEST_FIRST
    data class Module(val category: Category) : DeckQuery   // M3 bridge
    data class Resume(val session: SessionId) : DeckQuery
}
class DeckBuilder(scanner: (SourceRef) -> SourceScanPort, gateway: StorageGateway, clock: Clock) {
    suspend fun build(query: DeckQuery): DeckSnapshot
}
/** Frozen at build: ids + metadata captured; re-checked at presentation. */
data class DeckSnapshot(val items: List<MediaItem>, val query: DeckQuery, val builtAt: Long)
fun staleReason(expected: MediaItem, actual: Metadata): String?   // null = still valid
```
- Provenance line: `Screenshots · /Pictures/Screenshots · 4.2 MB · 14 Mar 2021`, built by a pure `fun provenance(item: MediaItem): String`.

- [ ] **Step 1: Write the failing tests** — folder deck preserves scanner order (oldest first); smart deck filters per rule; a size/mtime change between build and presentation yields a non-null `staleReason`.
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:deck:testDebugUnitTest --tests "*DeckBuilderTest"` → FAIL.
- [ ] **Step 3: Implement** — no Android imports; scanning uses `SourceScanPort` (D1), so truncation is observable to the UI.
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(deck): deck builder with frozen snapshot and stale detection (AC-8)`.

---

### Task 31 (M1-T02): Pure-Kotlin deck engine and property tests

**Files:**
- Create: `feature/deck/src/main/kotlin/com/swipedelete/feature/deck/domain/DeckState.kt`, `Gesture.kt`, `DeckReducer.kt`
- Test: `feature/deck/src/test/kotlin/com/swipedelete/feature/deck/domain/DeckInvariantPropertyTest.kt`

**Interfaces:**
- Produces:
```kotlin
enum class Gesture { DELETE, KEEP, DEFER, MOVE, UNDO }
data class Deferred(val item: ItemId, val count: Int)
data class DeckState(
    val items: List<ItemId>, val cursor: Int,
    val staged: List<ItemId>, val kept: List<ItemId>,
    val deferred: List<Deferred>, val decided: List<ItemId>,
    val history: List<DeckAction>,
)
sealed interface DeckAction { /* Delete, Keep, Defer, Move(slot), Undo(target) */ }
object DeckReducer {
    fun reduce(state: DeckState, gesture: Gesture, slot: SlotId? = null): DeckState
    fun undo(state: DeckState): DeckState
}
```
- Invariants (property-tested, seeded): an id is never both staged and kept; `history` is invertible; a deferred item re-queues at most 3 times then lands in "decide later"; no repeat within a session.

- [ ] **Step 1: Write the failing property test**

```kotlin
class DeckInvariantPropertyTest {
    @Test fun `no item is ever both staged and kept`() {
        repeat(2000) { seed ->
            var s = DeckState(items = (1..30).map { ItemId("i$it") }, cursor = 0, staged = emptyList(),
                kept = emptyList(), deferred = emptyList(), decided = emptyList(), history = emptyList())
            val rnd = Random(seed)
            repeat(200) { s = DeckReducer.reduce(s, Gesture.entries.random(rnd)) }
            assertTrue(s.staged.intersect(s.kept.toSet()).isEmpty(), "seed=$seed")
        }
    }
    @Test fun `history is invertible`() { /* replay undo to the initial state, assert equality */ }
}
```

- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:deck:testDebugUnitTest --tests "*DeckInvariantPropertyTest"` → FAIL.
- [ ] **Step 3: Implement** the reducer as pure functions; no `android.*` imports.
- [ ] **Step 4: Pass** — 2000 seeded sequences green.
- [ ] **Step 5: Commit** — `feat(deck): pure deck engine with seeded property tests (@9.2)`.

---

### Task 32 (M1-T04): Swipe surface

**Files:**
- Modify: `feature/deck/build.gradle.kts` (add Compose BOM, ui, foundation, material3, activity-compose, hilt, test deps)
- Create: `feature/deck/.../ui/SwipeCard.kt`
- Test: `feature/deck/src/test/kotlin/com/swipedelete/feature/deck/ui/SwipeGestureTest.kt`

**Interfaces:**
- Produces: `@Composable fun SwipeCard(item: MediaItem, onDecision: (Gesture) -> Unit, modifier: Modifier = Modifier)`.
- Thumbnails decode with `android.graphics.ImageDecoder` at an explicit target size (D7) — **do not add Coil**.
- Direction commits on threshold **and** velocity; progressive tint; the destructive direction carries colour + icon + **text** ("Delete"), never colour alone (§11.4).

- [ ] **Step 1: Write the failing Compose test** — drag past threshold and release commits `DELETE`; a slow drag under threshold snaps back and commits nothing; a fling commits at a lower distance.
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:deck:testDebugUnitTest --tests "*SwipeGestureTest"` → FAIL.
- [ ] **Step 3: Implement** with `Modifier.pointerInput` + `Animatable`; expose `contentDescription` and a semantics action per direction so TalkBack can commit without a gesture.
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(deck): threshold+velocity swipe surface with icon+text destructive signal`.

---

### Task 33 (M1-T05): Staging strip and Apply

**Files:**
- Create: `feature/deck/.../ui/StagingStrip.kt`
- Test: `feature/deck/src/test/kotlin/com/swipedelete/feature/deck/ui/StagingApplyTest.kt`, `ApplyConfirmationTest.kt`

**Interfaces:**
- Consumes: `DeckState.staged`, `StorageGateway.quarantine`, `RecoveryCoordinator.awaitReady`.
- Produces: `@Composable fun StagingStrip(staged: List<MediaItem>, onUndo: () -> Unit, onApply: () -> Unit)`; the Apply path calls `awaitReady()` (D6) then `quarantine`, and shows one confirmation listing count, bytes and a preview grid (§6.6).

- [ ] **Step 1: Write the failing tests** — a swipe stages but performs no gateway call; Apply calls `quarantine` once with the whole staged set; the confirmation names count and bytes.
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:deck:testDebugUnitTest --tests "*StagingApplyTest"` → FAIL.
- [ ] **Step 3: Implement** — staged-by-default (decision 3); no gateway call before Apply.
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(deck): staged deletes with Apply confirmation (decision 3)`.

---

### Task 34 (M1-T06): Five-slot dock and cross-volume move (AC-15)

**Files:**
- Create: `feature/deck/.../ui/SlotDock.kt`
- Test: `feature/deck/src/test/kotlin/com/swipedelete/feature/deck/ui/SlotDockTest.kt`, `MoveCollisionTest.kt`, `CrossVolumeMoveCancelTest.kt`

**Interfaces:**
- Consumes: `Slot` (`SlotMode.MOVE|TAG`), `StorageGateway.move`.
- Produces: dock begins at ~12 dp, magnetic snap within ~48 dp, drop outside cancels; a slot with a revoked grant greys out with a reason on tap; `MOVE` default when `destinationUri != null`; `TAG` writes a `Tag` only.

- [ ] **Step 1: Write the failing tests** — collision renames `a.gif` → `a (1).gif` and never overwrites; cancelling a cross-volume move leaves the original byte-identical (AC-15).
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:deck:testDebugUnitTest --tests "*SlotDockTest"` → FAIL.
- [ ] **Step 3: Implement** — reuses the M0 gateway's existing no-overwrite contract; no new move logic.
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(deck): five-slot dock with collision-safe, cancellable moves (AC-15)`.

---

### Task 35 (M1-T07): Undo stack and history strip

**Files:**
- Create: `feature/deck/.../session/UndoStack.kt`, `feature/deck/.../ui/HistoryStrip.kt`
- Test: `feature/deck/src/test/kotlin/com/swipedelete/feature/deck/session/SessionUndoTest.kt`

**Interfaces:**
- Produces: `class UndoStack` over `DeckAction`s; any entry revertible individually (not just the last); `@Composable fun HistoryStrip(history, onRevert: (Int) -> Unit)`.

- [ ] **Step 1: Write the failing test** — revert a **middle** entry and assert the rest survive; two-finger tap reverts the last.
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:deck:testDebugUnitTest --tests "*SessionUndoTest"` → FAIL.
- [ ] **Step 3: Implement** using `DeckReducer.undo` semantics; a move/keep revert is a gateway call, a staged revert is state-only.
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(deck): session-wide undo stack and history strip`.

---

### Task 36 (M1-T08): Viewer — image, GIF, audio

**Files:**
- Modify: `feature/viewer/build.gradle.kts` (Compose, Coil, Media3, Hilt)
- Create: `feature/viewer/.../ViewerScreen.kt`, `ImageViewer.kt`, `GifViewer.kt`, `AudioViewer.kt`
- Test: `feature/viewer/src/test/.../ViewerImageTest.kt`, `ViewerGifTest.kt`, `ViewerOrientationTest.kt`

**Interfaces:**
- Consumes: `StorageGateway.openStream`.
- Produces: `@Composable fun ViewerScreen(itemId: ItemId, pager: ViewerPager)`; full-resolution decode, pinch/double-tap zoom, EXIF orientation respected; animated GIF/WebP loop with play/pause; audio transport controls; dismiss on swipe-down, dock via a button (decision 5).

- [ ] **Step 1: Write the failing tests** — EXIF-rotated JPEG renders rotated (orientation respected); GIF toggles play/pause; audio shows transport + duration.
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:viewer:testDebugUnitTest --tests "*ViewerImageTest"` → FAIL.
- [ ] **Step 3: Implement** with `ImageDecoder` + explicit target size; never hold full-resolution bitmaps in the pager.
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(viewer): image, GIF and audio viewers with EXIF orientation`.

---

### Task 37 (M1-T09): Viewer — video

**Files:**
- Create: `feature/viewer/.../VideoViewer.kt`
- Test: `feature/viewer/src/test/.../ViewerVideoControlsTest.kt`, `feature/viewer/src/androidTest/.../VideoViewerInstrumentedTest.kt`

**Interfaces:**
- Produces: Media3 `ExoPlayer` play/pause, scrub with thumbnail preview, ±10 s, speed 0.25×–3×, frame step, loop toggle, audio-track selection; audio always available.

- [ ] **Step 1: Write the failing test** — control callbacks map to the expected player ops (frame step pauses first; speed clamps at the ends).
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:viewer:testDebugUnitTest --tests "*ViewerVideoControlsTest"` → FAIL.
- [ ] **Step 3: Implement**; exactly one player instance per viewer (M1-T11 owns the ceiling).
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(viewer): media3 video with scrub, speed and frame step`.

---

### Task 38 (M1-T10): Viewer — APK, documents, unsupported

**Files:**
- Create: `feature/viewer/.../ApkCard.kt`, `UnsupportedCard.kt`
- Test: `feature/viewer/src/test/.../ViewerApkTest.kt`, `ViewerUnsupportedTest.kt`

**Interfaces:**
- Produces: APK card (icon, package, version, requested permissions, signature, target SDK, installed-version comparison via targeted `PackageManager` visibility — **no `QUERY_ALL_PACKAGES`**); document metadata + "Open with"; an honest error naming the codec/MIME plus "Open with" for undecodable items, never a blank frame.

- [ ] **Step 1: Write the failing tests** — an undecodable item names its MIME and offers "Open with"; APK card shows the installed-version comparison.
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:viewer:testDebugUnitTest --tests "*ViewerApkTest"` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(viewer): APK, document and unsupported-item handling`.

---

### Task 39 (M1-T11): Autoplay, preloading and memory ceiling

**Files:**
- Create: `feature/deck/.../ui/AutoplayPolicy.kt`, `feature/deck/.../media/Preloader.kt`
- Test: `feature/deck/src/test/.../AutoplayPolicyTest.kt`, `feature/deck/src/androidTest/.../MemoryCeilingInstrumentedTest.kt`

**Interfaces:**
- Produces: `class AutoplayPolicy(memoryCeiling: Long)` with a state machine `NORMAL → DROP_PRELOAD → DROP_AUTOPLAY`; at most one active decoder plus one preloaded item; decoders released on `onStop`; autoplay cancelled on fling.

- [ ] **Step 1: Write the failing test** — crossing the ceiling drops preloading before autoplay and never throws; a fling cancels autoplay.
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:deck:testDebugUnitTest --tests "*AutoplayPolicyTest"` → FAIL.
- [ ] **Step 3: Implement** with an explicit ceiling and graceful degradation rather than an OOM.
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(deck): autoplay policy, preloading and memory ceiling`.

---

### Task 40 (M1-T12, M1-T13): Sessions, resume, summary and measured bytes (AC-9)

**Files:**
- Create: `feature/deck/.../session/SessionController.kt`, `feature/deck/.../ui/SessionSummaryScreen.kt`
- Modify: `core/database/.../dao/DeckSessionDao.kt` (persist `cursor` per decision)
- Test: `feature/deck/src/test/.../SessionResumeTest.kt`, `SessionSummaryTest.kt`, `app/src/test/.../NoMutationBeforeRecoveryTest.kt`

**Interfaces:**
- Consumes: `RecoveryCoordinator.awaitReady` (D6), `formatFreed`/`formatReclaimable`.
- Produces: `DeckSession` persisted per decision; crash/exit restores the exact cursor; the summary reports reviewed/kept/moved per slot/staged/deferred plus **measured** bytes and quarantine bytes as **reclaimable**; a prominent "restore everything from this session" panic button; exiting with a non-empty staging strip prompts Apply / Keep staged / Discard.

- [ ] **Step 1: Write the failing tests** — resume restores the cursor after a simulated process death; the summary renders "reclaimable", never "freed"; a destructive call before `ready` is refused.
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:deck:testDebugUnitTest --tests "*SessionResumeTest" --tests "*SessionSummaryTest"` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(deck): sessions, resume, summary and measured bytes (AC-9)`.

---

### Task 41 (M1-T14): Loop accessibility and capability requests (AC-19, D2)

**Files:**
- Create: `feature/deck/.../ui/DeckActionBar.kt`, `app/src/main/kotlin/com/swipedelete/app/permissions/CapabilityRequester.kt`
- Modify: `app/.../nav/AppNavHost.kt` (wire `deck`, `viewer/{itemId}`)
- Test: `feature/deck/src/test/.../DeckAccessibilityTest.kt`, `app/src/test/.../TieredDeckNarrowingTest.kt`

**Interfaces:**
- Produces: bottom action bar (Delete / Keep / Move / Skip) mirroring every swipe; dock reachable by tapping "Move"; custom accessibility actions; haptics (light tick on threshold, distinct on drop, error on failure); `@Composable fun rememberCapabilityRequester(): CapabilityController` (D2) wired from `rememberLauncherForActivityResult`, asking the gate about `Feature.WholeLibraryDecks`.

- [ ] **Step 1: Write the failing tests** — every swipe gesture has a button equivalent; at Tier 0 the whole-library affordance is `NARROWED` (not `UNAVAILABLE`) and offers a grant; requesting photo/video moves Tier 0 → Tier 1 without touching storage.
- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :feature:deck:testDebugUnitTest --tests "*DeckAccessibilityTest"` → FAIL.
- [ ] **Step 3: Implement** the requester and the action bar.
- [ ] **Step 4: Pass** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(deck): accessible action bar and in-context capability requests (AC-19)`.

---

## Self-review

**1. Spec coverage.** §5.1 deck construction → Task 30; §5.2 gestures → Tasks 31/32; §5.3 slots → Task 34; §5.4 viewer → Tasks 36–38; §5.5 sessions/undo/journal → Tasks 35/40; §4.6 MediaStore-first + AC-10 → Task 29; §9.2 pure engine → Task 31; §11.4/AC-19 → Task 41; AC-8 → Task 30; AC-9 → Task 40; AC-15 → Task 34; the two M0-deferred seams → Tasks 28/41.

**2. Placeholder scan.** No "TBD"/"implement later". UI tasks describe the test intent and the exact composable/interface; core tasks carry real test code.

**3. Type consistency.** All M0 types (`MediaItem`, `Slot`, `SlotMode`, `DeckSession`, `Category`, `ItemId`, `SlotId`, `SessionId`, `SourceRef`, `PageToken`, `Metadata`, `MoveResult`, `QuarantineResult`, `RestoreResult`) are reused unchanged. New M1 types (`DeckState`, `Gesture`, `DeckAction`, `DeckQuery`, `DeckSnapshot`, `ScanPage`, `SourceScanPort`, `UndoStack`, `AutoplayPolicy`) are defined once and reused.

## Gaps and risks

- **V6–V9 remain deferred** to M2/M3/M4 per the master plan; M1 does not depend on them (V9 burst detection is M3-T05's dependency, not M1's).
- **Device coverage:** M1's exit criterion (500-item deck ≥ 60 fps) needs a mid-range device or the API 36 emulator; the API 26/30/33 gap stays owned by the M1+ matrix task (`docs/M0-ACCEPTANCE.md`, Finding 8).
- **`RawStorageGateway` (Tier 2) is still unwired** (`StorageModule.kt:222-233`); M1's deck works at Tier 0/1 and Task 30 uses the scan port, so Tier 2 wiring is not an M1 blocker but remains open for M3 non-media modules.
- **M1 depth is step-level for the safety-critical tasks and test-intent-level for pure UI tasks**; per-task briefs (`task-N-brief.md`) carry the full code at dispatch, per the project's SDD flow.
