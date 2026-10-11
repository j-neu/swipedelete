# SwipeDelete — M1 (The core loop) acceptance record

**Status: ACCEPTED (see the T19 update).** Recorded 2026-10-11 against `main` at `d73c046`.

The loop is assembled and every automated suite is green, but the re-review found one Critical and nine Important defects in the assembled app. Most are invisible to the suites because the suites exercise library modules and fakes, not a real user's files flowing through the assembled graph.

This record supersedes the 2026-10-03 version, which predated the whole-branch review and described library modules as if they were the shipped loop. It keeps `docs/M0-ACCEPTANCE.md`'s honesty rule: an item is marked verified only where a command was run and its output seen. Findings reached by reading code are marked **read, not run**.

## Update — M1-T19 deck frame time (2026-10-11, branch `m1-t19-deck-frame-time`)

**Status: ACCEPTED, with the limits below recorded.** The last blocker, the deck's frame time, is fixed and measured. Two readings of the criterion are left for the product owner to confirm (see "Limits").

- **Cause, found by stage breakdown rather than guesswork:** the full-size card used Compose `clickable` with the default platform ripple. On Android 12+ that ripple is shader-based and animates across the whole card after every touch, which kept the render thread busy (command-issue time p50 13.2 ms) and kept the screen redrawing.
- **Fix:** the card's tap target no longer draws a press ripple (`SwipeCard.kt`). This removes visible press feedback on the card; tapping still opens the viewer.
- **Measured on the Android 13 phone (OnePlus Nord CE, 90 Hz, debug build, real thumbnails, touch injected in real time):**

| 500 items | Before | After |
|---|---|---|
| Median frame | 42.6 ms | 10.2 ms |
| p95 / p99 | 51.1 / 63.6 ms | 17.4 / 21.5 ms |
| Frames over 16.7 ms | 84.5% | 6.7% |
| Frames over 33 ms | n/a | 0.04% |

  Full undo of all 500 decisions: 2.8 s, back to the first card, staged and kept empty, no file touched.
- **Earlier full-undo timeout was not an undo defect.** Undo passed on the emulator and the phone; the failed runs coincided with the phone dropping off USB. The test now fails only when undo stops making progress and logs its duration. The cause of those failures is not proven.
- The frame-rate test also logs a per-stage breakdown (`DeckFps` tag).

### Limits

- Debug build only; a release-like build was not measured (expected to be faster, not verified).
- 93.3% of frames fit the 60 fps budget and the median is 10.2 ms; "at >= 60 fps" is read as smooth in practice, not every frame. One device (API 33) plus the API 36 emulator.
- Carried forward: session-restore entries are in memory only; MediaStore rows are review-only; CI has never run on GitHub (no remote yet), but its exact command passes locally.

## Update — M1-T18 restore and thumbnails (2026-10-11, branch `m1-t18-saf-restore`)

**Status: NOT ACCEPTED.** One blocker is left: the deck's frame time. T17 was merged (`46307cc`).

- **SAF restore — fixed (`d1af66f`).** A quarantined folder-grant item is now restored to its original tree path through a new `OriginalRestorer` capability on the concrete gateway: never overwriting, recreating a missing folder, hash-verified, and with the quarantine copy removed only after a verified restore. Host tests cover restore, folder recreation, occupancy and a revoked grant. The end-to-end device test restores through the summary screen and checks the bytes: 3/3 on the API 36 emulator and on the Android 13 phone. A MediaStore row still cannot be restored this way (it also cannot be quarantined; see T16 I3).
- **Blank deck cards — found and fixed (`5ed2a5a`).** A folder-grant item's `uri` is the gateway's `treeUri/relativePath` form, which `ContentResolver` cannot open, so every such card and staging tile was blank: the user was deciding without seeing the image. Thumbnails now read through the gateway's stream, off the main thread. A device test asserts a decoded image is on the card.
- **T16's SAF protected-root matching corrected (`e7d0eab`).** It decoded only the last URI segment and so missed that same real URI shape; the whole URI is now decoded.

**The T17 frame-time figures are no longer representative**: they were taken while cards were blank, so no image was being decoded. The re-measurement on the phone was interrupted when the phone dropped off USB mid-run and has not been repeated. The test app `com.swipedelete.feature.deck.test` may still be installed on the phone from that interrupted run.

Run on the branch head: `testDebugUnitTest` **414 tests, 0 failures**; API 36 emulator `:core:storage` 10/10, `:feature:deck` 7 pass + 1 skipped (opt-in frame-rate run).

Needed for acceptance now: re-measure the 500-item frame time with real thumbnails, find the per-frame cost, fix it, and measure again on a release-like build.

## Update — M1-T17 device acceptance (2026-10-11, branch `m1-t17-device-acceptance`)

**Status: NOT ACCEPTED.** The two missing acceptance pieces now exist and were run. One passes; the exit criterion fails; and the end-to-end run found a restore defect. The T16 fix wave was reviewed by the product owner and merged (`a5f2afc`).

Both tests live in `feature/deck/src/androidTest/.../e2e/` and drive the real `DeckViewModel` and `DeckRoute` over a real SAF tree (the shared `TestDocumentsProvider`), the production decorator order, an on-disk Room journal and a real quarantine directory. All files are in the test app's private cache directory. What they are **not**: the Hilt graph, `AppNavHost` or `RecoveryCoordinator`, which live in `:app`.

### 1. End-to-end Apply through the assembled deck — passes

`DeckEndToEndInstrumentedTest`, 2/2 on the API 36 emulator and on a OnePlus Nord CE (EB2103, Android 13 / API 33) — the first run of anything on an API other than 36. A touch swipe only stages; Apply moves the file to quarantine byte-identical, writes one `QUARANTINE`/`DONE` journal row, and leaves a kept item untouched.

### 2. Defect found: a quarantined folder-grant (SAF) item cannot be restored

`QuarantineStore.restore` only writes back to a raw absolute path and refuses a document URI with `no_raw_original_path`. Folder grants are the app's default tier and, since T16, the only path that can delete at all — so today **nothing the deck quarantines can be restored from inside the app**. AC-3 ("verified-hash restore") was accepted in M0 on raw paths only. The bytes are not lost: the verified copy stays in quarantine.

Fixed on this branch: the restore button no longer fails silently; a shortfall is shown ("N item(s) could not be restored and are still safe in quarantine"). The second end-to-end test pins that behaviour and is written to fail the day SAF restore works, so it gets replaced by the byte-identical check. **SAF restore itself is not built.**

### 3. Exit criterion measured — fails

`DeckFrameRateInstrumentedTest` (opt-in: `-Pandroid.testInstrumentationRunnerArguments.deckFps=true`) swipes a 500-item deck of real 1080 px JPEGs with real-time injected touch at thumb pace and times every drawn frame with the platform's `FrameMetrics`. Functionally it passes: 500 decisions by touch, full undo back to the first card, no file touched.

OnePlus Nord CE, Android 13, 60 Hz, **debug build**, 2026-10-11:

| Deck | Frames | p50 | p90 | p99 | max | Over 16.7 ms |
|---|---|---|---|---|---|---|
| 30 items | 833 | 29.6 ms | 47.9 ms | 58.7 ms | 67.1 ms | 67.2 % |
| 500 items | 14 149 | 44.1 ms | 49.3 ms | 62.6 ms | 142.4 ms | 85.6 % |

The budget is 16.7 ms a frame; the median frame takes 44 ms, roughly 23 fps. A debuggable build renders slower than a release build, so these are an upper bound, but the gap is too large for that to explain it, and frame time grows with deck size (p50 30 ms → 44 ms), which points at per-frame work that scales with the deck. Not yet investigated; candidates are the history strip composing every entry, and `DeckViewModel.refresh` re-reading sources and doing linear lookups on every decision. The emulator is not a usable perf target (p50 101 ms on 30 items).

Also fixed: a library test APK targets `minSdk`, so Android 16 put a "built for an older version" dialog over the test activity and swallowed the injected touches; `:feature:deck` now sets `testOptions.targetSdk = 36`.

### Run on the branch head

`testDebugUnitTest` **408 tests, 0 failures**. On the API 36 emulator: `:core:storage` 10/10, `:feature:deck` 5 pass + 1 skipped (the opt-in frame-rate run). The other modules' device suites were last run at `9451274` and their code is unchanged.

### Needed for acceptance now

1. SAF restore, so AC-3 holds for what the deck actually quarantines.
2. The deck's frame time: find the per-frame cost, fix it, and re-measure on a release-like build.

## Update — M1-T16 fix wave (2026-10-11, branch `m1-t16-rereview-fixes`)

**Status is still NOT ACCEPTED**, for a narrower reason: every finding below has a fix with tests, but the fixes have not been reviewed, nothing has yet driven the assembled app through a real Apply on a device, and the frame-rate criterion is unmeasured.

The product owner accepted three recommendations: deck decisions are exempt from protected folders (SPEC §6.3 amended, AC-7 reworded); whole-library deletion is review-only until a consent step exists; this record is committed.

| Finding | Disposition | Commit |
|---|---|---|
| C1 Apply reported unmoved items as deleted | Fixed. `SessionMutations.quarantine` returns a per-item `QuarantineOutcome`; only moved items leave the staging strip, the rest are shown with a plain reason. | `24d3733` |
| I1 every MediaStore item was a protected root | Fixed by the §6.3 amendment: the deck installs `IndividuallyReviewed` around its quarantine, move and move-undo; bulk calls are refused as before. | `7c02509` |
| I2 SAF items never matched a protected root | Fixed: matched on the decoded document path. Only path-shaped document ids (the external-storage provider) can be matched; other providers' opaque ids still cannot. | `7c02509` |
| I3 no way to delete media the app does not own | **Scope reduction, not a fix.** Whole-library items are held back before anything is copied and reported as review-only. The app binds this off for every tier, since All Files Access is never used for media. A `createDeleteRequest` consent step is its own later task. | `9451274` |
| I4 summary unreachable | Fixed: "Finish session" on the deck. | `f494d33` |
| I5 summary bytes and restore were stubs | Fixed: the session keeps its quarantine entries, reports their bytes as reclaimable and restores them. In memory only — a session resumed after a process death shows nothing restorable and disables the button. | `7738f6d` |
| I6 resume never invoked | Fixed: the latest ACTIVE session with work left is resumed; otherwise it is marked ABANDONED. | `93d4d41` |
| I7 TAG slots did nothing | Fixed: new `TagDao` (no schema change), the deck advances, undo removes the tag. | `706ea6c` |
| I8 moves skipped the stale check; failed undo reported as undone | Fixed: moves are stale-checked; a failed or impossible undo throws `UndoFailedException` and leaves state unchanged. Also found and fixed: an applied delete could be un-staged from the history strip or by discarding staging while its file sat in quarantine. | `706ea6c` |
| I9 unhandled errors | Fixed: every view-model action reports failure in `DeckUiState.error`. | `f494d33` |

Minors fixed along the way: the move-unavailable banner can be dismissed; the dock closes after a selection. The other minors are open.

Run on the branch head (`9451274`): `testDebugUnitTest` **407 tests, 0 failures** (`:feature:deck` 152, `:core:storage` 109, `:feature:viewer` 51, `:app` 40, `:core:designsystem` 16, `:feature:settings` 15, `:core:database` 13, `:core:common` 11); `connectedDebugAndroidTest` on the API 36 emulator **26 tests, 0 failures, 2 skipped**; `:app:assembleDebug` builds.

Still needed for acceptance: a review of this fix wave; one instrumented end-to-end scenario through the assembled UI on files the test does not own; the 500-item frame-rate measurement. The section "What acceptance needs" below is superseded by this paragraph.

The rest of this record is the re-review as written against `d73c046`, kept as the evidence the fixes answer.

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
- **CI has never run on GitHub (no remote yet).** The `packages:` line is already fixed to a single line, and `./gradlew :app:assembleDebug testDebugUnitTest lintDebug` passes locally (2026-10-11).
- **V6–V9 remain deferred** to M2/M3/M4.

## What acceptance needs

1. Fix C1 and add a partial-result Apply test.
2. An owner decision on I1/I2: how protected roots apply to a one-item-at-a-time deck, and make both backends enforce the same rule.
3. Decide Tier 1 deletion (I3): a `createDeleteRequest`/`createTrashRequest` consent step, or state plainly that whole-library decks are review-only until Tier 2.
4. Fix I4–I9, or cut each surface from M1 explicitly.
5. One instrumented end-to-end scenario through the assembled UI on files the test does not own.
6. Measure the 500-item frame-rate criterion.
