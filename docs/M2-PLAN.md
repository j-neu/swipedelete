# SwipeDelete — M2 (Dashboard & honesty) Implementation Plan

**Goal:** the user can see where their storage went, every number expands into the exact files behind it (AC-13), freed bytes are measured and quarantine bytes are only ever "reclaimable" (AC-9), and the journal can always be exported in full (AC-18).

**Exit criteria (`docs/SPEC.md` §12):** AC-9, AC-13 and AC-18 pass.

**Spec:** `docs/SPEC.md` §6.5 (every number drills down), §6.6 (confirmation ladder), §7.3–7.5 (quarantine card, health checks, what the dashboard must never do). Plan of record: `docs/tasks.md` M2-T01…T09. This file re-expands those tasks against the code as it stands after M1 (`docs/M1-ACCEPTANCE.md`).

---

## What already exists (verified in the tree, 2026-10-11)

| Piece | Where | Consequence for M2 |
|---|---|---|
| `Capacity(total, used, free, reclaimable)` and `StorageGateway.capacity()` | `core/model/Capacity.kt`, `StorageGateway.kt:33` | M2-T01 extends rather than creates; the measured-freed delta is not yet defined as an OS usable-bytes delta. |
| `formatFreed` / `formatReclaimable` / `formatBytes` | `core/common/Bytes.kt` | The two AC-9 phrases already have a single source of truth. M2-T09 guards it. |
| `QuarantineStore.scan()` / `contents()` / `empty(batch, allowPermanent)` / `restore(entry)` | `core/storage/quarantine/QuarantineStore.kt` | Quarantine card and history screen read these. `empty` is the only permanent-delete path. |
| `JournalDao` has `page(limit, offset)` and `byBatch`; **no delete, no "all rows" query, no archived filter** | `core/database/dao/JournalDao.kt` | Export (T07) needs a complete, streaming read; pagination by `page()` is enough if it is driven to exhaustion. |
| `StorageSnapshotEntity` + `SnapshotDao` (`insert`, `recent`, `forVolume`) | `core/database` | Trends (T03) has its table; it needs a writer and a reader, no schema change expected. |
| `:feature:dashboard` is an **empty module** (only `build.gradle.kts`, no sources, no dependencies); `AppNavHost` shows `PlaceholderScreen` for the dashboard route | `feature/dashboard`, `app/nav/AppNavHost.kt:71` | M2 creates the module's first code and wires the route. |
| Session summary already renders reclaimable/freed text | `feature/deck/.../SessionController.kt:134-137` | Reuse the same formatters; do not add a second wording path. |
| `StorageGraphHandle` exposes only `gateway`, `reconciler`, `scanPort` | `app/di/StorageModule.kt:158` | Read-only quarantine and journal access for the dashboard must be added the same careful way (read-only, no mutating bypass). |

## Global constraints (restated)

- **AC-9:** bytes are measured. Quarantined bytes are *reclaimable*, never *freed*. Only `formatFreed`/`formatReclaimable` may produce those phrases.
- **AC-13 / §6.5:** *a number the user cannot expand into a file list is not allowed in the UI.* Every figure is built from a list; the figure is `list.sumOf(size)`, never a separate query.
- **§7.5:** no alarm-red gauge, no "boost score", no comparison to other users, no nagging, no health score that rewards deleting.
- **AC-14:** no automatic or scheduled destructive action. Trends and snapshots are read/record only.
- **AC-6 (no network):** snapshots are on-device, opt-in; `TrendTest` asserts no network call.
- **AC-19:** every panel works at Tier 0 and names what it excludes, with an in-context grant.
- The frozen `StorageGateway` contract is not changed. Destructive verbs still pass `RecoveryCoordinator.awaitReady()`.

## Pre-flight decisions

**E1 — One list, one number.** Every dashboard metric is modelled as `Metric(label, items: List<MediaItem> / FileRef)`; its displayed size and count are *derived* from `items`. This makes AC-13 true by construction and lets one parameterised test cover all metrics.

**E2 — Read-only dashboard access.** The dashboard gets a `DashboardReadPort` (capacity, quarantine contents, journal pages, snapshot read/write) built in `:app` and injected. It has no mutating method except through the existing gated paths (Empty/Restore go through the decorated gateway / store as today).

**E3 — "Other" is a residual.** Per M2-T02, "Other" (total − categorised − system) is labelled a residual, cannot be bulk-acted, and its drill-down says so. It still expands into whatever list can be honestly produced; if none can, the number is not shown.

**E4 — Measured freed.** Define freed = increase in OS-reported usable bytes (`StatFs.availableBytes` / `StorageStatsManager`) across the operation, reported alongside what was expected. It is never a sum of file sizes. Quarantine copies live in the app's external files dir, so a quarantine alone frees ~0 and the UI says so.

**E5 — Schema.** No Room schema change is planned. If T03/T07 need one, bump the version with a migration test in the same task (the repo already has a schema check).

---

## Task order and dependencies

```
T01 Capacity ─┬─> T02 Overview ──> T08 Health checks
              ├─> T05 Quarantine card ──> T06 History / restore
              └─> T03 Trends
T07 Journal export  (independent)
T04 Triage progress (independent; reads sessions)
T09 Honesty audit   (last; sweeps everything above)
```

Recommended execution: **T01 → T05 → T06 → T07 → T02 → T08 → T04 → T03 → T09.** T05/T06 first because they reuse the freshly accepted quarantine/restore code and carry the highest user risk (Empty is the one irreversible action in the app).

---

## M2-T01 — Capacity service and measured-freed delta (AC-9)

**Files:** `core/model/Capacity.kt`; `core/common/` (new `MeasuredFreed.kt`); `core/storage/` gateway `capacity()` implementations; `app/di`.

- [ ] **Test first** `MeasuredFreedTest` (`:core:common`, JVM): given before/after usable bytes, freed = `max(0, after − before)`; a quarantine-only run yields 0 freed and N reclaimable; a negative delta (other app wrote meanwhile) is reported as 0 with the raw delta available, never negative "freed".
- [ ] **Test first** `CapacityTest` (`:core:storage`, Robolectric): `used = total − free`; `reclaimableBytes` equals the sum of quarantine sidecar sizes; volumes enumerated individually.
- [ ] Implement `MeasuredFreed` as a pure function plus an `Android` sampler (StatFs) behind an interface so tests stay JVM.
- [ ] Per-volume capacity (internal + SD) rather than one total; the existing single `Capacity` stays for compatibility.
- [ ] Gate: unit tests green; no new wording path for "freed".

## M2-T05 — Quarantine card and Empty flow (§7.3, §6.6)

**Files:** `feature/dashboard/.../quarantine/` (new), `app/di` read port.

- [ ] **Test first** `QuarantineCardTest`: item count, bytes held (`formatReclaimable`), oldest-expiry countdown (`humanDuration`), and the statement "these bytes are not yet reclaimed" are present for a non-empty quarantine; an empty quarantine shows "Nothing in quarantine", not zeros dressed as success.
- [ ] **Test first** `EmptyQuarantineConfirmationTest`: Empty is refused until the user types the exact item count; the dialog says "cannot be undone"; `allowPermanent` is `true` only on the second, typed confirmation; cancelling at either step changes nothing (assert files and sidecars intact).
- [ ] Wire: card → Restore all (existing `restoreEverything` path), Review (history, T06), Empty… (typed-count dialog → `QuarantineStore.empty`).
- [ ] Device check (emulator only, own test files): Empty removes bytes and the measured-freed figure rises; phone is not used without asking.
- [ ] Gate: Empty is unreachable from any swipe or one-tap path (extends the AC-14 architecture test).

## M2-T06 — History screen and per-item restore (AC-17)

**Files:** `feature/dashboard/.../history/`.

- [ ] **Test first** `HistoryRestoreTest`: every batch lists date, count, bytes; per-item Restore returns the file byte-identical to its original place (reuse the SAF/raw fakes from M1).
- [ ] **Test first** `RestoreConflictTest`: a restore onto an occupied original path shows a conflict screen with choices (keep both / skip), and never overwrites (AC-17).
- [ ] Page through `JournalDao.page()`; show archived/expired rows greyed, with honest "no longer restorable" where the quarantine copy is gone.
- [ ] MediaStore rows remain review-only (known M1 limit); the screen says so rather than offering a Restore that cannot work.

## M2-T07 — Journal export (AC-18)

**Files:** `core/storage` or `feature/dashboard/.../export/`; Room read query if a stream is needed.

- [ ] **Test first** `JournalExportParityTest`: seed N journal rows (all `JournalState`s, all `OpKind`s, including expired/archived and rows with null `toUri`/`hash`/`error`); export JSON and CSV; parse both; assert the parsed rows equal the DB rows field-for-field and count-for-count.
- [ ] **Test first** CSV edge cases: commas, quotes, newlines and non-ASCII in `fromUri`/`error` round-trip.
- [ ] Export streams page by page (no full-table load) to a user-chosen document via `ACTION_CREATE_DOCUMENT`; failure leaves no partial file reported as success.
- [ ] Gate: export never filters; "complete" is asserted by count against `SELECT COUNT(*)`.

## M2-T02 — Dashboard storage overview (AC-13)

**Files:** `feature/dashboard/.../overview/`.

- [ ] Model `Metric(label, items)` (E1). Categories: Images, Video, Audio, Documents, APKs, App data, System, Other (residual, E3); top-20 largest folders and files.
- [ ] **Test first** `DashboardDrilldownTest` (one parameterised case per metric): displayed bytes == `items.sumOf(size)` and displayed count == `items.size`; tapping opens a list whose rows are exactly `items`.
- [ ] Tier handling (AC-19): at Tier 0 the overview covers MediaStore media and granted trees, and a banner names what is excluded with an in-context grant; "App data"/"System" appear only where measurable, otherwise are omitted, not guessed.
- [ ] Copy rules: neutral colour, no gauge in alarm red.

## M2-T08 — Health checks (§7.4)

- [ ] **Test first** `HealthCheckZeroStateTest`: with an empty library every row reads "0 items" and is still tappable; no row invents a finding.
- [ ] Rows for modules that do not exist until M3 (duplicates, similar media, empty folders, APKs, partial downloads) render as "Not available yet", clearly distinguished from "0 items". Only large-files-style checks computable from M1 data show a count now.

## M2-T04 — Triage progress

- [ ] **Test first** `TriageProgressTest`: reviewed/total and per-slot counts derive from stored sessions and keep marks; "X GB awaiting review" is the sum of un-reviewed items' sizes (E1: tappable into that deck).
- [ ] Streak shown plainly; no gamified reward language (§7.5).
- [ ] Known limit to address or document: session-restore entries are in memory only (M1 carry-forward) — progress must not claim history it cannot reproduce.

## M2-T03 — Snapshot trends

- [ ] **Test first** `TrendTest`: opt-in off by default (no row written until enabled); enabled records a `StorageSnapshot` per volume; "what grew this week" is a difference of two snapshots, per category; **asserts no network class is touched** (extends the zero-network check).
- [ ] Recording is user-initiated or on app open only — no background worker (AC-14).
- [ ] Empty state: with fewer than two snapshots, say so; show no trend.

## M2-T09 — Honesty copy audit (AC-9, §7.5)

- [ ] **Test first** `HonestyCopyTest` (JVM string sweep over all `strings.xml` and Compose literals in `:app` + `:feature:*`): the word "freed" appears only via `formatFreed`; "reclaimable" never appears next to a measured-freed figure; banned phrases (`boost`, `score`, `faster phone`, `vs. other`, `cleaned!`) fail the build; no `Color.Red`-family gauge in dashboard sources.
- [ ] Wire into `testDebugUnitTest` so it runs in CI on every push.

---

## Acceptance for M2

Same honesty rule as `docs/M0-ACCEPTANCE.md`: an item is verified only where a command was run and its output seen.

- [ ] AC-9: `MeasuredFreedTest`, `HonestyCopyTest`, and a device run on the emulator showing Empty raises measured free space while Delete-to-quarantine does not.
- [ ] AC-13: `DashboardDrilldownTest` green for every metric.
- [ ] AC-18: `JournalExportParityTest` green; a manual export opened and counted on the emulator.
- [ ] `./gradlew :app:assembleDebug testDebugUnitTest lintDebug` green locally **and** on GitHub Actions (first real CI run has not been confirmed yet).
- [ ] Phone runs only the dedicated acceptance tests, pinned with `ANDROID_SERIAL`, only after asking.

## Risks

1. **"Other" and "App data"/"System" need `StorageStatsManager`/usage access** that Tier 0 may not grant; the plan degrades by omitting those rows (AC-19) rather than estimating.
2. **Measured freed on a busy device** can be noisy (other apps writing). Report the delta with its caveat; never round up.
3. **Journal export size** on a heavy user can be large; streaming and a progress/cancel affordance are required, not optional.
4. **`:feature:dashboard` has no dependencies yet**; the first task there adds Compose/Hilt/test setup, and should mirror `:feature:deck`'s build file rather than invent one.
5. **Session restore is in-memory** (M1 limit) and bounds what T04 can honestly show.
