# SwipeDelete — Implementation Task Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build SwipeDelete — a safety-first, gesture-driven Android triage tool for accumulated junk media, where nothing is destroyed on first action and every destructive decision is one item at a time with the file visible.

**Architecture:** Gradle multi-module clean-ish architecture (data/domain/UI split by module, §9.1). A `StorageGateway` abstraction is wrapped by decorators that enforce journal write-ahead and protected roots structurally, so no feature can bypass them. Capability **tiers** (0/1/2) replace build flavours; one APK degrades rather than breaks. Deletion is always a move into app-managed quarantine (30 days, hash-verified restore). The deck engine is pure Kotlin with no Android dependencies so it is exhaustively unit- and property-testable.

**Tech Stack:** Kotlin 2.x, Jetpack Compose + Material 3, Hilt, Room, DataStore (Proto), Coil 3, Media3/ExoPlayer, WorkManager, Paging 3, kotlinx-coroutines/Flow, Turbine + Robolectric (JUnit4), Kotest property testing, Macrobenchmark, Baseline Profiles, R8.

**Spec:** `docs/SPEC.md` (v0.2 draft). Sections 1, 4, 6 and 12 are load-bearing and are never traded against a feature.

---

## How to read this plan

- **Task IDs:** executable tasks are numbered `Task 1`…`Task 26` so the Superpowers SDD tooling (`task-brief`, `review-package`) can extract them by heading. Each heading keeps its original ID: `Task 1`–`Task 9` are the `V1`–`V9` verification tasks, and `Task 10`–`Task 26` are `M0-T01`–`M0-T17`. Milestone M1 onward is re-planned per milestone and is not yet numbered; `Task 27` is a boundary marker, not work.
- **Task sizing:** a task is the smallest unit that carries its own test cycle and is worth a fresh reviewer's gate. Each task ends with an independently testable deliverable.
- **Steps:** every task is a RED → GREEN → REFACTOR → COMMIT cycle. Each step is one action.
- **Commands:** shown as `./gradlew`; on Windows use `.\gradlew.bat`. Unit tests are host-side (Robolectric where Android APIs are needed); device tests are marked.
- **Commits:** Conventional Commits (`feat:`, `fix:`, `test:`, `chore:`, `docs:`). Commit only at a task's final step.
- **Depth:** M0 and M1 are specified to execution depth (interfaces, test code, algorithms). M2–M5 are specified to task granularity (files, interfaces, tests, acceptance mapping) and must be re-planned task-by-task against the then-current code as each milestone starts — their interfaces depend on M0/M1 landing. This is deliberate, not holes.
- **AC mapping:** every acceptance criterion in §12 is owned by at least one task (see [Acceptance-criteria coverage](#acceptance-criteria-coverage)).

### Adopted decisions (spec `[OPEN]` items)

The spec leaves these open. Each has a recommendation; the plan adopts the recommendation as a **working assumption** so tasks are executable. The product owner may override any of these before the owning task starts — the task text names the assumption it depends on.

| # | Question (§15) | Adopted default | Owning task |
|---|---|---|---|
| 1 | App name | Keep `SwipeDelete` as working title; package `com.swipedelete` | M0-T01 |
| 2 | Positioning | File manager / media organiser, one listing, both declarations | M5-T06 |
| 3 | Staged vs immediate delete | **Staged by default**, explicit Apply | M1-T05 |
| 4 | Slot default mode | `MOVE` when a destination is set; `TAG` offered per slot | M1-T06 |
| 5 | Viewer vertical gesture | Dismiss on swipe-down; dock via a button | M1-T08 |
| 6 | Source grant cap | 20 persisted grants, surfaced and revocable | M0-T16 |
| 7 | Quarantine location | App external files dir (same-volume rename) | M0-T08 |
| 8 | Quarantine expiry | Never auto-purge; notify + require an explicit action | M0-T08 |
| 9 | Audio in scope | Yes — viewer + decks support audio | M1-T08 |
| 10 | Shizuku / Wireless ADB | Out of scope for v1 | — |
| 11 | Locales | English at launch; all strings externalised, RTL-correct from day one | M0-T14 |
| 12 | Update checking | No in-app updater; user opens the release page in a browser | M5-T02 |
| 13 | Minimum device | Mid-range 2020 device as the perf budget baseline (§11.1) | M5-T04 |
| 14 | Screenshots | Smart deck by default; also assignable to a slot | M3-T04 |

### Global Constraints

Copied from the spec; every task's requirements implicitly include these.

1. `minSdk 26`, `targetSdk 36`, `compileSdk 36`. Kotlin 2.x, AGP and 16 KB page-size requirement verified at implementation time. (`compileSdk` is deliberately pinned to **36**, not 37: the resolved catalog in `gradle/libs.versions.toml` is AGP 8.13.2, whose maximum supported API is 36.1, and Compose BOM 2026.06.01 requires `minCompileSdk 35`; a 37 pin is not buildable on this toolchain.)
2. **No `INTERNET` permission in any build** (AC-6). No network stack linked, no analytics, no telemetry, no ads, no identifiers, no remote crash reporting (§11.3).
3. **One APK, one Play listing**, capability tiers 0/1/2, plus a `-PnoAllFilesAccess` contingency build that strips Tier 2 (AC-19).
4. **Media always goes through `MediaStore`.** All Files Access is used only for *non-media* file management. A file with a media MIME type or media extension can never be classified into a non-media category (AC-10, §4.2 condition 2).
5. **No file operation without a prior committed journal entry** (AC-1). Write-ahead: `PENDING` → op → `DONE`/`FAILED`.
6. **No destructive bulk operation without the user having seen the enumerated set** (AC-2), except a per-category opt-in that is unavailable for media-eligible categories.
7. **30-day quarantine with hash-verified restore** before anything is destroyed (AC-3).
8. **A file's category is never derived from its parent folder name alone** (AC-4).
9. **Process death at any point leaves no item in an unknown state** (AC-5); startup reconciliation resolves every `PENDING` record.
10. **Protected roots are enforced in the storage-gateway layer, not the UI** (AC-7).
11. Operations act on a **frozen snapshot**; externally changed items abort with a visible message (AC-8).
12. **Bytes freed is measured, never estimated.** Quarantine bytes are "reclaimable", never "freed" (AC-9).
13. **No media-eligible category is pre-selected**; "review in deck" is its primary action (§6.4).
14. **Sanity ceilings** force list review instead of a one-tap action (AC-12).
15. **The word "cache" never appears on a destructive surface applied to a heuristic set** (AC-11).
16. **Every number in the UI expands into the exact file list that produced it** (AC-13).
17. **No automatic or scheduled destructive action exists** — automation prepares and notifies only (AC-14).
18. **Cancelling any operation leaves the original untouched** (AC-15).
19. **Compression is non-destructive**: new file only, verified before the original is offered, EXIF orientation and timestamps preserved or the operation is refused (AC-16).
20. **Restore recreates original paths and MediaStore rows; conflicts never overwrite** (AC-17).
21. **The journal is always exportable and complete** (AC-18).
22. **Every feature works, narrower, without any restricted permission; no dead ends** (AC-19).
23. Performance budgets (§11.1): cold start to deck ≤ 1.2 s; swipe frame ≤ 16 ms sustained ≥ 60 fps; thumbnail ≤ 100 ms cached / ≤ 400 ms first; 50k MediaStore index ≤ 60 s resumable; scan RSS ≤ 150 MB with no OOM at 100k items; autoplay start ≤ 300 ms.
24. Crash-free session rate ≥ 99.5% (§11.2).
25. Accessibility (§11.4): every gesture has a button equivalent; touch targets ≥ 48 dp; colour is never the sole signal; 200% font scale, reduced motion, high contrast, landscape, tablets/foldables.
26. **No destructive background work, ever** (§9.6).

---

## Phase 0 — Verification tasks

These resolve the spec's `[VERIFY]` markers. Each deliverable is a dated note appended to `docs/ANDROID-CONSTRAINTS.md` (create the file in V1) with the claim, the primary source, and the exact version/API level. Run these **before** the task that depends on them. Where a note changes the plan, the note must say which task edits are required.

### Task 1 (V1): Toolchain, SDK targets and 16 KB page size

- [ ] Confirm current Play `targetSdk` requirement and current Android release `compileSdk` against the Play Console help centre and the Android API levels page. Record exact values.
- [ ] Confirm current stable AGP, Kotlin, Compose BOM, Hilt, Room, Media3, Coil, WorkManager, Paging versions; record them for the version catalog.
- [ ] Confirm the 16 KB page-size native-alignment requirement and whether any planned dependency ships native code that must comply.
- [ ] Create `docs/ANDROID-CONSTRAINTS.md` with a table: claim | value | source URL | date checked.
- [ ] Commit: `docs: record verified toolchain and SDK constraints (V1)`.

### Task 2 (V2): Persisted SAF grant limits

- [ ] Determine the practical `takePersistableUriPermission` limit (documented or observed) on a low-end device and an emulator; test 20 grants and record behaviour at the cap.
- [ ] Record the failure mode when exceeded (thrown exception vs silent eviction) and the mitigation (surface + revoke oldest).
- [ ] Deliverable: note in `docs/ANDROID-CONSTRAINTS.md`; confirms or amends M0-T16's cap of 20.

### Task 3 (V3): Quarantine move semantics (rename vs copy)

- [ ] Confirm whether a same-volume rename from a SAF/raw source into the app external files dir is O(1), per API level, including across mount points (`/storage/emulated/0` vs `/storage/XXXX-XXXX`).
- [ ] Define the fallback: copy → verify (SHA-256 + size) → delete original, with cancellation leaving the original untouched (AC-15).
- [ ] Deliverable: note; confirms or amends M0-T08.

### Task 4 (V4): MediaStore reconciliation after a move

- [ ] Determine the per-API mechanism to update the index so gallery apps do not show a ghost after quarantining an indexed file: `ContentResolver.delete` on the row, `MediaStore.createTrashRequest`, `IS_PENDING`, or `ScannerConnection`.
- [ ] Record the path for API 26–29 (legacy), 30+, and 33+.
- [ ] Deliverable: note; confirms or amends M0-T08 and M1-T01.

### Task 5 (V5): Delete/trash request limits and availability

- [ ] Confirm the maximum URI count per `MediaStore.createDeleteRequest` (spec asserts 2000) and the exact exception when exceeded.
- [ ] Confirm `createTrashRequest` availability per API level and whether trashed items are auto-purged by the OS and when.
- [ ] Deliverable: note; confirms or amends the batch-chunking constant in M0-T08/M1-T05.

### Task 6 (V6): Per-app storage measurement

- [ ] Confirm `StorageStatsManager` / `StorageStatsManager.getUidBytes` or the documented app-cache measurement route, and what is measurable vs not (other apps' internal cache is not).
- [ ] Confirm `StatFs` fields for real total/used/free and the correct "usable bytes" definition used by the OS for the measured-freed delta (§6.5).
- [ ] Deliverable: note; confirms or amends M2-T01.

### Task 7 (V7): Media3 Transformer coverage and licensing

- [ ] Confirm `Transformer` codec/format coverage for the target formats, HEVC licensing implications, and minimum Media3 version.
- [ ] Confirm whether FFmpeg is avoidable for v1; if not, record the licence review requirement.
- [ ] Deliverable: note; confirms or amends M4-T06.

### Task 8 (V8): Foreground service declaration

- [ ] Confirm current Play requirements for foreground service type `dataSync` including the declaration form and justification text.
- [ ] Deliverable: note; confirms or amends M4-T07.

### Task 9 (V9): Burst / live-photo / RAW detection

- [ ] Confirm any `MediaStore` grouping for bursts, and how to identify live photos and RAW+JPEG pairs on API 26–36.
- [ ] Deliverable: note; confirms or amends M3-T05.

---

## Milestone M0 — Foundation

**Exit criteria:** AC-1, AC-3, AC-5 pass; a file can be quarantined, the app killed mid-operation, and restored correctly.

**Result (2026-09-20, Task 26):** M0 accepted on the API 36 emulator against real files, a real Room journal and the assembled graph — AC-1, AC-3, AC-5 and AC-7 verified (AC-5 by abandoned-coroutine + real `Reconciler`, not an OS kill); API 26/30/33 coverage remains an open gap. See `docs/M0-ACCEPTANCE.md`.

### Task 10 (M0-T01): Repository, Gradle multi-module skeleton and CI

**Files:**
- Create: `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `core/model/build.gradle.kts`, `core/common/build.gradle.kts`, `core/database/build.gradle.kts`, `core/storage/build.gradle.kts`, `core/media/build.gradle.kts`, `core/designsystem/build.gradle.kts`
- Create: `feature/deck/build.gradle.kts`, `feature/viewer/build.gradle.kts`, `feature/dashboard/build.gradle.kts`, `feature/cleaner/build.gradle.kts`, `feature/duplicates/build.gradle.kts`, `feature/compress/build.gradle.kts`, `feature/settings/build.gradle.kts`
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: module graph from §9.1 and a `libs.versions.toml` every later task adds to. Namespace convention `com.swipedelete.<module-path>` (e.g. `com.swipedelete.core.model`). Application id `com.swipedelete.app`.

- [ ] **Step 1: Initialise the repository and ignore rules**

Run: `git init` in the project root. Write `.gitignore` covering `*.iml`, `.gradle/`, `build/`, `local.properties`, `.idea/`, `.kotlin/`, `*.apk`, `*.aab`, `captures/`, `.cxx/`.

- [ ] **Step 2: Write the Gradle skeleton**

Write `settings.gradle.kts` declaring all modules with `include(...)`, a root `build.gradle.kts` applying `com.android.application`/`com.android.library`/`org.jetbrains.kotlin.android` false-by-default (plugins declared `apply false`), and `gradle/libs.versions.toml` with the exact versions from V1. Root `gradle.properties`: `org.gradle.jvmargs=-Xmx4g`, `android.useAndroidX=true`, `kotlin.code.style=official`, `org.gradle.caching=true`, `org.gradle.configuration-cache=true`.

- [ ] **Step 3: Write the app module and an empty Application class**

`app/src/main/AndroidManifest.xml` declares **no** permissions yet and a `com.swipedelete.app.SwipeDeleteApplication`. `app/build.gradle.kts`: `compileSdk`, `minSdk`, `targetSdk` per V1, `buildFeatures { compose = true }`, and a `noAllFilesAccess` build flag placeholder (`manifestPlaceholders["noAllFilesAccess"] = "false"`) wired in M0-T13.

- [ ] **Step 4: Run the build to verify it works**

Run: `./gradlew help` then `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Add CI**

`.github/workflows/ci.yml`: JDK 17, Gradle cache, `./gradlew :app:assembleDebug testDebugUnitTest lintDebug`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: gradle multi-module skeleton and CI"
```

### Task 11 (M0-T02): Domain model (`:core:model`)

**Files:**
- Create: `core/model/src/main/kotlin/com/swipedelete/core/model/Ids.kt` (`ItemId`, `SourceId`, `BatchId`, `SessionId`, `SlotId` value classes)
- Create: `MediaItem.kt`, `SourceRef.kt`, `Slot.kt`, `DeckSession.kt`, `JournalEntry.kt`, `Tag.kt`, `KeepMark.kt`, `StorageSnapshot.kt`, `ProtectedRoot.kt`, `Category.kt`, `Capacity.kt`, `Result.kt`
- Test: `core/model/src/test/kotlin/com/swipedelete/core/model/CategoryDefinitionTest.kt`

**Interfaces:**
- Produces (consumed by every later task):

```kotlin
@JvmInline value class ItemId(val value: String)
@JvmInline value class SourceId(val value: String)
@JvmInline value class BatchId(val value: String)
@JvmInline value class SessionId(val value: String)
@JvmInline value class SlotId(val value: Int)

data class MediaItem(
    val id: ItemId, val uri: String, val path: String?, val relativePath: String?,
    val mediaStoreId: Long?, val mime: String, val sizeBytes: Long,
    val width: Int?, val height: Int?, val durationMs: Long?,
    val dateAdded: Long, val dateModified: Long, val bucket: String?,
    val sourceId: SourceId, val hash: String? = null, val phash: Long? = null,
    val protectedRoot: Boolean = false,
)

enum class SourceKind { SAF, MEDIASTORE, RAW }
data class SourceRef(
    val id: SourceId, val treeUri: String, val displayName: String,
    val access: Access, val kind: SourceKind,
    val itemCount: Long?, val totalBytes: Long?, val grantedAt: Long,
) { enum class Access { RO, RW } }

enum class SlotMode { MOVE, TAG }
data class Slot(val id: SlotId, val name: String, val icon: String, val colour: Long,
                val destinationUri: String?, val mode: SlotMode, val sortOrder: Int)

enum class DeckStatus { ACTIVE, COMPLETED, ABANDONED }
data class DeckSession(val id: SessionId, val queryJson: String, val createdAt: Long,
                       val cursor: Int, val status: DeckStatus)

enum class OpKind { QUARANTINE, RESTORE, MOVE, DELETE_PERMANENT, COMPRESS }
enum class JournalState { PENDING, DONE, FAILED, ABORTED, RESTORED }
data class JournalEntry(
    val id: String, val batchId: BatchId, val sessionId: SessionId?, val itemId: ItemId,
    val opKind: OpKind, val fromUri: String, val toUri: String?, val hash: String?,
    val sizeBytes: Long, val state: JournalState, val createdAt: Long,
    val expiresAt: Long, val error: String? = null,
)

data class Capacity(val totalBytes: Long, val usedBytes: Long, val freeBytes: Long, val reclaimableBytes: Long)

enum class Category(val mediaEligible: Boolean, val defaultState: CategoryDefault) {
    LARGE_FILE(true, CategoryDefault.REVIEW),
    EXACT_DUPLICATE(true, CategoryDefault.REVIEW),
    SIMILAR_MEDIA(true, CategoryDefault.REVIEW),
    APK_INSTALLER(false, CategoryDefault.REVIEW),
    EMPTY_FOLDER(false, CategoryDefault.REVIEW),
    PARTIAL_DOWNLOAD(false, CategoryDefault.REVIEW),
    ZERO_BYTE(false, CategoryDefault.REVIEW),
    CRASH_LOG(false, CategoryDefault.REVIEW),
    TEMP_FILE(false, CategoryDefault.REVIEW),
    THUMBNAIL_CACHE(false, CategoryDefault.OFF),
    ORPHANED_APP_DIR(false, CategoryDefault.OFF),
    APP_CACHE(false, CategoryDefault.MEASURE_ONLY),
    OBSOLETE_MEDIA(true, CategoryDefault.DECK_ONLY),
}
enum class CategoryDefault { REVIEW, OFF, MEASURE_ONLY, DECK_ONLY }
```

- [ ] **Step 1: Write the failing test**

```kotlin
class CategoryDefinitionTest {
    @Test
    fun `media-eligible categories match spec table 6 4`() {
        val expected = setOf(
            Category.LARGE_FILE, Category.EXACT_DUPLICATE, Category.SIMILAR_MEDIA, Category.OBSOLETE_MEDIA,
        )
        assertEquals(expected, Category.entries.filter { it.mediaEligible }.toSet())
    }

    @Test
    fun `no category that can contain media is default-on`() {
        Category.entries.filter { it.mediaEligible }
            .forEach { assertNotEquals(CategoryDefault.OFF, it.defaultState, "$it may not default off") }
    }

    @Test
    fun `the word cache never appears as an actable category default`() {
        assertEquals(CategoryDefault.MEASURE_ONLY, Category.APP_CACHE.defaultState)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:model:testDebugUnitTest --tests "*CategoryDefinitionTest"`
Expected: FAIL — `Unresolved reference: Category`.

- [ ] **Step 3: Implement the model**

Write the types above. Keep `:core:model` free of Android imports (assert via the module `build.gradle.kts` not depending on `androidx.*`; a later architecture test in M5-T03 enforces this).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:model:testDebugUnitTest --tests "*CategoryDefinitionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model
git commit -m "feat(core-model): domain types and closed category taxonomy"
```

### Task 12 (M0-T03): Common utilities (`:core:common`)

**Files:**
- Create: `core/common/src/main/kotlin/com/swipedelete/core/common/DispatchersProvider.kt`, `LocalLog.kt`, `Bytes.kt`, `Hashing.kt`, `Clock.kt`
- Test: `core/common/src/test/kotlin/com/swipedelete/core/common/BytesTest.kt`, `HashingTest.kt`

**Interfaces:**
- Produces: `interface DispatchersProvider { val io: CoroutineDispatcher; val default: CoroutineDispatcher; val main: CoroutineDispatcher }` (Hilt-bound in M0-T15); `fun formatBytes(bytes: Long): String`; `fun humanDuration(ms: Long): String`; `interface Hasher { suspend fun sha256(stream: InputStream): String }`; `interface Clock { fun nowMillis(): Long }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class BytesTest {
    @Test fun `formatBytes uses binary units with one decimal`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("4.1 GB", formatBytes(4_400_000_000))
    }
    @Test fun `reclaimable and freed wording are distinct and never interchangeable`() {
        assertEquals("4.1 GB reclaimable", formatReclaimable(4_400_000_000))
        assertEquals("4.1 GB freed", formatFreed(4_400_000_000))
    }
}

class HashingTest {
    @Test fun `sha256 matches the known digest of an empty stream`() = runTest {
        val digest = Sha256Hasher().sha256(ByteArrayInputStream(ByteArray(0)))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:common:testDebugUnitTest`
Expected: FAIL — unresolved `formatBytes` / `Sha256Hasher`.

- [ ] **Step 3: Implement**

`formatBytes` uses 1024-based units (B, KB, MB, GB, TB) with one decimal place except `0 B`. `formatReclaimable`/`formatFreed` are the single source of truth for AC-9 wording and are the only functions allowed to render those two phrases. `Sha256Hasher` streams into `MessageDigest` in 64 KiB chunks (never loads a whole file). `LocalLog` writes to logcat only, with a compile-time constant that a lint rule later checks (M5-T02).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:common:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/common
git commit -m "feat(core-common): byte formatting, hashing and dispatchers"
```

### Task 13 (M0-T04): Room database and DAOs (`:core:database`)

**Files:**
- Create: `core/database/src/main/kotlin/com/swipedelete/core/database/SwipeDeleteDatabase.kt`
- Create: `entity/JournalEntryEntity.kt`, `entity/MediaItemEntity.kt`, `entity/SourceRefEntity.kt`, `entity/SlotEntity.kt`, `entity/DeckSessionEntity.kt`, `entity/TagEntity.kt`, `entity/KeepMarkEntity.kt`, `entity/StorageSnapshotEntity.kt`, `entity/ProtectedRootEntity.kt`
- Create: `dao/JournalDao.kt`, `dao/MediaItemDao.kt`, `dao/SourceRefDao.kt`, `dao/SlotDao.kt`, `dao/DeckSessionDao.kt`, `dao/SnapshotDao.kt`
- Create: `core/database/src/main/kotlin/com/swipedelete/core/database/Converters.kt`
- Test: `core/database/src/test/kotlin/com/swipedelete/core/database/JournalDaoTest.kt` (Robolectric)

**Interfaces:**
- Produces:

```kotlin
@Dao interface JournalDao {
    @Insert suspend fun insertPending(entry: JournalEntryEntity): Long
    @Query("UPDATE journal SET state = :state, error = :error WHERE id = :id")
    suspend fun markOutcome(id: String, state: JournalState, error: String?): Int
    @Query("SELECT * FROM journal WHERE state = 'PENDING'") suspend fun pending(): List<JournalEntryEntity>
    @Query("SELECT * FROM journal ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<JournalEntryEntity>
    @Query("SELECT * FROM journal WHERE batchId = :batchId") suspend fun byBatch(batchId: String): List<JournalEntryEntity>
}

@Database(entities = [...], version = 1, exportSchema = true)
abstract class SwipeDeleteDatabase : RoomDatabase() { abstract fun journalDao(): JournalDao; /* ... */ }
```

- [ ] **Step 1: Write the failing test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class JournalDaoTest {
    private lateinit var db: SwipeDeleteDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SwipeDeleteDatabase::class.java).allowMainThreadQueries().build() }
    @After fun tearDown() = db.close()

    @Test fun `pending entries round-trip and are queryable`() = runBlocking {
        db.journalDao().insertPending(pending("j1", "item-1"))
        assertEquals(listOf("j1"), db.journalDao().pending().map { it.id })
    }

    @Test fun `markOutcome removes the entry from pending`() = runBlocking {
        db.journalDao().insertPending(pending("j1", "item-1"))
        db.journalDao().markOutcome("j1", JournalState.DONE, null)
        assertTrue(db.journalDao().pending().isEmpty())
    }

    @Test fun `rows are never deleted inside the retention window`() = runBlocking {
        db.journalDao().insertPending(pending("j1", "item-1"))
        db.journalDao().markOutcome("j1", JournalState.DONE, null)
        assertEquals(1, db.journalDao().page(limit = 10, offset = 0).size)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:database:testDebugUnitTest --tests "*JournalDaoTest"`
Expected: FAIL — unresolved `SwipeDeleteDatabase`.

- [ ] **Step 3: Implement entities, converters and DAOs**

Journal stores `expiresAt = createdAt + retention` (retention in DataStore, default 30 days). `state` and `opKind` are stored as their enum names via `Converters`. Enable `exportSchema = true` and commit the schema JSON so migrations are reviewable. Add `tags` and `keepMarks` tables (composite primary keys) even though their UI lands in M1.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:database:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/database
git commit -m "feat(core-database): room schema and journal dao"
```

### Task 14 (M0-T05): `StorageGateway` abstraction (`:core:storage`)

**Files:**
- Create: `core/storage/src/main/kotlin/com/swipedelete/core/storage/StorageGateway.kt`
- Create: `Page.kt`, `Metadata.kt`, `MoveResult.kt`, `QuarantineResult.kt`, `RestoreResult.kt`, `DeleteResult.kt`, `ChangeEvent.kt`
- Test: `core/storage/src/test/kotlin/com/swipedelete/core/storage/StorageGatewayContractTest.kt`

**Interfaces:**
- Produces (exact spec §9.3 shape plus result types):

```kotlin
interface StorageGateway {
    suspend fun sources(): List<SourceRef>
    fun enumerate(source: SourceRef, page: PageToken?): Flow<Page<MediaItem>>
    suspend fun metadata(item: MediaItem): Metadata
    fun openStream(item: MediaItem): InputStream
    suspend fun move(item: MediaItem, destination: Destination): MoveResult
    suspend fun quarantine(items: List<MediaItem>, batch: BatchId): QuarantineResult
    suspend fun restore(entry: JournalEntry): RestoreResult
    suspend fun deletePermanently(items: List<MediaItem>): DeleteResult
    suspend fun capacity(): Capacity
    fun watch(source: SourceRef): Flow<ChangeEvent>
}

sealed interface MoveResult { data class Moved(val to: MediaItem) : MoveResult; data class NameCollision(val resolvedTo: MediaItem) : MoveResult; data class Failed(val reason: String) : MoveResult }
sealed interface QuarantineResult { data class Quarantined(val batch: BatchId, val entries: List<JournalEntry>) : QuarantineResult; data class InsufficientSpace(val neededBytes: Long, val freeBytes: Long) : QuarantineResult; data class Partial(val entries: List<JournalEntry>, val failures: List<Failure>) : QuarantineResult }
sealed interface RestoreResult { data class Restored(val to: String) : RestoreResult; data class Conflict(val originalPath: String, val occupiedBy: String) : RestoreResult; data class HashMismatch(val expected: String, val actual: String) : RestoreResult }
```

- [ ] **Step 1: Write the failing test**

A reusable contract test suite parameterised over implementations (run again for SAF, Raw, MediaStore, Fake):

```kotlin
abstract class StorageGatewayContractTest {
    abstract fun newGateway(): StorageGateway
    @Test fun `enumerate yields stable ids that metadata can resolve`() = runTest { /* ... */ }
    @Test fun `move never overwrites an existing filename`() = runTest { /* asserts a (1) suffix */ }
    @Test fun `restore refuses to overwrite and reports Conflict`() = runTest { /* AC-17 */ }
    @Test fun `cancelled quarantine leaves the original untouched`() = runTest { /* AC-15 */ }
}

class FakeStorageGatewayContractTest : StorageGatewayContractTest() {
    override fun newGateway() = FakeStorageGateway()
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*FakeStorageGatewayContractTest"`
Expected: FAIL — unresolved `FakeStorageGateway`.

- [ ] **Step 3: Implement the interface and the in-memory fake**

The fake stores items in a map, enforces the no-overwrite rename rule, and exposes `faultInjector` (used in M0-T10). No decorators yet — the contract test is against the raw interface.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*FakeStorageGatewayContractTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/storage
git commit -m "feat(core-storage): storage gateway contract and in-memory fake"
```

### Task 15 (M0-T06): Journal write-ahead decorator (AC-1)

**Files:**
- Create: `core/storage/src/main/kotlin/com/swipedelete/core/storage/JournalingStorageGateway.kt`
- Test: `core/storage/src/test/kotlin/com/swipedelete/core/storage/JournalWriteAheadTest.kt`

**Interfaces:**
- Consumes: `StorageGateway`, `JournalDao`, `Clock`.
- Produces: `class JournalingStorageGateway(delegate: StorageGateway, journal: JournalDao, clock: Clock) : StorageGateway`. Contract: for every mutating call, `insertPending` is committed **before** the delegate call, and `markOutcome` is written after. Read-only calls pass through untouched.

- [ ] **Step 1: Write the failing test (this is the AC-1 test)**

```kotlin
class JournalWriteAheadTest {
    private val trace = mutableListOf<String>()
    private val journal = TracingJournal(trace)          // appends "PENDING:<id>" / "DONE:<id>"
    private val delegate = TracingGateway(trace)          // appends "OP:<id>"
    private val gateway = JournalingStorageGateway(delegate, journal, FixedClock(1_000L))

    @Test fun `pending is committed before the operation runs`() = runTest {
        gateway.quarantine(listOf(item("item-1")), BatchId("b1"))
        assertEquals(listOf("PENDING:item-1", "OP:item-1", "DONE:item-1"), trace)
    }

    @Test fun `a failing operation records FAILED after PENDING`() = runTest {
        delegate.failNext = "io error"
        gateway.quarantine(listOf(item("item-1")), BatchId("b1"))
        assertEquals(listOf("PENDING:item-1", "OP:item-1", "DONE:item-1"), trace) // FAILED path asserted in the second trace test
    }

    @Test fun `read-only calls do not touch the journal`() = runTest {
        gateway.sources(); gateway.metadata(item("item-1"))
        assertTrue(trace.none { it.startsWith("PENDING") })
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*JournalWriteAheadTest"`
Expected: FAIL — unresolved `JournalingStorageGateway`.

- [ ] **Step 3: Implement the decorator**

Each write method follows: build `JournalEntry(state = PENDING, expiresAt = clock.nowMillis() + retention)` → `journal.insertPending` → `delegate.<op>` → `journal.markOutcome(id, DONE|FAILED, error)`. A `FAILED` outcome records the error and rethrows. `deletePermanently` uses `OpKind.DELETE_PERMANENT` and additionally requires the caller to have passed the confirmation gate (the gate lives in the UI; the decorator records the op).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*JournalWriteAheadTest"`
Expected: PASS, with trace exactly `PENDING, OP, DONE`.

- [ ] **Step 5: Commit**

```bash
git add core/storage
git commit -m "feat(core-storage): journal write-ahead decorator (AC-1)"
```

### Task 16 (M0-T07): Protected-roots decorator (AC-7)

**Files:**
- Create: `core/storage/src/main/kotlin/com/swipedelete/core/storage/ProtectedRoots.kt` (the default pattern set from §6.3)
- Create: `core/storage/src/main/kotlin/com/swipedelete/core/storage/ProtectedRootsStorageGateway.kt`
- Test: `core/storage/src/test/kotlin/com/swipedelete/core/storage/ProtectedRootsTest.kt`

**Interfaces:**
- Produces: `object DefaultProtectedRoots { fun patterns(): List<ProtectedRoot> }`; `class ProtectedRootsStorageGateway(delegate, unlocked: () -> Set<String>, isRoot: (MediaItem) -> Boolean) : StorageGateway`. Every mutating call filters out items inside a protected root unless that exact pattern is in `unlocked`; filtered items are reported as `MoveResult.Failed("protected_root")` / excluded from `QuarantineResult` with an explicit reason, never silently dropped.

Protected patterns (verbatim from §6.3): `DCIM/`, `DCIM/Camera`, `Pictures/`, `Movies/`, `Music/`, `Documents/`, `Download/`; buckets named `Camera`, `Screenshots`, `Screen recordings`, `WhatsApp Images`, `WhatsApp Video`, `Telegram`, `Signal`, `Snapseed`, `Instagram`, `Snapchat`, `Pinterest`, `Reddit`; voice/call recorders; any tree of media older than 5 years; the app's own quarantine directory; any SAF root itself.

- [ ] **Step 1: Write the failing test**

```kotlin
class ProtectedRootsTest {
    private val gateway = ProtectedRootsStorageGateway(FakeStorageGateway(), unlocked = { emptySet() })

    @Test fun `DCIM items are refused bulk modification`() = runTest {
        val result = gateway.quarantine(listOf(item(path = "/storage/emulated/0/DCIM/Camera/IMG_1.jpg")), BatchId("b"))
        assertTrue(result is QuarantineResult.Partial)
        assertEquals("protected_root", (result as QuarantineResult.Partial).failures.single().reason)
    }

    @Test fun `a bucket named Screenshots is protected even outside Pictures`() = runTest {
        assertTrue(gateway.isProtected(item(relativePath = "Somewhere/Screenshots/x.png")))
    }

    @Test fun `the quarantine directory is never a delete target`() = runTest {
        assertTrue(gateway.isProtected(item(path = "/storage/emulated/0/Android/data/com.swipedelete.app/files/quarantine/b1/a.jpg")))
    }

    @Test fun `an unlocked pattern is allowed and every other pattern stays protected`() = runTest {
        val unlocked = ProtectedRootsStorageGateway(FakeStorageGateway(), unlocked = { setOf("DCIM/") })
        assertTrue(unlocked.quarantine(listOf(item(path = "DCIM/Camera/a.jpg")), BatchId("b")) is QuarantineResult.Quarantined)
        assertTrue(unlocked.quarantine(listOf(item(path = "Pictures/a.jpg")), BatchId("b")) is QuarantineResult.Partial)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*ProtectedRootsTest"`
Expected: FAIL — unresolved `ProtectedRootsStorageGateway`.

- [ ] **Step 3: Implement**

Pattern matching normalises paths to POSIX, case-insensitively on the segment, and matches on **path segments plus bucket**, never on the raw path string alone (so `/PicturesofYou/` does not match `Pictures/`). The decorator is applied *outside* the journaling decorator in the DI graph so protected items never reach a journal write (M0-T15 wires the order).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*ProtectedRootsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/storage
git commit -m "feat(core-storage): protected-roots decorator (AC-7)"
```

### Task 17 (M0-T08): Quarantine store with hash-verified restore (AC-3, AC-15, AC-17)

**Files:**
- Create: `core/storage/src/main/kotlin/com/swipedelete/core/storage/quarantine/QuarantineStore.kt`, `QuarantineLayout.kt`, `SidecarCodec.kt`
- Test: `core/storage/src/test/kotlin/com/swipedelete/core/storage/quarantine/QuarantineRestoreTest.kt`

**Interfaces:**
- Produces: `class QuarantineStore(root: File, hasher: Hasher, clock: Clock)` with `suspend fun store(item, batch): JournalEntry`, `suspend fun restore(entry): RestoreResult`, `suspend fun contents(): Flow<List<QuarantineEntry>>`, `suspend fun empty(batch, allowPermanent: Boolean)`. Layout `<root>/<batchId>/<originalRelativePath>`; sidecar `<file>.swipedelete.json` holding original URI, MediaStore ID, size, mtime, MIME, SHA-256, cause, session id, timestamp, expiry.

- [ ] **Step 1: Write the failing tests**

```kotlin
class QuarantineRestoreTest {
    @Test fun `quarantine then restore returns byte-identical content to the original path`() = runTest {
        val source = file("Memes/a.gif", "GIF89a...")
        gateway.quarantine(listOf(source.item), BatchId("b1"))
        assertFalse(source.exists())
        val entry = quarantineEntryFor("Memes/a.gif")
        assertTrue(gateway.restore(entry) is RestoreResult.Restored)
        assertEquals("GIF89a...", file("Memes/a.gif").readText())
    }

    @Test fun `restore verifies the recorded hash and refuses on mismatch`() = runTest {
        /* tamper with the quarantined bytes -> HashMismatch, original path untouched */
    }

    @Test fun `restore into an occupied path reports Conflict rather than overwriting`() = runTest {
        /* create a new file at the original path -> RestoreResult.Conflict, both files still exist */   // AC-17
    }

    @Test fun `insufficient free space is reported, never silently downgraded`() = runTest {
        /* fake capacity below item size -> InsufficientSpace, nothing moved */
    }

    @Test fun `empty quarantine is refused without the explicit permanent flag`() = runTest {
        assertTrue(store.empty(BatchId("b1"), allowPermanent = false) is QuarantineResult.Partial)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*QuarantineRestoreTest"`
Expected: FAIL — unresolved `QuarantineStore`.

- [ ] **Step 3: Implement**

Move strategy per V3: attempt a same-volume rename first; on `AtomicMoveNotSupported`/cross-device, copy → verify SHA-256 + size → delete original, cancellable between chunks so a cancelled copy leaves the original untouched (AC-15). Space pre-flight via `capacity()`. Restores recreate parent directories and the MediaStore row per V4, and never overwrite (AC-17). `empty()` requires `allowPermanent = true` and is the only caller-allowed path to `deletePermanently`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*QuarantineRestoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/storage
git commit -m "feat(core-storage): quarantine store with hash-verified restore (AC-3, AC-15, AC-17)"
```

### Task 18 (M0-T09): Startup reconciliation and Recovery Report (AC-5)

**Files:**
- Create: `core/storage/src/main/kotlin/com/swipedelete/core/storage/Reconciler.kt`
- Create: `core/model/src/main/kotlin/com/swipedelete/core/model/RecoveryReport.kt`
- Test: `core/storage/src/test/kotlin/com/swipedelete/core/storage/ReconcilerTest.kt`

**Interfaces:**
- Produces: `class Reconciler(journal: JournalDao, gateway: StorageGateway, quarantine: QuarantineStore)` with `suspend fun reconcile(): RecoveryReport`; `data class RecoveryReport(val resolved: List<Resolved>, val unknown: List<JournalEntry>)` where `Resolved(entryId, outcome)` and `outcome ∈ {ABORTED, DONE}`. Called from `SwipeDeleteApplication.onCreate` on a background dispatcher before any UI can mutate storage.

- [ ] **Step 1: Write the failing tests**

```kotlin
class ReconcilerTest {
    @Test fun `a pending entry whose file is still at source becomes ABORTED`() = runTest { /* ... */ }
    @Test fun `a pending entry whose file is at the destination becomes DONE`() = runTest { /* ... */ }
    @Test fun `a pending entry found at neither location surfaces in the Recovery Report`() = runTest {
        val report = reconciler.reconcile()
        assertEquals(1, report.unknown.size)
        assertEquals("j1", report.unknown.single().id)
    }
    @Test fun `reconcile is idempotent`() = runTest {
        val first = reconciler.reconcile(); val second = reconciler.reconcile()
        assertTrue(second.unknown.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*ReconcilerTest"`
Expected: FAIL — unresolved `Reconciler`.

- [ ] **Step 3: Implement**

For each `PENDING` entry: resolve source existence and destination existence via the gateway, mark `ABORTED` (source only), `DONE` (destination only), and report both/neither as unknown. Never delete or move anything during reconciliation — it only classifies and records.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*ReconcilerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model core/storage
git commit -m "feat(core-storage): startup reconciliation and recovery report (AC-5)"
```

### Task 19 (M0-T10): `FakeStorageGateway` fault injection and chaos harness (AC-5)

**Files:**
- Create: `core/storage/src/testFixtures/kotlin/com/swipedelete/core/storage/fake/FakeStorageGateway.kt`
- Create: `core/storage/src/testFixtures/kotlin/com/swipedelete/core/storage/fake/FaultInjector.kt`
- Test: `core/storage/src/test/kotlin/com/swipedelete/core/storage/ChaosReconciliationTest.kt`

**Interfaces:**
- Produces: `FaultInjector` with `failOnCall(n: Int)`, `failOnOperation(op: OpKind, nth: Int)`, `throwOnCommit(state: JournalState)`; `FakeStorageGateway` emits an event after each operation so the chaos test can force-stop at arbitrary points. Exposed to `:app` and other modules via a `testFixtures` configuration (or an `androidTest`/`test` fixtures source set).

- [ ] **Step 1: Write the failing chaos test**

```kotlin
class ChaosReconciliationTest {
    @Test fun `no item ends in limbo when killed at any of N points in a batch`() = runTest {
        for (stopAt in 0 until 40) {
            val env = ChaosEnv(stopAt = stopAt)              // disposes the process simulation at that op
            runCatching { env.runQuarantineBatch(of = 5) }
            val report = Reconciler(env.journal, env.gateway, env.quarantine).reconcile()
            assertTrue(report.unknown.isEmpty(), "limbo with stopAt=$stopAt: ${report.unknown}")
            env.assertEveryItemIsEitherAtSourceOrInQuarantine()
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*ChaosReconciliationTest"`
Expected: FAIL — unresolved `ChaosEnv`.

- [ ] **Step 3: Implement the fake and the harness**

The harness abandons the in-flight coroutine at operation `stopAt` without running any cleanup, then runs reconciliation on the same durable journal state. This is the executable form of AC-5.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*ChaosReconciliationTest"`
Expected: PASS for all 40 stop points.

- [ ] **Step 5: Commit**

```bash
git add core/storage
git commit -m "test(core-storage): fault-injected gateway and chaos reconciliation (AC-5)"
```

### Task 20 (M0-T11): SAF storage gateway

**Files:**
- Create: `core/storage/src/main/kotlin/com/swipedelete/core/storage/saf/SafStorageGateway.kt`, `DocumentTreeWalker.kt`, `SafUri.kt`
- Test: `core/storage/src/test/kotlin/com/swipedelete/core/storage/saf/SafDocumentTreeWalkerTest.kt` (Robolectric, `DocumentsContract` shadow/tree fixtures)
- Test: `core/storage/src/androidTest/kotlin/com/swipedelete/core/storage/saf/SafStorageGatewayInstrumentedTest.kt` (device, `ACTION_OPEN_DOCUMENT_TREE` fixture)

**Interfaces:**
- Produces: `class SafStorageGateway(context: Context, persisted: PersistedGrantStore) : StorageGateway`. Enumerate is paged and cancellable between pages; `openStream` uses `ContentResolver.openInputStream`; `move` copies within the tree then deletes the source; `deletePermanently` uses `DocumentsContract.deleteDocument`.

- [ ] **Step 1: Write the failing walker test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class SafDocumentTreeWalkerTest {
    @Test fun `walker pages a deep tree and honours the depth and entry caps`() = run { /* asserts page boundaries and cap stop */ }
    @Test fun `walker is cancellable between pages and reports partial progress`() = run { /* cancel after first emission */ }
    @Test fun `deleted and unreadable children are reported, not silently skipped`() = run { /* ... */ }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*SafDocumentTreeWalkerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Depth default 32, hard entry cap 200k per tree with an explicit truncated flag surfaced to the UI. Every page carries a resumable `PageToken`. Unreadable children are counted and reported so scans are honest (§9.4).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*SafDocumentTreeWalkerTest"`
Expected: PASS.

- [ ] **Step 5: Register with the shared contract suite and commit**

Add `SafStorageGateway` to `StorageGatewayContractTest` (instrumented variant). Commit: `feat(core-storage): SAF storage gateway`.

### Task 21 (M0-T12): Raw storage gateway (Tier 2)

**Files:**
- Create: `core/storage/src/main/kotlin/com/swipedelete/core/storage/raw/RawStorageGateway.kt`
- Test: `core/storage/src/test/kotlin/com/swipedelete/core/storage/raw/RawStorageGatewayTest.kt`

**Interfaces:**
- Produces: `class RawStorageGateway(roots: List<File>) : StorageGateway`. Used only for **non-media** management (APKs, empty folders, partial downloads, orphaned dirs, thumbnails). Constructed only when All Files Access is held; the DI graph refuses to build it otherwise.

- [ ] **Step 1: Write the failing test**

```kotlin
class RawStorageGatewayTest {
    @Test fun `raw gateway is only constructed when All Files Access is held`() = runTest {
        assertFailsWith<IllegalStateException> { RawStorageGateway(roots = listOf(tmp), hasAllFilesAccess = false) }
    }
    @Test fun `raw gateway refuses to enumerate media mime types`() = runTest {
        // AC-10: media is not this gateway's job
        assertTrue(runCatching { raw.metadata(mediaItem(mime = "image/jpeg")) }.isFailure)
    }
    @Test fun `empty folder detection ignores folders with a nomedia marker`() = runTest { /* ... */ }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*RawStorageGatewayTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Guarded construction on `Environment.isExternalStorageManager()`. Media MIME types are rejected with a clear error so no M0–M4 feature can accidentally route media through the raw gateway (AC-10 enforcement point, asserted again in M3-T11 and M5-T03).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:storage:testDebugUnitTest --tests "*RawStorageGatewayTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/storage
git commit -m "feat(core-storage): tier-2 raw storage gateway with media exclusion (AC-10)"
```

### Task 22 (M0-T13): Capability tiers, permission flows and `-PnoAllFilesAccess`

**Files:**
- Modify: `app/build.gradle.kts` (add `noAllFilesAccess` product build flag / manifest placeholder)
- Create: `core/model/src/main/kotlin/com/swipedelete/core/model/Tier.kt`
- Create: `app/src/main/kotlin/com/swipedelete/app/permissions/CapabilityController.kt`, `TierState.kt`, `PermissionRationale.kt`
- Create: `app/src/main/AndroidManifest.xml` permission declarations, `app/src/noAllFilesAccess/AndroidManifest.xml` override
- Test: `app/src/test/kotlin/com/swipedelete/app/permissions/CapabilityControllerTest.kt` (Robolectric, `ShadowApplication`)
- Test: `app/src/test/kotlin/com/swipedelete/app/permissions/TieredFeatureMatrixTest.kt` (AC-19, part 1)

**Interfaces:**
- Produces: `enum class Tier { TIER_0, TIER_1, TIER_2 }`; `interface CapabilityController { fun tier(): Flow<Tier>; suspend fun requestPhotoVideo(): TierResult; suspend fun requestAllFilesAccess(): TierResult }`; `sealed interface TierResult { object Granted; data class Declined(val rationaleRes: Int); object UnavailableInThisBuild }`. Feature gate: `fun isAvailable(feature: Feature, tier: Tier): Availability` where `Availability ∈ {FULL, NARROWED(reasonRes, grantAffordanceRes), UNAVAILABLE_THIS_BUILD}`.

Manifest: declare `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` (API 33+, `maxSdkVersion`-free), `MANAGE_EXTERNAL_STORAGE` (with `tools:ignore` as required by Play), and **no** `INTERNET`. `-PnoAllFilesAccess` filters out the `MANAGE_EXTERNAL_STORAGE` declaration via the `noAllFilesAccess` manifest overlay and makes `requestAllFilesAccess()` return `UnavailableInThisBuild`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
class CapabilityControllerTest {
    @Test fun `tier is tier 0 with no broad permissions held`() { /* assert tier() == TIER_0 */ }
    @Test fun `photo video grant moves tier 0 to tier 1`() { /* ... */ }
    @Test fun `all files access moves tier 1 to tier 2`() { /* ... */ }
    @Test fun `revoking a grant moves the tier back down without data loss`() { /* assert no destructive side effect */ }
}

class TieredFeatureMatrixTest {
    @Test fun `every feature is FULL or NARROWED at tier 0 and never UNAVAILABLE`() {
        Feature.entries.forEach { feature ->
            val a = gate.isAvailable(feature, Tier.TIER_0)
            assertTrue(a !is Availability.UNAVAILABLE_THIS_BUILD || feature.requiresAllFilesAccess, "$feature is a dead end at tier 0 (AC-19)")
        }
    }
    @Test fun `every narrowed panel names what it excludes and offers an in-context grant`() {
        Feature.entries.flatMap { listOf(Tier.TIER_0, Tier.TIER_1) }
            .mapNotNull { (f, t) -> (gate.isAvailable(f, t) as? Availability.NARROWED)?.let { f to it } }
            .forEach { (f, a) -> assertNotEquals(0, a.reasonRes); assertNotEquals(0, a.grantAffordanceRes) }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*CapabilityControllerTest" --tests "*TieredFeatureMatrixTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Tier 0 is the default on first run. Requests happen in context only (`requestPhotoVideo` is invoked from the deck's "include the whole library" affordance, not onboarding). No code path gates browsing or opening a file behind All Files Access.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*CapabilityControllerTest" --tests "*TieredFeatureMatrixTest"`
Expected: PASS.

- [ ] **Step 5: Verify the contingency build**

Run: `./gradlew :app:assembleDebug -PnoAllFilesAccess` then inspect the merged manifest with `./gradlew :app:processDebugMainManifest` output.
Expected: `MANAGE_EXTERNAL_STORAGE` absent; `INTERNET` absent; build succeeds.

- [ ] **Step 6: Commit**

```bash
git add app core/model
git commit -m "feat(app): capability tiers, in-context grants and noAllFilesAccess build (AC-19)"
```

### Task 23 (M0-T14): Design system (`:core:designsystem`)

**Files:**
- Create: `core/designsystem/src/main/kotlin/com/swipedelete/core/designsystem/Theme.kt`, `Tokens.kt`, `components/PrimaryButton.kt`, `components/DestructiveAction.kt`, `components/EmptyState.kt`, `components/SlotDock.kt`, `components/StagingStrip.kt`
- Create: `core/designsystem/src/main/res/values/strings.xml`, `values-night/`, `values-rtl/` baseline
- Test: `core/designsystem/src/test/kotlin/com/swipedelete/core/designsystem/DestructiveActionTest.kt`, `ResourceStringPolicyTest.kt`

**Interfaces:**
- Produces: `SwipeDeleteTheme`, semantic colour tokens (including `destructive`, never expressed by colour alone in components), and the shared `SlotDock`/`StagingStrip` used by `:feature:deck`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
class DestructiveActionTest {
    @Test fun `a destructive action always carries an icon and text, never colour alone`() { /* compose test asserts content description + label node */ }
    @Test fun `destructive touch targets are at least 48 dp`() { /* assert size */ }
}

class ResourceStringPolicyTest {
    @Test fun `no string resource applies the word cache to a destructive surface`() {
        // AC-11, first pass; the CI lint rule in M3-T11 is the enforcing gate
        val destructiveSurfaces = listOf("action_delete_all", "category_review_action")
        destructiveSurfaces.forEach { name -> assertFalse(stringOf(name).contains("cache", ignoreCase = true)) }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:designsystem:testDebugUnitTest`
Expected: FAIL.

- [ ] **Step 3: Implement tokens and components**

Every destructive affordance renders icon + text + colour. All strings externalised from day one; layouts use `start`/`end` not `left`/`right` so RTL is correct without rework. Support font scale to 200% and `LocalAccessibilityManager`/reduced-motion-aware animations.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:designsystem:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/designsystem
git commit -m "feat(designsystem): tokens, destructive-action and dock components (AC-11 seed)"
```

### Task 24 (M0-T15): App shell, DI graph and navigation (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/swipedelete/app/SwipeDeleteApplication.kt`, `MainActivity.kt`, `di/StorageModule.kt`, `di/DatabaseModule.kt`, `di/DispatchersModule.kt`, `di/CapabilityModule.kt`, `nav/AppNavHost.kt`, `nav/Destinations.kt`
- Test: `app/src/test/kotlin/com/swipedelete/app/di/StorageGraphOrderTest.kt`

**Interfaces:**
- Produces: Hilt bindings where the mutating stack is constructed **outside-in as `ProtectedRoots → Journaling → Quarantine → ConcreteGateway`** so a protected item is refused before any journal row is written. `SwipeDeleteApplication.onCreate` launches `Reconciler.reconcile()` on `DispatchersProvider.io` and publishes the `RecoveryReport` to a `MutableStateFlow` the UI observes.

- [ ] **Step 1: Write the failing test**

```kotlin
class StorageGraphOrderTest {
    @Test fun `protected roots are evaluated before the journal is written`() {
        val order = StorageStack.order(ProtectedRootsStorageGateway::class, JournalingStorageGateway::class)
        assertEquals(listOf("ProtectedRoots", "Journaling", "Quarantine"), order)
    }
    @Test fun `a protected item produces no journal row`() = runTest {
        /* quarantine a DCIM item through the assembled graph -> journal.pending() and journal.page() both empty */
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*StorageGraphOrderTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`AppNavHost` declares `onboarding`, `deck`, `viewer/{itemId}`, `dashboard`, `quarantine`, `history`, `settings`. Only onboarding and settings are reachable in M0; later milestones fill the rest without changing the graph order.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*StorageGraphOrderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "feat(app): Hilt graph with protected-roots-before-journal ordering"
```

### Task 25 (M0-T16): SAF onboarding — "pick your five folders" and grant management

**Files:**
- Create: `feature/settings/src/main/kotlin/com/swipedelete/feature/settings/onboarding/OnboardingScreen.kt`, `OnboardingViewModel.kt`, `GrantSlotPicker.kt`
- Create: `feature/settings/src/main/kotlin/com/swipedelete/feature/settings/grants/GrantsScreen.kt`, `GrantRow.kt`, `PersistedGrantStore.kt`, `SourceGrantRepository.kt`
- Create: `app/src/main/res/xml/onboarding_strings.xml`
- Test: `feature/settings/src/test/kotlin/com/swipedelete/feature/settings/onboarding/OnboardingViewModelTest.kt`, `SourceGrantRepositoryTest.kt`
- Test: `feature/settings/src/androidTest/kotlin/com/swipedelete/feature/settings/onboarding/OnboardingInstrumentedTest.kt` (device, real `ACTION_OPEN_DOCUMENT_TREE`)

**Interfaces:**
- Produces: `SourceGrantRepository` with `suspend fun persist(uri: Uri, mode: Int): SourceRef`, `fun grants(): Flow<List<SourceRef>>`, `suspend fun revoke(id: SourceId)`, and a hard cap of `MAX_GRANTS = 20` (per V2) returning `GrantCapReached` when exceeded. `OnboardingViewModel` exposes `state: StateFlow<OnboardingState>` where `state.slots: List<Slot>` is persisted to Room.

- [ ] **Step 1: Write the failing tests**

```kotlin
class SourceGrantRepositoryTest {
    @Test fun `persisting a tree grants a source with its display name and access mode`() = runTest { /* ... */ }
    @Test fun `the 21st grant is refused with GrantCapReached and the existing grants are unchanged`() = runTest {
        repeat(20) { repo.persist(uri(it), READ) }
        assertEquals(GrantCapReached, repo.persist(uri(20), READ))
        assertEquals(20, repo.grants().first().size)
    }
    @Test fun `revoking a grant removes it and never deletes any files`() = runTest { /* assert gateway untouched */ }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :feature:settings:testDebugUnitTest --tests "*SourceGrantRepositoryTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Onboarding asks for five destination folders, each `takePersistableUriPermission` with `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION`. The flow is skippable (Tier 0 with zero slots must still be usable — AC-19). Grants are also usable as **sources** and shown with size/item count and a one-tap revoke. Storage surface: 20 grants tested on a low-end device per V2.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/settings
git commit -m "feat(settings): five-slot SAF onboarding and grant management"
```

### Task 26 (M0-T17): M0 acceptance — quarantine, force-stop, restore

**Files:**
- Test: `app/src/androidTest/kotlin/com/swipedelete/app/M0AcceptanceTest.kt` (device)

**Interfaces:**
- Consumes: the assembled Hilt graph, `QuarantineStore`, `Reconciler`.

- [ ] **Step 1: Write the failing acceptance test**

```kotlin
@RunWith(AndroidJUnit4::class)
class M0AcceptanceTest {           // exit criteria for M0
    @Test fun `a file quarantined then restored comes back byte-identical`() { /* AC-3 */ }
    @Test fun `killing the process mid-quarantine leaves no pending record unresolved`() { /* AC-5 */ }
    @Test fun `every mutation produced exactly one journal row with a terminal state`() { /* AC-1 */ }
    @Test fun `a protected-root item produces zero journal rows and zero moves`() { /* AC-7 */ }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*M0AcceptanceTest"`
Expected: FAIL.

- [ ] **Step 3: Fix only what the failing assertions identify**

No feature work — this task closes gaps the unit tests could not see (real filesystem, real process death).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*M0AcceptanceTest"`
Expected: PASS on API 26, 30, 33 and 36 emulators.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "test(app): M0 acceptance — quarantine, kill, restore (AC-1, AC-3, AC-5, AC-7)"
```

---

## Task 27: (boundary marker — Milestones M1–M5 are re-planned per milestone; do not dispatch)

---

## Milestone M1 — The core loop

**Exit criteria:** a 500-item deck triages end-to-end at ≥ 60 fps with full undo; AC-8 and AC-15 pass.

**Expanded to execution depth in [`docs/M1-PLAN.md`](M1-PLAN.md)** (Tasks 28–41, M1-T01a…M1-T14). That file resolves the M0 carry-forward blockers: D1 scan-progress port, D2 Activity-bound capability requests, D3 no new test dependency, D6 recovery-readiness gate. The bullets below are the milestone summary and are superseded by the plan for execution.

Task IDs and scope (each follows the same RED→GREEN→REFACTOR→COMMIT shape as M0):

- **M1-T01 `MediaStoreStorageGateway`** — media catalogue via `ContentResolver` cursors with paging; projections from §4.6 (`_ID`, `RELATIVE_PATH`, `SIZE`, `DATE_ADDED`, `DATE_MODIFIED`, `MIME_TYPE`, `WIDTH`, `HEIGHT`, `DURATION`, `BUCKET_DISPLAY_NAME`, `IS_TRASHED`, `IS_PENDING`, `OWNER_PACKAGE_NAME`); incremental `watch()` via `ContentObserver`. Tests: Robolectric cursor fixtures + contract suite + AC-10 media-never-in-non-media assertion.
- **M1-T02 Deck engine** — `DeckState(items, cursor, staging, kept, deferred, history)` and `reduce(gesture)` as pure Kotlin in `:core:model` or a new `:feature:deck` `domain` package with **no Android imports**. Property tests over random gesture sequences asserting: no item both staged and kept; history always invertible; skipped items re-queue at most 3 times then land in "Decide later"; no repeats within a session. Files: `feature/deck/src/main/kotlin/com/swipedelete/feature/deck/domain/DeckState.kt`, `Gesture.kt`, `DeckReducer.kt`; test `DeckInvariantPropertyTest.kt` (Kotest).
- **M1-T03 Deck builder and frozen snapshot** — folder/smart/resume decks; materialise IDs+metadata; detect on-disk change at presentation and abort that item with a visible message (AC-8); provenance line `Screenshots · /Pictures/Screenshots · 4.2 MB · 14 Mar 2021`; ordering defaults (oldest first / largest first). Tests: `DeckBuilderTest`, `StaleItemAbortTest` (AC-8).
- **M1-T04 Swipe surface** — Compose gesture with threshold+velocity commit, progressive tint and a "Delete"/"Keep" label that is never a surprise; destructive direction carries colour + icon + text. Tests: `SwipeGestureTest` (compose), threshold/velocity boundary cases.
- **M1-T05 Staging strip and Apply** — staged-by-default (decision 3); card enters a visible strip ("3 to delete · Undo"); batch executes only on Apply, session end, or the configured idle window; Apply shows one confirmation listing count, bytes and a preview grid (§6.6). Tests: `StagingApplyTest`, `ApplyConfirmationTest`.
- **M1-T06 Five-slot dock** — drag begins at ~12 dp, dock animates up, magnetic snap within ~48 dp, drop outside cancels and returns the card, a revoked-destination slot greys out with a reason on tap; `MOVE` default when a destination is set, `TAG` offered (decision 4); collision rename ` (1)`, ` (2)`; cross-volume copy→verify→delete with cancellation leaving the original untouched (AC-15). Tests: `SlotDockTest`, `MoveCollisionTest`, `CrossVolumeMoveCancelTest`.
- **M1-T07 Undo stack and history strip** — session-wide (not just last action), scrollable strip ("kept 12 · moved 4 to Memes · staged 3"), any entry individually revertible; two-finger tap = undo last. Tests: `SessionUndoTest` incl. revert-a-middle-entry.
- **M1-T08 Viewer: image, GIF, audio** — full-resolution decode, pinch/double-tap zoom, pan, EXIF orientation respected; animated GIF/WebP play/loop with play/pause; audio transport controls; dismiss on swipe-down, dock via a button (decision 5); pager between deck items. Tests: `ViewerImageTest`, `ViewerGifTest`, `ViewerOrientationTest`.
- **M1-T09 Viewer: video** — Media3/ExoPlayer play/pause, scrub with thumbnail preview, ±10 s, speed 0.25×–3×, frame step, loop toggle, audio track selection; audio always available. Tests: `ViewerVideoControlsTest`.
- **M1-T10 Viewer: APK, documents, unsupported** — APK card (icon, package, version, requested permissions, signature, target SDK, installed-version comparison); document metadata + "Open with"; an honest error naming the codec/MIME plus "Open with" for undecodable items, never a blank frame. Tests: `ViewerApkTest`, `ViewerUnsupportedTest`.
- **M1-T11 Card autoplay, preloading and memory ceiling** — video autoplays muted, GIFs loop (configurable); autoplay cancelled on fling; never more than one active decoder plus one preloaded item; explicit RSS ceiling with graceful degradation (drop preloading, then autoplay) rather than OOM; decoders released on `onStop`. Tests: `AutoplayPolicyTest`, `MemoryCeilingTest` (device instrumentation for real decoder counts).
- **M1-T12 Session model and resume** — `DeckSession` persisted per decision; crash/exit mid-triage restores the exact cursor; resume deck is a deck type (§5.1). Tests: `SessionResumeTest` incl. process-death simulation.
- **M1-T13 Session summary and measured bytes** — reviewed / kept / moved per slot / staged / deferred, plus **measured** bytes and quarantine bytes reported as reclaimable (§6.5); restore-everything panic button prominent; exit-with-non-empty-staging prompt (Apply / Keep staged / Discard). Tests: `SessionSummaryTest`, AC-9 wording assertions reusing `formatFreed`/`formatReclaimable`.
- **M1-T14 Loop accessibility** — bottom action bar (Delete / Keep / Move / Skip) mirroring every swipe; dock reachable by tapping "Move"; custom accessibility actions; haptics (light tick on threshold, distinct on drop, error pattern on failure); TalkBack traversal test. Tests: `DeckAccessibilityTest` (compose + TalkBack semantics), AC-19 narrowed-state tests for tier 0.

---

## Milestone M2 — Dashboard & honesty

**Exit criteria:** AC-9, AC-13 and AC-18 pass.

- **M2-T01 Capacity service (`:core:common` + `:core:storage`)** — real total/used/free via `StatFs`/`StorageStatsManager` per V1; measured-freed delta defined as the OS's usable-byte definition, not a sum of file sizes; `reclaimableBytes` from quarantine. Tests: `CapacityTest`, `MeasuredFreedTest` (AC-9).
- **M2-T02 Dashboard storage overview** — volumes, category breakdown (Images, Video, Audio, Documents, APKs, App data, System, Other), honest "Other is a residual and not bulk-actionable" labelling, top-20 largest folders and files; **every number expands into the exact file list** (AC-13). Files: `feature/dashboard/.../overview/`. Tests: `DashboardDrilldownTest` per metric (AC-13).
- **M2-T03 Snapshot trends** — opt-in, on-device-only `StorageSnapshot` rows; "what grew this week?". Tests: `TrendTest` incl. no network call assertion.
- **M2-T04 Triage progress** — reviewed/total, per-slot counts, streak, session history; "X GB awaiting review" computed from real decks and tappable to start that deck.
- **M2-T05 Quarantine card and flows** — item count, bytes held, oldest expiry countdown, Restore all / Review / Empty; a clear "these bytes are not yet reclaimed" statement; Empty requires the second typed-count confirmation (§6.6) and is refused unless permanent is explicit. Tests: `QuarantineCardTest`, `EmptyQuarantineConfirmationTest`.
- **M2-T06 History screen and per-item restore** — every batch with date, count, bytes, per-item Restore; restore recreates original paths and MediaStore rows; conflict-resolution screen instead of overwrite (AC-17). Tests: `HistoryRestoreTest`, `RestoreConflictTest`.
- **M2-T07 Journal export** — JSON and CSV export of the complete journal to a user-chosen local file (AC-18). Tests: `JournalExportParityTest` — exported rows equal the DB contents exactly, including archived rows.
- **M2-T08 Health checks checklist** — one row per module, each tappable, each reporting honest findings and "0 items" rather than inventing findings. Tests: `HealthCheckZeroStateTest`.
- **M2-T09 Honesty copy audit** — CI string assertions: "reclaimable" never renders as "freed"; no alarm-red gauge; no "boost score"; no comparison to other users; no fabricated health score. Tests: `HonestyCopyTest` (AC-9 partially, §7.5).

---

## Milestone M3 — Cleaner modules

**Exit criteria:** AC-2, AC-4, AC-7, AC-10 and AC-12 pass.

- **M3-T01 Category engine** — every §6.4 category as data with a documented human-readable definition, confidence and an enumerable result set; **a file's category is never derived from its parent folder name alone** (AC-4) and **a media MIME/extension can never enter a non-media category** (AC-10). Files: `feature/cleaner/src/main/kotlin/com/swipedelete/feature/cleaner/domain/CategoryRule.kt`, `Classifier.kt`. Tests: `GoldenFileClassifierTest` over adversarial directory names (a dir called `cache`, `Cache`, `.cache`, `temp` containing JPEG/PNG/MP4) — this is the named **AC-10 regression test, run on every commit**.
- **M3-T02 Sanity ceilings** — refuse the one-tap action and force list review when a category total exceeds its plausible bound (~2 GB for junk/cache), contains any file over 200 MB, or contains any path inside a protected root (AC-12). Tests: `SanityCeilingTest` (AC-12).
- **M3-T03 Category review surface** — zero items pre-selected; every category view shows the exact file list and preview grid before any action; frozen set; per-category "skip the preview" opt-in that is never available for media-eligible categories (AC-2). Tests: `NoPreselectionTest`, `ReviewBeforeActTest`, `SkipPreviewPolicyTest` (AC-2).
- **M3-T04 Large files** — pure size filter over `MediaStore` + granted trees; "review in deck" as the primary action. Tests: `LargeFilesTest`.
- **M3-T05 Exact duplicates** — size → head/tail hash → full content hash (xxHash/BLAKE3); only size-identical candidates hashed; keeper recommendation (protected root → oldest → shortest path) is a suggestion with a visible reason, never a selection; live photos, bursts and RAW+JPEG pairs are never treated as duplicates of each other by default (V9); deletion = quarantine with the "which copy" decision visible and reversible. Tests: `DuplicatePipelineTest`, `KeeperSuggestionTest`, `LivePhotoNotDuplicateTest`.
- **M3-T06 Similar media** — pHash/dHash/PDQ in a bounded worker pool, Hamming clustering, **mandatory time-window gating** (default 60 s for burst-like groups) plus a separate low-confidence bucket that is never bulk-actionable. Tests: `PerceptualHashTest`, `TimeWindowClusteringTest`, `LowConfidenceNeverBulkTest`.
- **M3-T07 APK installers** — `.apk` MIME/extension, compare against installed package versions via `PackageManager` (no `QUERY_ALL_PACKAGES` — request only targeted package visibility). Tests: `ApkInstallerTest`.
- **M3-T08 Empty folders** — zero entries after a completed scan, not a SAF root, not a known app path, no `.nomedia` marker; granted trees only at Tier 0–1, full at Tier 2; resumable scan. Tests: `EmptyFolderTest`.
- **M3-T09 Partial downloads, zero-byte, temp** — strict extension + path rules for `.part`, `.crdownload`, `.tmp`, `.download`, `.opdownload` inside download paths; zero-byte; known temp extensions only **outside** protected roots and outside media types. Tests: `PartialDownloadTest`, `ZeroByteTest`, `TempOutsideMediaTest`.
- **M3-T10 Review-in-deck bridge** — a module result set can be handed to the deck as a module deck, so the destructive decision is one item at a time with the file visible (§6.4 rule 3). Tests: `ModuleDeckBridgeTest`.
- **M3-T11 Enforcement gates** — CI lint rule failing on the word "cache" on any destructive user-facing surface (AC-11), plus per-module protected-root tests attempting modification without unlock and asserting refusal (AC-7). Files: `lint/detekt-rules/...`, `.github/workflows/ci.yml` additions.

---

## Milestone M4 — Power features

**Exit criteria:** AC-16 passes; compression verified non-destructive.

- **M4-T01 Thumbnail cache module** — OS thumbnail cache path only, Tier 2 only, **default OFF**, review-only, high blast radius documented. Tests: `ThumbnailCacheTest` incl. default-off assertion.
- **M4-T02 Orphaned app dirs** — package-named dir under `/Android/data` or `/Android/obb` with no installed package (All Files Access), review-only, never bulk. Tests: `OrphanedAppDirTest`.
- **M4-T03 Residual files** — heuristic by nature, review-only, never bulk, never default. Tests: `ResidualFilesTest`.
- **M4-T04 App cache measurement** — `StorageStatsManager` measure + deep-link only; the UI states plainly that we cannot clear it on modern Android (V6, §8). Tests: `AppCacheMeasureOnlyTest` asserting no delete entry point exists.
- **M4-T05 Image compression** — re-encode JPEG/WebP/AVIF at chosen quality or target size with optional downscale; **EXIF orientation and timestamps preserved or the operation is refused**; output is always a new file; full-decode verification before the original is offered for quarantine; estimated savings labelled as estimates, real saving measured afterwards (AC-16). Tests: `ImageCompressionRoundTripTest`, `OrientationPreservationTest`, `RefuseWhenOrientationUnpreservableTest`, `OriginalNeverMutatedTest`.
- **M4-T06 Video compression** — Media3 `Transformer` for transcode, resolution cap, bitrate control; validated by V7; FFmpeg only with an explicit licence review. Tests: `VideoCompressionVerifyTest` (full decode + duration + frame count before offering the original).
- **M4-T07 WorkManager orchestration** — long jobs in unique `WorkManager` work with a foreground notification, progress, cancellation and resume; cancellation leaves no partial output; foreground service type `dataSync` per V8. Tests: `WorkCancellationTest`, `ResumeAfterProcessDeathTest`.
- **M4-T08 Scheduled reminders** — reminders only; **no automatic or scheduled destructive action exists in the codebase** (AC-14). Tests: `ReminderIsNonDestructiveTest`; the enforcing architecture test lands in M5-T03.

---

## Milestone M5 — Ship

**Exit criteria:** AC-6, AC-11 and AC-19 pass; accessibility audit clean.

- **M5-T01 Permission-denied matrix** — run the full feature matrix across Tier 0/1/2 on API 26/30/33/36 and assert every feature is FULL or NARROWED, never a dead end, and every narrowed panel names what it excludes and offers an in-context grant (AC-19). Tests: instrumented matrix suite.
- **M5-T02 Zero-network CI assertion** — CI fails if `INTERNET` appears in any merged manifest, if any networking dependency is on the classpath, or if any source file imports a network API (AC-6). Files: `.github/workflows/ci.yml`, `scripts/assert-no-network.sh`, `lint/`.
- **M5-T03 Architecture test** — assert destructive entry points are UI-reachable only and no scheduled/automatic destructive path exists (AC-14); assert `:core:model` and the deck domain have no Android imports (§9.2); assert media never routes through the raw gateway (AC-10 enforcement point).
- **M5-T04 Macrobenchmark, Baseline Profiles and CI gates** — implement the §11.1 budgets as Macrobenchmark tests and gate the build on startup and jank regressions; generate Baseline Profiles; verify on the slowest supported device (decision 13).
- **M5-T05 Accessibility audit** — full TalkBack pass, switch-access pass, 200% font scale, reduced motion, high contrast, landscape, tablet/foldable; fix all findings; keep the audit checklist in `docs/`.
- **M5-T06 Play listing and both declarations** — listing leads with **file manager / media organiser** and frames cleaning as file maintenance (§4.2 condition 1); write the All Files Access declaration (core functionality, non-media-only use, why MediaStore for media) and the Photo and Video declaration (the policy's own "managing and maintaining all of users' photos/videos" example; why the Photo Picker is insufficient); review listing copy against §1.3 anti-goals before release. Files: `docs/play-listing.md`, `docs/permission-declarations.md`.
- **M5-T07 Privacy policy and F-Droid metadata** — privacy policy stating zero collection, no network, on-device only; F-Droid metadata and a reproducible build note; `docs/privacy-policy.md`, `fastlane/metadata/android/en-US/`.

---

## Acceptance-criteria coverage

| AC | Requirement (short) | Owning task(s) |
|---|---|---|
| AC-1 | Journal write-ahead before every op | M0-T06, M0-T17 |
| AC-2 | No destructive bulk op without enumerated review | M3-T03, M3-T10 |
| AC-3 | 30-day quarantine, verified-hash restore | M0-T08, M0-T17 |
| AC-4 | Category never from parent folder name alone | M3-T01 |
| AC-5 | No limbo after process death; reconciliation | M0-T09, M0-T10, M0-T17 |
| AC-6 | Zero network capability | M0-T13, M5-T02 |
| AC-7 | Protected roots unmodifiable by bulk flows | M0-T07, M0-T17, M3-T11 |
| AC-8 | Frozen snapshot; stale items abort visibly | M1-T03 |
| AC-9 | Measured freed; quarantine as reclaimable | M2-T01, M2-T09, M1-T13 |
| AC-10 | CCleaner regression test (media never in non-media category) | M0-T12, M3-T01, M3-T11, M5-T03 |
| AC-11 | No "cache" on a destructive heuristic surface | M0-T14, M3-T11, M5-T06 |
| AC-12 | Sanity ceilings force list review | M3-T02 |
| AC-13 | Every number expands to its file list | M2-T02 |
| AC-14 | No automatic/scheduled destructive action | M4-T08, M5-T03 |
| AC-15 | Cancellation leaves originals untouched | M0-T08, M1-T06 |
| AC-16 | Non-destructive, verified compression | M4-T05, M4-T06 |
| AC-17 | Restore recreates paths; conflicts never overwrite | M0-T08, M2-T06 |
| AC-18 | Journal always exportable and complete | M2-T07 |
| AC-19 | Works narrower without restricted permissions | M0-T13, M1-T14, M5-T01 |

## Spec-section coverage

| Spec section | Task(s) |
|---|---|
| §4 Permissions / tiers / deletion paths | V1–V5, M0-T11, M0-T12, M0-T13, M0-T16, M1-T01 |
| §5 Core experience (deck, gestures, slots, viewer, sessions) | M1-T02 … M1-T14 |
| §6 Safety architecture | M0-T06, M0-T07, M0-T08, M0-T09, M3-T01, M3-T02, M3-T03, M2-T01, M2-T05, M2-T06, M2-T09 |
| §7 Dashboard | M2-T01 … M2-T09 |
| §8 Cleaner modules | M3-T04 … M3-T10, M4-T01 … M4-T04 |
| §9 Technical architecture | M0-T01 … M0-T15, M1-T01, M1-T02, M1-T11, M4-T07 |
| §10 Data model | M0-T02, M0-T04 |
| §11 Non-functional | M5-T04, M5-T05, M2-T01, M0-T14 |
| §12 Acceptance criteria | whole plan (see coverage table) |
| §13 Roadmap | milestone boundaries |

## Self-review

**1. Spec coverage:** every §5–§13 requirement maps to a task above; §1–§4 are constraints carried in Global Constraints and enforced by M0-T06/T07/T12/T13 and M3-T01/T02/T03/T11. The `[VERIFY]` markers are owned by V1–V9. The `[OPEN]` decisions are captured in the decisions table with owners.

**2. Placeholder scan:** no "TBD"/"TODO"/"implement later" steps; every M0 task carries concrete test code and run commands; M2–M5 tasks name exact files, interfaces and test names.

**3. Type consistency:** `StorageGateway` signatures match §9.3 exactly; `JournalEntry`/`JournalState`/`OpKind`, `Category`/`CategoryDefault`, `Tier`, `Availability`, `Slot`/`SlotMode`, and the `MoveResult`/`QuarantineResult`/`RestoreResult`/`DeleteResult` names are defined once in M0-T02/T05 and reused unchanged in M1–M5. Decorator order (`ProtectedRoots → Journaling → Quarantine → Concrete`) is asserted by M0-T15 and reused by M0-T17, M3-T11 and M5-T03.

## Gaps and risks in this plan

- **M2–M5 depth is task-level, not step-level.** Milestone M2 begins by re-expanding its tasks to the M0/M1 step depth, because their interfaces settle only once M0/M1 land. This is intentional per the plan's depth rule; it is the largest planning risk.
- **V1–V9 can change the plan.** If V3 (rename semantics) or V7 (Transformer coverage) fail, M0-T08 and M4-T06 change materially. Resolve V1–V5 before starting M0, V6 before M2, V7/V8 before M4, V9 before M3-T05.
- **Open decisions are assumptions.** Decisions 3 (staged by default), 4 (move default) and 7 (quarantine location) are load-bearing in M1; if the owner picks differently, M1-T05/T06/T08 are re-planned, not patched.
- **No repository exists yet** (no `git init`, no CI host chosen). M0-T01 assumes git + GitHub Actions; swap the CI file if the owner prefers another host.
- **`docs/RESEARCH-PITFALLS.md` and `docs/ANDROID-CONSTRAINTS.md` are referenced by the spec but not present.** V1 creates `ANDROID-CONSTRAINTS.md`; a separate task should fold `RESEARCH-PITFALLS.md` into §6/§12 if the owner still wants it (`docs/RESEARCH-PITFALLS.md` is absent from the working tree).
