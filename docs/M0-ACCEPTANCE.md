# M0 acceptance record — quarantine, process death, restore

**Recorded:** 2026-09-20 · **Branch:** `feature/m0-foundation` · **Task:** M0-T17 (Task 26), the M0 exit gate.

**Device:** `emulator-5554`, API **36** (Android 16, security patch of the AVD), AVD `Medium_Phone`
(`google_apis_playstore`). All four criteria below were verified on that device against **real files**
on shared external storage (`/storage/emulated/0/SwipeDeleteM0Acceptance/…`, with All Files Access
granted to `com.swipedelete.app`), a **real on-disk Room database** (`m0-acceptance.db`) and the
**real assembled graph** from `assembleStorageGraph` (the same construction path Hilt uses).

**API coverage shortfall (deliberate, not an oversight).** M0's exit gate was scoped to pass on API
26, 30, 33 and 36 emulators. API 26 and API 30 system images are not installed in this environment and
downloading them was declined for this session, so **only API 36 was exercised. No multi-API claim is
made.** The API-26/28 / API-30/33 legs remain unverified.

## Per-criterion result

| criterion | what was verified on device | test | API | unverified |
|---|---|---|---|---|
| **AC-1** — no file operation without a prior committed journal entry | A quarantine of two items wrote exactly two `PENDING`-then-`DONE` `QUARANTINE` rows (one per item, committed before the move) and the following restore wrote exactly one `RESTORE` row; all three ended terminal `DONE` with no error, and `journal.unresolved()` was empty afterwards. | `M0AcceptanceTest.everyMutationProducedExactlyOneJournalRowWithATerminalState` | 36 | — |
| **AC-3** — 30-day quarantine with verified-hash restore | A real 150 KiB file was quarantined through the production stack (original gone, verified copy + JSON sidecar on disk, SHA-256 recorded), then restored; the restored bytes hashed identically (hash comparison, not length). A second leg proved a restore into an **occupied** absolute path returns `Conflict` and leaves both the occupying file and the quarantined copy byte-for-byte untouched. | `M0AcceptanceTest.aFileQuarantinedThenRestoredComesBackByteIdentical` | 36 | 30-day retention *expiry* behaviour (recorded, never auto-deletes) is unchanged and not re-tested here |
| **AC-5** — process death leaves no item in an unknown state | The quarantine coroutine was abandoned at two **real** filesystem boundaries (mid-copy; and after the verified copy, before the original delete) against real files and a real Room journal. Both left the write-ahead row `PENDING`; the real `Reconciler` then classified the copy-absent case `ABORTED` (original intact and unchanged) and the copy-present case `DONE` (verified quarantine copy retained), with **no item lost** and `journal.unresolved()` empty. | `M0AcceptanceTest.killingTheProcessMidQuarantineLeavesNoPendingRecordUnresolved` | 36 | **OS-level process kill.** The abandonment is an in-process `CancellationException` at a real I/O boundary, not an `am force-stop`/`kill -9`; see below |
| **AC-7** — protected roots cannot be modified by any bulk flow | A real file under a real `DCIM/Camera/` directory was refused with reason `protected_root`, wrote **zero** journal rows, produced **zero** quarantine copies, and was left byte-identical. | `M0AcceptanceTest.aProtectedRootItemProducesZeroJournalRowsAndZeroMoves` | 36 | — |

### What AC-5 does and does not prove

A single instrumentation run cannot `am force-stop` its own process, so the on-device AC-5 test does
not perform an OS-level kill. It abandons the quarantine coroutine at a real filesystem operation
(the host chaos sweep's technique) over **real files and a real Room journal**, then runs the real
`Reconciler` and asserts every row is terminal and no item is lost. A genuine cross-process kill was
not attempted: the only process that has the production classes loaded is the app/instrumentation
process itself, whose death ends the run before any assertion — a test-only second-process harness
would need a new component in a separate APK process and still could not drive the production stack
without duplicating the app's classes. **The OS-level kill remains proven only by the host chaos
sweep** (Task 19's 22-stop-point sweep over the fake gateway), not on a device.

## Instrumented suites — device pass

| suite | command | result |
|---|---|---|
| `:core:storage` | `.\gradlew.bat :core:storage:connectedDebugAndroidTest` | **4/4 pass** (`SafStorageGatewayInstrumentedTest`: the frozen `StorageGatewayContractTest` over `TestDocumentsProvider`) |
| `:feature:settings` | `.\gradlew.bat :feature:settings:connectedDebugAndroidTest` | **4 tests: 2 pass, 2 skipped** (`assumeTrue` on real picker grants) — including the new non-gated V2 leg |
| `:app` | `.\gradlew.bat :app:connectedDebugAndroidTest` | **4/4 pass** (`M0AcceptanceTest`) |

Host unit tests after the fixes: `:core:storage` 83, `:feature:settings` 15, `:app` 29, `:core:database` 4 — all green.

## Defects the device pass exposed (all fixed)

1. **`DocumentsProvider` missing `MANAGE_DOCUMENTS` (test manifest).** API 30+ `DocumentsProvider.attachInfo`
   requires the provider to be exported, grant URI permissions **and** be protected by `MANAGE_DOCUMENTS`
   on read and write. `TestDocumentsProvider` crashed the process before the first test, so the SAF suite
   reported "Starting 0 tests". Fixed in `core/storage/src/androidTest/AndroidManifest.xml`.
2. **`TestDocumentsProvider.onCreate` used `ApplicationProvider`.** Providers are installed at process
   start, before the instrumentation is registered, so `ApplicationProvider` threw. Fixed by resolving the
   base directory from the provider's own context and publishing it.
3. **Authority resolution picked a system provider (the Task 20 reviewer's flagged unverified alignment).**
   `queryContentProviders(null, uid, 0)` returns the whole system (the uid argument does not filter), and
   several system authorities end with `.documents`, so the first match was `externalstorage` and every
   tree query returned nothing. Fixed by resolving this package's own `DOCUMENTS_PROVIDER` registration.
4. **SAF gateway minted `MediaItem.id` with `/` (production).** `SafStorageGateway` and `DocumentTreeWalker`
   used `sourceId/relativePath` while the raw gateway and the frozen contract use `sourceId:relativePath`,
   so the contract's cancellation assertion failed on a real device. Unified on `:`. **This is a
   production change and has its own commit.**
5. **Backticked prose test names cannot be dexed at `minSdk 26`.** `OnboardingInstrumentedTest`'s
   space-containing method names made `:feature:settings:dexBuilderDebugAndroidTest` fail with "Space
   characters in SimpleName … are not allowed prior to DEX version 040", so the suite had never built.
   Renamed to camelCase (the app acceptance suite is camelCase for the same reason).
6. **`= runBlocking { … }` returning non-`Unit`.** One renamed test's last statement returned `Int`, so
   JUnit rejected the class ("Method … should be void"). Made explicitly `runBlocking<Unit>`.

Full detail, commands, RED states and evidence: `../.superpowers/sdd/tasks/task-26-report.md`.

## Whole-branch review fix wave (2026-09-20)

The final whole-branch review returned **0 Critical and 8 Important** findings; all
eight were fixed on this branch before the M1 hand-off. The findings that change
this *acceptance record* (rather than only code/tests/CI) are recorded here. Full
per-finding evidence: `../.superpowers/sdd/tasks/final-fix-report.md`.

### Finding 1 — corrected M1 carry-forward: scan progress must not rely on `enumerateDetailed`

The Task 20 parked carry-forward ("M1 will surface the walker's truncation flag via
`SafStorageGateway.enumerateDetailed`") is **wrong as written**. The fix wave made
the concrete `SafStorageGateway` non-injectable — the only injectable storage type
is the decorated `StorageGateway`, enforced by the generalised reflective scan in
`app/src/test/.../di/StorageGraphOrderTest.kt` — so `enumerateDetailed`, like every
other gateway-only capability, is no longer reachable by any consumer.

**Corrected carry-forward (M1 owns it):** M1 must either thread scan
progress/truncation through the frozen storage port or add an explicit
progress/enumeration port on the assembled graph. No such port is invented in M0.

### Finding 5 — recovery readiness gates M1 mutation

`RecoveryCoordinator` now publishes `StateFlow<Boolean> ready` and a documented
`suspend fun awaitReady(): RecoveryReport` guard (re-exposed as
`SwipeDeleteApplication.recoveryReady`). **M1's deck must not perform any
destructive action (delete, quarantine, move, restore) before reconciliation has
completed:** it must consult `awaitReady()` first. The guard returns only once the
report exists; if reconciliation fails, readiness stays `false` and mutation is
blocked (the safe direction).

### Finding 6 — AC-1 durability now empirically exercised against real Room

The 22-stop-point kill sweep now also runs over a **real Room database**
(`core/storage/src/test/.../ChaosRoomReconciliationTest.kt`, reusing the same
`ChaosEnv` harness), so every `PENDING` insert and terminal mark is a committed
SQLite write, not an in-memory list write. The four invariants (no loss, no limbo,
honest reconciliation, nothing destroyed) hold over the durable path.

**Still open:** an **OS-level** process kill (`am force-stop` / `kill -9`) is still
not exercised on a device. The on-device AC-5 test abandons a coroutine at a real
I/O boundary instead (see "What AC-5 does and does not prove" above). This remains
a documented gap.

### Finding 7 — which AC-17 leg is proven for SAF

The frozen contract's `restoreRefusesToOverwriteAndReportsConflict` uses a
synthetic non-absolute `originalPath`, so on SAF it exercises the store's
documented **pathless** refusal (`no_raw_original_path`) — a genuine refusal, but
**not** the occupancy branch its name suggests. The record is now explicit:

- **Pathless leg (host):** proven in
  `core/storage/src/test/.../quarantine/QuarantineRestoreTest.kt`
  (`restore of a SAF-shaped synthetic original path is the documented pathless refusal`).
- **Occupancy leg (device):** proven in
  `SafStorageGatewayInstrumentedTest.restoreRefusesAnOccupiedAbsoluteOriginalPath`,
  which gives the item a real absolute original path so restore reaches the
  occupancy check. Device pass: **5/5** on `emulator-5554`, API 36 (the four frozen
  contract tests plus this leg).

### Finding 8 — accepted, owned gap: the API 26/30/33 device legs

`docs/tasks.md` required PASS on API 26, 30, 33 and 36; only **API 36** ran (the
API 26/30 system images were unavailable, per the shortfall note above). This is an
**accepted scope reduction**, owned — not an oversight:

- **Owner:** the device/CI matrix task (the first task that owns multi-API
  instrumentation in the M1+ plan).
- **Why it matters:** the V2 20-grant durability check and the re-hash/rename
  behaviours are API-band-sensitive, so the missing legs are exactly the ones that
  could regress there.
- **Not a waiver:** no multi-API claim is made; API 26/30/33 remain unverified
  until that owner runs them.

### Also fixed in the wave (no acceptance-record change)

- **Finding 2:** CI now runs the unqualified `testDebugUnitTest` (all modules, not
  only `:app`) and installs platform `android-36` + build-tools explicitly.
- **Finding 3:** an unlistable directory inside a raw delete subtree is treated as
  media-bearing (AC-10), so it is refused rather than `deleteRecursively()`d.
- **Finding 4:** reconciliation resolves identity through a new `ItemLocator` on
  each concrete gateway instead of the `content://`-shape probe (which reported
  present SAF files as absent).

