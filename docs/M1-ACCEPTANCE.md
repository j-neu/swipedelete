# SwipeDelete — M1 (The core loop) acceptance record

Base: `615c67c` (M0 merge) → head: `d4ca24c` (main).

This records what M1 actually verified, the defects the verification found, and what remains unproven. It mirrors `docs/M0-ACCEPTANCE.md`'s honesty rules: an item is marked verified only where a command was run and its output seen.

## Scope

M1 is the core triage loop: MediaStore catalogue, pure-Kotlin deck engine, swipe surface, staged deletes with Apply, five-slot dock, viewer (image/GIF/audio/video/APK/unsupported), autoplay + memory ceiling, sessions/resume/summary, and the accessibility/capability-request loop. Tasks 28–41 of `docs/tasks.md`, expanded in `docs/M1-PLAN.md`.

## Executed status

| Task | Area | Commit |
|---|---|---|
| 28 (M1-T01a) | Read-only `SourceScanPort` (D1) | `b8ac52f` |
| 29 (M1-T01) | MediaStore gateway (AC-10) | `496ef1d` |
| 30 (M1-T03) | Deck builder + frozen snapshot (AC-8) | `6d44c04` |
| 31 (M1-T02) | Pure deck engine | `6534c15` |
| 32 (M1-T04) | Swipe surface | `9854784` |
| 33 (M1-T05) | Staging strip + Apply | `e79d011` |
| 34 (M1-T06) | Five-slot dock (AC-15) | `e1596d1` |
| 35 (M1-T07) | Undo stack + history strip | `911e87a` |
| 36 (M1-T08) | Viewer image/GIF/audio | `83bc7e3` |
| 37 (M1-T09) | Viewer video | `1d441b2` |
| 38 (M1-T10) | Viewer APK/documents/unsupported | `a0e238e` |
| 39 (M1-T11) | Autoplay + memory ceiling | `d3bdf57` |
| 40 (M1-T12/T13) | Sessions, resume, honest summary (AC-9) | `2840b84` |
| 41 (M1-T14) | Action bar + capability requests (AC-19, D2) | `e789223` |

## Unit verification

`.\gradlew.bat testDebugUnitTest` — **344 tests, 0 failures** (2026-10-03):

| Module | Tests |
|---|---|
| `:core:storage` | 102 |
| `:feature:deck` | 106 |
| `:feature:viewer` | 49 |
| `:app` | 35 |
| `:core:designsystem` | 16 |
| `:feature:settings` | 15 |
| `:core:common` | 11 |
| `:core:database` | 10 |

## Device pass (API 36, `Medium_Phone` AVD)

`connectedDebugAndroidTest` on the emulator:

| Module | Result |
|---|---|
| `:core:storage` | 10/10 pass |
| `:feature:deck` | 3/3 pass |
| `:feature:viewer` | 5/5 pass |

## Defects found by the verification and fixed

Two real defects were invisible to the unit pass and only surfaced when the device pass compiled and ran the instrumented sources:

1. **`feature:deck` had no `androidTest` dependencies** (from Task 39). `:feature:deck:compileDebugAndroidTestKotlin` failed with unresolved `junit`/`AndroidJUnit4` because the module declared only `testImplementation` deps and no `testInstrumentationRunner`. Fixed in `d4ca24c` by adding the runner and `androidTestImplementation(libs.junit / androidx.test.core / androidx.test.runner / androidx.test.ext.junit)`, mirroring `:core:storage` and `:feature:viewer`.
2. **`VideoViewerInstrumentedTest` accessed `ExoPlayer` on the wrong thread** (from Task 37). All 5 tests failed with `IllegalStateException: Player is accessed on the wrong thread` — Media3 binds the player to the main thread while the instrumentation thread ran the calls. Fixed in `d4ca24c` by marshalling every player access through `InstrumentationRegistry.runOnMainSync` while keeping the latch waits on the instrumentation thread.

Also found and recorded before M1 began: **Coil is unusable at `compileSdk 36`** (`coil-compose:3.6.3` pulls Compose 1.12.0 → requires `compileSdk 37` + AGP 9.1). Decision **D7**: card thumbnails decode via `ImageDecoder`, never Coil.

## M0 carry-forwards honoured

- **D1** scan progress/truncation is now reachable through the read-only `SourceScanPort` (Task 28); the frozen `StorageGateway`/`Page` and `StorageGatewayContractTest` were not changed.
- **D2** in-context grants are wired: `rememberCapabilityRequester()` builds an Activity-bound controller from `rememberLauncherForActivityResult` (Task 41); the Application-scoped singleton keeps its null seams.
- **Recovery readiness gate** (D6): the Apply path calls `RecoveryCoordinator.awaitReady()` before any destructive call, and `NoMutationBeforeRecoveryTest` asserts it.
- **WholeLibraryDecks gate**: the whole-library affordance asks the gate about `Feature.WholeLibraryDecks` (not `DeckTriage`); `TieredDeckNarrowingTest` covers the Tier 0 → Tier 1 transition.

## Process note

Task 32 (swipe surface) was completed by the coordinator directly. Two Agent Manager sessions stalled immediately after writing the failing test (the first also left a `strings.xml` that collided with Task 35's), so the implementation was written and verified in the main worktree. The two abandoned worktrees were pruned.

## Not verified / open (honest limits)

- **M1 exit criterion not measured.** "A 500-item deck triages end-to-end at ≥ 60 fps with full undo" requires a macrobenchmark or a real-content device run. No frame-timing measurement exists yet.
- **AC-8 and AC-15 are covered by unit tests** (`StaleItemAbortTest`, `CrossVolumeMoveCancelTest`) but there is no end-to-end device scenario exercising a stale item or a cancelled cross-volume move through the assembled UI.
- **Multi-API gap unchanged.** API 26/30/33 images are still unavailable; no multi-API claim. Owned by the M1+ matrix task.
- **AC-5 on-device gap unchanged.** OS-level process kill is covered only by the Task 19 host sweep; the device path uses an abandoned-coroutine boundary.
- **V6–V9 remain deferred** to M2/M3/M4 as the master plan requires.
- Duplicate/dead code noted by sessions (e.g. a designsystem `StagingStrip`/`SlotDock` alongside the feature copies) has not been reconciled.
