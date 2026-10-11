# SwipeDelete — M1 (The core loop) acceptance record

**Status: NOT ACCEPTED.** Recorded 2026-10-11 against `main` at `d73c046`.

The loop is assembled and every automated suite is green, but the re-review found one Critical and nine Important defects in the assembled app. Most are invisible to the suites because the suites exercise library modules and fakes, not a real user's files flowing through the assembled graph.

This record supersedes the 2026-10-03 version, which predated the whole-branch review and described library modules as if they were the shipped loop. It keeps `docs/M0-ACCEPTANCE.md`'s honesty rule: an item is marked verified only where a command was run and its output seen. Findings reached by reading code are marked **read, not run**.

## Scope

M1 is the core triage loop: MediaStore catalogue, pure-Kotlin deck engine, swipe surface, staged deletes with Apply, five-slot dock, viewer, autoplay and memory ceiling, sessions/resume/summary, and the accessibility and capability-request loop. Tasks 28–41 of `docs/tasks.md` (expanded in `docs/M1-PLAN.md`), plus two fix tasks added after the first review:

| Task | What it did | Commit |
|---|---|---|
| M1-T15 | Assembled the loop (`DeckViewModel`, `DeckRoute`, nav wiring, SAF + MediaStore routing) and addressed the first review's findings 1–9 | merge `6ee3c6d` |
| M1-T15b | Fixed the MediaStore contract fixture that made three device tests fail | `5a62a5a`, merge `d73c046` |

## What was run (2026-10-11, `d73c046`)

`.\gradlew.bat testDebugUnitTest` — **369 tests, 0 failures.**

| Module | Tests |
|---|---|
| `:feature:deck` | 125 |
| `:core:storage` | 102 |
| `:feature:viewer` | 51 |
| `:app` | 38 |
| `:core:designsystem` | 16 |
| `:feature:settings` | 15 |
| `:core:common` | 11 |
| `:core:database` | 11 |

`.\gradlew.bat connectedDebugAndroidTest` on the API 36 `Medium_Phone` emulator (`ANDROID_SERIAL=emulator-5554`) — **26 tests, 0 failures, 2 skipped.**

| Module | Result |
|---|---|
| `:core:storage` | 10/10 pass |
| `:feature:viewer` | 5/5 pass |
| `:app` | 4/4 pass |
| `:feature:deck` | 3/3 pass |
| `:feature:settings` | 2 pass, 2 skipped (`assumeTrue` on real picker grants) |

### The T15b failures were a fixture defect

After the T15 merge, three legs of the frozen `StorageGatewayContractTest` failed against the real MediaStore gateway. The gateway was not at fault. The fixture reused one fixed directory, `Pictures/SwipeDeleteM1T01`; files from earlier installs survived there because a reinstalled test package no longer owns those rows. MediaProvider then renamed each new insert to `a (1).gif`, `a (2).gif`, and the contract's exact-name lookups missed.

Verified by reverting the fix (the same three tests fail) and re-applying it (10/10, four runs). Each run now uses its own directory and an `@After` clears the rows while the package still owns them. The frozen contract test and the gateway were not changed.

## Status of the first review's findings

| # | Finding | Status |
|---|---|---|
| C1 | Core loop not assembled | **Fixed** for the swipe/stage/apply/move/viewer path. The summary screen, resume and TAG slots are wired but unreachable or inert — see I4, I6, I7 below. |
| C2 | AC-8 stale check had no production caller | **Fixed for Apply** (`GatewaySessionMutations.staleReason`, tested). Not applied to slot moves — see I8. |
| I3 | Slot move bypassed the recovery gate | Fixed (`SlotMoveCoordinator` awaits `MutationGate`). |
| I4 | Scan truncation not carried | Fixed (`DeckSnapshot.truncated`, partial-deck banner). |
| I5 | Move undo was a no-op | Fixed in the happy path; failure handling is missing — see I8. |
| I6 | Two Apply paths | Fixed (`StagingApplier` removed). |
| I7 | Summary counted applied items as staged | Fixed for a full success; over-counts on a partial result — see C1 below. |
| I8 | Drag-down did not reveal the dock | Fixed. |
| I9 | Audio/video bypassed the gateway | Fixed (`GatewayDataSource`). |

## Re-review findings

All locations are in `main` at `d73c046`. Everything in this section is **read, not run** unless it says otherwise.

### Critical

**C1 — Apply reports items as deleted that were never moved.**
`SessionController.applyStaged` (`SessionController.kt:326-332`) marks every non-stale staged item as decided and removes it from the staging strip, whatever the gateway returned. `GatewaySessionMutations.quarantine` reduces the result to a count and drops the per-item failures. An item refused as `protected_root`, a batch stopped by `InsufficientSpace`, or a failed original delete all look identical to success: the item leaves the strip and the summary counts it under "quarantined". No file is lost, but the app states a deletion that did not happen, which is the exact failure the product exists to prevent. `SessionApplyTest` has no partial-result case.

### Important

**I1 — Whole-library (MediaStore) items can never be quarantined; every one is a protected root.**
`StorageModule.kt:284` hard-codes `unlocked = { emptySet() }` and there is no unlock UI. The default patterns protect `DCIM/`, `Pictures/`, `Movies/`, `Music/`, `Documents/`, `Download/` and any media older than five years. MediaStore only stores media under those directories, so every Tier 1 Apply is refused in full — and, through C1, shown as done. This needs an owner decision: §6.3 requires a typed unlock in Settings before acting inside these roots, but the personas' junk (`Pictures/Screenshots`, `Download`) lives exactly there, and the unlock is not planned until M3.

**I2 — SAF items are not matched by protected roots at all.**
`DocumentTreeWalker` sets `MediaItem.path` to the document's `content://` URI, whose path separators are percent-encoded, and `relativePath` to the path inside the granted tree. `ProtectedRootsStorageGateway` matches on path segments, so a tree granted at `DCIM/Camera` or `Pictures/` yields items with no `dcim` or `pictures` segment and none is refused. AC-7 is therefore not enforced for SAF sources, the opposite of I1. Only a sub-folder inside the tree that happens to be named like a pattern, or the five-year rule, would trigger.

**I3 — Tier 1 has no way to delete media the app does not own.**
`MediaStoreStorageGateway.deletePermanently` calls `resolver.delete` directly; `createDeleteRequest` and `createTrashRequest` appear nowhere in the codebase. On API 30+ without All Files Access the platform refuses that delete for other apps' media. `QuarantineStore.store` would then have copied the file, failed the delete, and thrown `original_delete_failed`, leaving the original plus a quarantine copy. The device tests pass because they only use rows the test inserted itself. Masked today by I1.

**I4 — The session summary cannot be reached.**
`DeckRouteContent` accepts `onFinish` (`DeckRoute.kt:86`) and never calls it, and nothing else sets the session to `COMPLETED`. The summary, the exit prompt for a non-empty staging strip, and the "restore everything" button are unreachable in the app. An exhausted deck shows the generic empty state.

**I5 — The summary's numbers and its restore button are stubs.**
`DeckViewModel.kt:83-84` passes `measuredFreed = { 0L }` and `reclaimable = { 0L }`, so the summary would show "0 B reclaimable" after quarantining real bytes (AC-9). `GatewaySessionMutations.restoreSession` returns 0 without restoring anything, because journal rows carry no session id; the view model ignores the count, so the panic button would silently do nothing. The second gap is documented in the code; neither is surfaced to the user.

**I6 — Resume is never invoked.**
`SessionController.resume` has no production caller. `DeckViewModel.init` always starts a new session on the first source, so a crash or exit loses the position and leaves an `ACTIVE` row behind on every launch. `SessionResumeTest` covers the controller only.

**I7 — TAG slots do nothing.**
`SessionController.applySlotMove` builds `SlotMoveCoordinator` without a tag sink, so it uses `SlotTagSink.NoOp`, and only `Moved`/`Renamed` advance the deck. Dropping an item on a slot with no destination writes no tag and leaves the card in place.

**I8 — Slot moves skip the stale check, and a failed undo is reported as undone.**
`applySlotMove` does not call `staleReason`, so AC-8 covers Apply only. `undoMove` ignores the `MoveResult` of the move back and returns silently when it has no recorded origin; the origins are in memory only. In both cases the deck state says the move was reverted while the file stays at the destination.

**I9 — Errors in deck coroutines are not handled.**
Every `DeckViewModel` action is a bare `viewModelScope.launch`. `controller.start` runs the scan uncaught, so a source whose grant was revoked would crash the app on launch rather than show a narrowed state (AC-19). `DeckScreen`'s `runCatching` wraps a call that only launches a coroutine, so it catches nothing.

### Minor

- The two-finger-tap undo (§5.2) is not implemented; undo is reachable from the staging and history strips.
- The move-unavailable banner has no dismiss (`dismissMoveUnavailable` is unwired) and the dock never closes after a selection.
- Granting photo/video from the whole-library affordance does not rebuild the deck; it stays on the first source.
- `GatewayDataSource` seeks by reopening the stream and skipping, which is linear in file size on large videos.
- `SourceRoutingStorageGateway.quarantine`/`deletePermanently` route a whole batch by its first item. Safe today because the quarantine store calls them one item at a time; the class comment says "per item".
- `DeckViewModel.refresh` calls `gateway.sources()` and does linear lookups on every swipe; relevant to the unmeasured frame-rate criterion.

## Not verified (honest limits)

- **M1 exit criterion not measured.** "A 500-item deck triages end to end at ≥ 60 fps with full undo" has no frame-timing measurement.
- **No end-to-end run of the assembled loop on real content.** The findings above come from reading; none of the instrumented tests drives the app through a real Apply on files the test does not own. That gap is why C1 and I1–I3 were not caught.
- **AC-8 and AC-15** are covered by unit tests and the contract suite, not by a device scenario through the UI.
- **Multi-API gap unchanged.** Only API 36 has run; API 26/30/33 images are unavailable.
- **AC-5 on-device gap unchanged.** An OS-level process kill is covered only by the host sweep.
- **CI has never run.** The `packages:` block in `ci.yml` is malformed and must be fixed before the first run (see the SDD ledger).
- **V6–V9 remain deferred** to M2/M3/M4.

## What acceptance needs

1. Fix C1 and add a partial-result Apply test.
2. An owner decision on I1/I2: how protected roots apply to a one-item-at-a-time deck, and make both backends enforce the same rule.
3. Decide Tier 1 deletion (I3): a `createDeleteRequest`/`createTrashRequest` consent step, or state plainly that whole-library decks are review-only until Tier 2.
4. Fix I4–I9, or cut each surface from M1 explicitly.
5. One instrumented end-to-end scenario through the assembled UI on files the test does not own.
6. Measure the 500-item frame-rate criterion.
