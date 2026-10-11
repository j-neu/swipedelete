# SwipeDelete — Product & Technical Specification

**Working title:** SwipeDelete (naming open — see §15)
**Status:** Draft v0.2 — for review
**Target platform:** Android (Kotlin + Jetpack Compose), `minSdk 26`, `targetSdk 36` (required for new submissions since 31 Aug 2026), `compileSdk 37` (Android 17) — **[VERIFY against current Play and Android release requirements at implementation time]**
**Licence intent:** Free, open source, no ads, no telemetry

---

## 0. How to read this

This document is the single source of truth for what we build and, more importantly, **what we refuse to build**. Sections 1, 4, 6 and 12 are the load-bearing ones. §6 (Safety Architecture) and §12 (Acceptance Criteria) are non-negotiable: if a feature cannot satisfy them, the feature is cut, not the constraint.

Everything marked **[OPEN]** needs a decision from the product owner. Everything marked **[VERIFY]** is a claim that must be checked against current docs before implementation.

---

## 1. Vision and anti-goals

### 1.1 One-sentence vision

A fast, honest, gesture-driven triage tool for the pile of junk media everyone accumulates — memes, screenshots, downloaded GIFs, saved videos, stray APKs — where the user never has to fear that the app will destroy something irreplaceable.

### 1.2 What we are

- A **triage tool**, not a "cleaner". The user makes every destructive decision, one item at a time, with full-fidelity playback in front of them.
- A **media organiser** with five user-defined destination folders, reachable by dragging a card down.
- A **truthful storage dashboard**: real measurements, real file lists, real before/after.
- A **safety-first deletion system**: nothing is destroyed, everything is quarantined and restorable for 30 days.

### 1.3 What we are explicitly not

| Anti-goal | Why |
|---|---|
| "One-tap boost / free up 40 GB!" | The single largest source of catastrophic data loss in this category. |
| Red gauges, alarmist numbers, "your phone is 90% junk" | Deceptive-behaviour policy risk, and it is how users get tricked into destructive taps. |
| Any category label that maps to an opaque, app-chosen set of files | "Visible Cache" was 40 GB because the label resolved to a heuristic, not a list. |
| Clearing other apps' internal caches | Not possible on modern Android. Claiming it is a lie. |
| Killing background apps / "RAM boosting" | Makes battery life worse. Actively harmful. |
| Fake progress bars, fake scan animations, artificial delays | Straightforwardly deceptive. |
| Advertising SDKs and analytics | The ad SDK is the #1 privacy complaint in this category. |
| Any network call whatsoever, in any build | Trust is the product. Remove the `INTERNET` permission entirely. |
| Auto-selecting files for deletion | Any bulk default other than "nothing selected" is a bug. |

### 1.4 The one-line test for every feature

> *Could this feature, through a plausible user misunderstanding, destroy a file the user wanted to keep?*

If yes, it needs a structural answer, not a warning dialog.

---

## 2. The problem, evidenced

### 2.1 The canonical failure

A Play Store review quoted verbatim by the product owner:

> "CAUTION: REQUEST USERS NOT TO USE THIS APP. I recently used CCleaner to clear cache. I know for sure I didn't select 'Data' to be deleted but noticed something strange that the 'Visible Cache' was around 40 GB. I know few games keeps >1GB of cache so I assumed it was that & cleared it only to find out later that it deleted my entire Gallery, all the photos, videos & everything else that was in the gallery because all those data were copied from my old phone which had 9+ year old data as well :("

**Attribution correction (from `docs/RESEARCH-PITFALLS.md`):** the specific "over 40 GB, entire gallery" case that the research pass could verify with a primary source is **AVG Cleaner**, not CCleaner — AVG Community forum thread [*Recover deleted files with avg cleaner*](https://community.avg.com/t/recover-deleted-files-with-avg-cleaner/252639), user Faviola_Gutz, 5 Oct 2024: *"it deleted over 40GB of pictures files and videos... there really is no way to recover them?"*, with AVG support replying that the product has no restore option. CCleaner has the **identical failure mode at smaller scale** — CCleaner forum topic 153628, Jun 2025: *"all I've done was quick clean where I have ticked visible caches and thumbnails. Nothing more... How is it possible that all the photos and videos are gone?"* (~5 GB), and topic 150351, Nov 2024.

This makes the design case **stronger, not weaker**: the failure is a category-wide pattern produced by the same design, not one company's bug. The quoted review is representative of the whole product class.

Decomposed into root causes:

1. **The label was a heuristic, not a list.** "Visible Cache" was computed by scanning storage and guessing. Nothing in the UI let the user see the 40 GB of actual files.
2. **User media was reachable by a non-media category.** The classifier had no hard structural exclusion preventing `/DCIM` content from being classified as "cache".
3. **The user's mental model was "cache", the app's was "everything my heuristic matched".** No provenance was shown ("this file is here because of rule X").
4. **Destruction was irreversible and unwarned.** Years of a family's photos, from a phone migration, gone in one tap — and the vendor shipped no restore path at all.
5. **The number itself was the only evidence offered.** 40 GB is *enormous* for cache and should have been a blocking internal sanity check, not a green light.
6. **The destructive default was pre-selected.** AVG's own FAQ defines "Unneeded files" as *"visible and hidden cache, browser data, residual files, installed APKs, thumbnails, ad caches, and empty folders"*, ticked by default, with *"It is not possible to restore items that are deleted during a Quick Clean"*, plus a "Select all" affordance and large folders rendered in alarm red.

### 2.2 The category-wide pattern

`docs/RESEARCH-PITFALLS.md` mines Play Store 1–3★ reviews, vendor forums, Reddit and press coverage for cleaner/optimizer/booster apps, and produces a ranked root-cause list plus a "never do this / always do this" table feeding §6 and §12. This spec is written so that each anticipated root cause already has a structural mitigation; the research pass exists to confirm the list and add anything missed.

---
## 3. Target users and jobs to be done

### 3.1 Primary persona — "the hoarder with no time"

Someone with 5,000–60,000 media items, tens of GB of memes and screenshots, no desire to scroll a grid and long-press each one. They want to burn through items at speed with a thumb, and they want to *see* the thing before judging it (a thumbnail is not enough for a GIF or a video).

**Job:** "Let me get through 500 saved memes in ten minutes, with my thumb, without accidentally losing anything."

### 3.2 Secondary persona — "the cautious organiser"

Same, but they want files moved into real folders: `Memes`, `Reaction GIFs`, `Wallpapers`, `Recipes`, `Tax`. They already have that structure and want the app to feed it.

**Job:** "Let me sort this pile into my five existing folders by dragging, without breaking my gallery or my WhatsApp history."

### 3.3 Tertiary persona — "the space reclaimer"

Doesn't care about taste-making; wants the 40 GB back and wants to know exactly where it went.

**Job:** "Show me the truth about my storage and let me act on it safely."

---

## 4. Permissions, distribution and what is actually possible

### 4.1 How cleaner apps get "the whole scan"

| Mechanism | Availability | What it gives |
|---|---|---|
| `READ/WRITE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage` | Android ≤ 10 only | Full raw-path access. Dead on 11+. |
| `MANAGE_EXTERNAL_STORAGE` ("All files access") | Android 11+ | Raw-path read/write across shared storage. A special-access toggle, not a runtime prompt. Restricted by Play policy. |
| `READ_MEDIA_IMAGES / VIDEO / AUDIO` | Android 13+ | MediaStore-visible media only. Restricted by Play policy. |
| `MediaStore` + `createDeleteRequest` / `createTrashRequest` | Android 11/12+ | Scoped, user-confirmed, OS-mediated mutation of media. |
| **SAF `ACTION_OPEN_DOCUMENT_TREE`** | Android 5+ | Read/write over exactly the trees the user picks. Play-safe, revocable, bounded. |
| `StorageStatsManager` | Android 8+ | Per-app cache/data **measurement**. Not deletion. |

**Hard limits even with All Files Access:** no other app's `/data/data`, no other app's `/Android/data` or `/Android/obb` (Android 11+), no system partitions, no other app's internal cache. **[VERIFY against current docs.]**

**Consequence:** any app claiming to "clear all app caches" on modern Android is measuring and deep-linking, or lying. We measure and deep-link, and we say so in the UI.

### 4.2 Decision: position as a **file manager** — and it is a legitimate route, not a loophole

The product owner asked whether declaring the app a file manager gets "the best of both worlds". The policy text says yes, **with three conditions that our architecture already satisfies**. Verbatim from [Use of All files access (MANAGE_EXTERNAL_STORAGE) permission](https://support.google.com/googleplay/android-developer/answer/10467955):

> "For apps requesting access to the All files access permission, intended and permitted use includes **file managers**, backup and restore apps, anti-virus apps, and document management apps."

> "**File management** — App's core purpose involves the access, editing, and management (**including maintenance**) of files and folders outside of its app-specific storage space" → eligible for `MANAGE_EXTERNAL_STORAGE`.

Three conditions, and our answer to each:

| Condition (policy text) | Our position |
|---|---|
| **1. Core functionality.** "the app's usage of the permission must fall within permitted uses and must be directly tied to the core functionality of the app... must all be **prominently documented and promoted in the app's description**." | The app genuinely is a file manager: it enumerates, previews, moves, copies, dedupes, compresses, and deletes files and folders across storage. The Play listing must lead with **file manager / media organiser**, and cleaning should be described as file **maintenance** — which the policy explicitly folds into file management. A listing that leads with "cleaner" would be an undeclared/inaccurate use. |
| **2. Invalid use — "Media Files access".** Media Files access is listed as an **invalid** use of All Files Access; the documented alternative is the MediaStore API. | We never use All Files Access to read or edit media. **Media always goes through MediaStore** (§4.6). All Files Access is used only for *non-media* file management that MediaStore cannot do and SAF cannot do globally: APKs, empty folders, partial downloads, zero-byte files, orphaned app dirs, residual files, OS thumbnail cache, and cross-tree moves. This split is the single strongest element of the declaration. |
| **3. Invalid use — manual file selection.** "Any File selection activity where the user manually selects individual files" is an invalid use. | The deck is not a picker for import/transfer/processing; it is a **management** surface operating on files the app has already indexed, and it is fully functional without the permission. We frame it as management, and we never gate browsing or opening a file behind All Files Access. |

**Exception route (if a reviewer disputes the file-manager classification):** the policy permits a temporary exception where the use "enables the app's core functionality", there is no alternative, **or** "the use of privacy-friendly alternatives... has a **substantially detrimental impact on the critical features of the app that are tied to the core functionality**", and privacy impact is mitigated. Our argument is concrete and strong: media deletion via `MediaStore.createDeleteRequest` requires a **system confirmation dialog per batch**. Doing that per item destroys swipe-to-triage entirely; batching thousands of items into one dialog is exactly the "select all" pattern that caused the AVG/CCleaner disasters. The user-confirmed OS dialog is the *wrong* safety primitive for one-at-a-time triage. That is a documented, technical, detrimental impact on the core feature — and it is the honest reason we need scoped raw access plus our own quarantine.

> **Warning in the same policy:** "Deceptive and undeclared uses of these permissions may result in a suspension of your app and/or termination of your developer account." The positioning in condition 1 is therefore not marketing copy — it is a compliance requirement, and it locks in the §1.3 anti-goals.

### 4.3 The second declaration: Photo and Video permissions

Independent of All Files Access, requesting `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` on API 33+ requires its own declaration under the [Photo and Video Permissions policy](https://support.google.com/googleplay/android-developer/answer/14115180). Full compliance has been mandatory since 28 May 2025; non-compliant apps are subject to removal.

The policy's own qualifying example fits us precisely:

> "Apps whose core function includes **managing and maintaining all of users' photos/videos** (such as 'gallery apps') would need broad access on a user's device."

Two mandatory obligations that shape the product:

1. **Declaration + justification.** A custom picker does *not* auto-qualify; we must demonstrate why the Android Photo Picker is insufficient. Justification: the Photo Picker returns user-chosen items for a single transaction; it cannot enumerate, present one item at a time in a deck, surface *unreviewed* or *duplicate* or *similar* items, or persist a review state across sessions. Our core function is exactly the "managing and maintaining all of the user's photos/videos" case the policy names.
2. **Graceful degradation is required, not optional.** "you must make a reasonable effort to accommodate users who do not grant broad access to media files... a more transactional method (for example, via a system picker)... or gracefully facilitating a modified app experience." The tier model in §4.4 is that obligation expressed as architecture, and §12 AC-19 makes it testable.

### 4.4 Capability tiers, not build flavours

There is **one APK and one Play listing**. Capability unlocks as the user grants more, and every feature degrades rather than breaks:

| Tier | Permissions held | What works | What is narrower |
|---|---|---|---|
| **Tier 0 — default, zero broad permissions** | SAF tree grants only (+ Photo Picker) | Full triage inside granted folders; the five slots; move/tag; viewer; quarantine; journal; duplicates within granted trees; compression | No global media catalogue; no non-media global scan |
| **Tier 1 — + Photo/Video** | `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | Whole-library media decks: screenshots, largest videos, unreviewed, duplicates and similar across all media | Still no non-media global reach |
| **Tier 2 — + All files access** | `MANAGE_EXTERNAL_STORAGE` (user-toggled in Settings) | Non-media file management: APKs anywhere, empty folders, partial downloads, orphaned app dirs, residual files, OS thumbnail cache, global large-file deck | Still cannot touch other apps' private dirs or internal caches |

Rules:

- Tier 0 is the **default state on first run**. The app must be genuinely useful at Tier 0, because that is what the policy requires and because asking for everything up front is the pattern that makes users distrust this category.
- Each tier is requested **in context**, when the user taps the feature that needs it, with a plain-language explanation of exactly what it unlocks — never a wall of prompts at onboarding.
- **No feature is ever "broken" by a missing permission** — it is offered as a narrower version, or with an explicit "grant X to include the rest" affordance. (§12 AC-19)
- **Contingency build flag:** `-PnoAllFilesAccess` produces a Play-submittable variant with the permission and its dependent features stripped, in case the declaration is rejected. This is a build flag, not a second product.

### 4.5 The five folders *are* the permission model

The five destination slots in the footer are exactly five persisted SAF tree grants:

- Onboarding asks the user to pick their five destination folders. This doubles as the app's first permission request. There is no separate scary permission screen — the UI *is* the grant flow, and it works at Tier 0.
- Files can be moved into a destination because we hold write access to it. No `MANAGE_EXTERNAL_STORAGE` required.
- Source folders are additional SAF grants, read-only where possible, countable and revocable.
- **[OPEN]** Cap on source grants. Recommendation: 20 total persisted grants, with a UI showing each grant, its size, item count, and a one-tap revoke. Evaluate `takePersistableUriPermission` limits on low-end devices. **[VERIFY]**

### 4.6 MediaStore-first for media (also a policy requirement)

For media we do not walk the filesystem. `MediaStore` holds an indexed catalogue: `_ID`, `RELATIVE_PATH`, `SIZE`, `DATE_ADDED`, `DATE_MODIFIED`, `MIME_TYPE`, `WIDTH`, `HEIGHT`, `DURATION`, `BUCKET_DISPLAY_NAME`, `IS_TRASHED`, `IS_PENDING`, `OWNER_PACKAGE_NAME`. Queries are `ContentResolver` cursors with paging, and they are what makes a 60,000-item library usable.

This is simultaneously the performance answer and the compliance answer: it is the documented privacy-friendly alternative, and keeping media on MediaStore is what makes the All Files Access declaration defensible (§4.2, condition 2).

### 4.7 Deletion paths (in preference order)

1. **Quarantine move** (§6.1) — the default for every destructive action.
2. **`MediaStore.createTrashRequest`** — only when quarantine is impossible (insufficient free space for the copy, or a SAF item we can't move) *and* the item is MediaStore-indexed on API ≥ 30. Requires explicit consent to the weaker guarantee, in plain language.
3. **`MediaStore.createDeleteRequest`** — permanent, system-dialog-confirmed. **Maximum 2000 URIs per request** (documented in the `MediaStore` reference; exceeding it throws `IllegalArgumentException`). Used only for emptying quarantine or when the user explicitly chooses permanent deletion.
4. **SAF `DocumentsContract.deleteDocument`** — for non-media files inside granted trees.

Every path except #3 is reversible. #3 always requires a second, deliberate confirmation (§6.6).

---
## 5. Core experience — the triage deck

### 5.1 Sources and deck construction

A **deck** is an ordered list of items, snapshotted at session start. Deck types:

| Deck | Source | Purpose |
|---|---|---|
| **Folder deck** | One granted source tree, recursively | The default. "Go through my Memes folder." |
| **Smart deck** | A stored query | "Screenshots", "Videos > 100 MB", "Downloads older than 90 days", "Not reviewed yet", "Largest first" |
| **Module deck** | Output of a cleaner module | "Review these 312 duplicate candidates in the deck instead of in a list." |
| **Resume deck** | Persisted session position | Crash/exit mid-triage restores exactly where you were. |

Deck rules:

- **Frozen snapshot semantics.** The deck is materialised with IDs + metadata. If an item changes on disk before it is presented, we detect it on presentation and abort that item with a visible message rather than acting on stale data. (§12 AC-8)
- **No repeats within a session.** Skipped items re-queue at the end, maximum 3 times, then land in a "Decide later" pile.
- **Provenance line on every card.** Each card states *why* it is here: `Screenshots · /Pictures/Screenshots · 4.2 MB · 14 Mar 2021`. In a smart deck the rule is stated in plain language and is tappable to see the rule definition and the full matching set.
- **Ordering defaults:** oldest first for folder decks (you already decided you didn't need it), largest first for space decks. **[OPEN: configurable default.]**

### 5.2 Gestures

| Gesture | Action | Reversible? |
|---|---|---|
| **Swipe left** | Delete → **stage for quarantine** | Yes, 30 days + instant undo |
| **Swipe right** | Keep in place — mark reviewed with a timestamp; file is not moved or modified | Yes (nothing was modified) |
| **Swipe up** | Defer — re-queue at end of deck, max 3 | N/A |
| **Swipe down / drag down** | Reveal the five-slot dock; continue the drag onto a slot to move the file there | Yes |
| **Tap** | Open the full-fidelity viewer (§5.4) | N/A |
| **Long press** | Action sheet: details, share, open with, rename, exclude from decks, "never show me this again" | N/A |
| **Two-finger tap** | Undo last action | N/A |

Design details that matter:

- **Direction is committed by threshold + velocity**, with the card tinted and a label ("Delete" / "Keep") revealed progressively — never a surprise. The destructive direction uses a distinct colour *and* an icon *and* text; colour is never the only signal (§11.4).
- **Delete is never one-frame-commit.** The card flies out, the item enters a visible **staging strip** ("3 to delete · Undo"), and the batch executes only on "Apply", on session end, or after a configurable idle window. **[OPEN]** Recommendation: stage-by-default with explicit Apply — it turns every mis-swipe into a visible, recoverable state instead of a race against a toast timer.
- **Every gesture has a button equivalent.** A bottom action bar (Delete / Keep / Move / Skip) mirrors all swipe gestures, and the dock is reachable by tapping "Move" without dragging. Required for TalkBack, switch access and motor impairment — not optional.
- **Haptics:** light tick on threshold cross, distinct pattern on drop-into-slot, error pattern on failure.
- **Drag-to-folder mechanics:** drag begins after a ~12 dp threshold; the dock animates up; the nearest slot highlights with magnetic snapping within ~48 dp; dropping outside the dock cancels and returns the card; a slot greys out if its destination is unavailable (e.g. revoked grant) with the reason on tap.

### 5.3 The five folder slots

Each slot has: **destination** (SAF tree or MediaStore relative path), **display name**, **icon/emoji + colour**, and an **action mode**:

- **Move** — physically moves the file into the destination (default when a destination is set).
- **Tag only** — records the tag in the app database and does *not* touch the file. **[Recommended default for cautious users, and for anyone whose gallery or chat app would break on a move.]**

Other rules:

- Long-press a slot to reconfigure. Slots show a live count and total size of items assigned this session.
- **Filename collisions never overwrite.** Append ` (1)`, ` (2)`, … before the extension. **[OPEN: alternative is date-prefixed rename. Recommendation: numeric suffix — least surprising.]**
- Cross-volume moves are copy → verify (hash + size) → delete original, with progress and full cancellation; a cancelled move leaves the original untouched.
- Moving into a destination that is itself a source tree of the active deck triggers a deck refresh notice rather than silently reordering.

### 5.4 The viewer — "play anything"

The user must be able to *properly experience* the file before deciding.

| Content | Behaviour |
|---|---|
| **Images** | Full-resolution decode, pinch-zoom, double-tap zoom, pan, EXIF orientation respected |
| **Animated GIF / animated WebP** | Plays and loops, with play/pause |
| **Video** | Media3/ExoPlayer: play/pause, scrub with thumbnail preview, ±10 s, speed 0.25×–3×, frame step, loop toggle, audio track selection; **audio always available in the viewer** |
| **Audio** | Transport controls, duration, metadata |
| **APK** | Icon, package name, version, requested permissions, signature, target SDK, "installed? which version?" comparison |
| **Documents/other** | Metadata card + "Open with" hand-off |
| **Unsupported/undecodable** | Honest error naming the codec/MIME, plus "Open with" — never a blank frame |

Viewer specifics:

- Pager between deck items: horizontal = previous/next item, vertical down = dismiss. To avoid conflicting with the deck's drag-to-folder, **inside the viewer the dock opens via a handle/button**, not via drag-down. **[OPEN]** Alternative: keep drag-down-to-dock in the viewer too. Recommendation: dismiss-on-drag-down (conventional), dock via button.
- **Deck cards autoplay video muted and loop GIFs** (configurable), so the user can judge without opening. Autoplay is cancelled on fling, and never more than one active decoder plus the preloaded next item.
- **Preloading**: next/previous item prepared during idle; explicit memory ceiling; decoders released on `onStop`.
- **Metadata panel**: path, MediaStore ID, size, dimensions, duration, codec, bitrate, mtime/ctime, SHA-256 (computed lazily), EXIF including GPS with a map link and a "copy without location" option.
- **Provenance**: the same line as the deck card, so the viewer also answers "why am I looking at this?"

### 5.5 Session model, undo and the journal

- A **session** is a triage run: start → N decisions → Apply → summary.
- **Undo stack for the whole session**, not just the last action: a scrollable history strip ("kept 12 · moved 4 to Memes · staged 3") where any entry can be reverted individually.
- **Session summary** on exit: reviewed, kept, moved (per slot), staged for deletion, deferred — **and measured bytes freed** (not estimated), with quarantine bytes reported separately (§6.5).
- Exiting with a non-empty staging strip prompts: *Apply*, *Keep staged for later*, or *Discard staging (nothing deleted)*.
- Everything is written to the **Action Journal** (§6.2) before it happens.

---

## 6. Safety architecture — the core of the product

This section exists to make the CCleaner failure mode impossible by construction.

### 6.1 Quarantine, not deletion

**Nothing is ever deleted on first action.** A delete moves the file into app-managed quarantine:

- Location: the app's external files directory, which keeps moves on the same volume (typically an O(1) rename rather than a copy). **[VERIFY cross-mount rename behaviour; fall back to copy+verify+delete.]**
- Structure: `<quarantine>/<batchId>/<original relative path>` — the original tree shape is preserved so restore is exact.
- Sidecar record per file: original URI/path, MediaStore ID (if any), size, mtime, MIME, SHA-256, the rule/category that caused the action, session ID, timestamp, expiry date.
- **MediaStore reconciliation:** after quarantining an indexed file, the index is updated so gallery apps don't show a ghost. **[VERIFY mechanism per API level.]**
- **`.nomedia` is never used to hide quarantine contents from the user.** The quarantine screen is a first-class UI surface with a full preview grid.

**Storage-cost honesty:** quarantining a 4 GB video needs 4 GB free. If free space is insufficient, the UI says so plainly and offers: (a) quarantine the rest later, (b) MediaStore trash instead (weaker guarantee, explained), or (c) permanent deletion with double confirmation. It never silently downgrades.

### 6.2 The Action Journal

- **Write-ahead.** Every mutation writes an intent record (`PENDING`) → performs the operation → writes the outcome (`DONE`/`FAILED`). The journal commits synchronously before the filesystem op.
- **Startup reconciliation.** Every `PENDING` record is inspected on launch: still at source → `ABORTED`; at destination → `DONE`; in neither → surfaced in a **Recovery Report** with a one-tap "Tell me what happened" explanation. No item is ever in an unknown state without the user being told.
- **Immutable.** Rows are never deleted inside the retention window. Emptying quarantine archives rows to a log rather than erasing them.
- **Self-audit.** A periodic integrity check verifies a random sample of quarantine entries are byte-identical (hash match) to their recorded hash and still restorable. Failures are surfaced.
- **Export.** The journal is exportable to JSON/CSV so the user can see exactly what the app ever did. A trust feature that costs almost nothing.

### 6.3 Protected roots — the "never touch" list

Enforced at the storage-gateway layer, not in the UI, so no feature can bypass it accidentally.

Protected by default; acting inside requires an explicit unlock in Settings with typed confirmation:

- `DCIM/`, `DCIM/Camera`, `Pictures/`, `Movies/`, `Music/`, `Documents/`, `Download/`
- Camera-roll heuristics: buckets named `Camera`, `Screenshots`, `Screen recordings`, `WhatsApp Images`, `WhatsApp Video`, `Telegram`, `Signal`, `Snapseed`, `Instagram`, `Snapchat`, `Pinterest`, `Reddit`
- Voice recorders, call recorders, and any tree of media older than 5 years **[OPEN — recommendation: yes, with a distinct warning]**
- The app's own quarantine directory (never a delete target), and any SAF root itself (we never delete a root the user granted)

**Deck decisions are not a bulk flow (amended 2026-10-11).** The list above exists to stop a flow from acting on files the user never looked at. An item the user decided one at a time in the triage deck — card in front of them, staged, confirmed in the Apply preview, quarantined and restorable — may be quarantined or moved from a protected folder, and regardless of its age, without the Settings unlock. Three things stay protected even for a deck decision: the app's own quarantine directory, a granted SAF root itself, and permanent deletion. Every other flow (cleaner modules, any selection by rule) is still refused until the root is unlocked. In code this is the `IndividuallyReviewed` marker, which only the deck's session layer may install; an architecture test pins that.

Additionally:

- **A file's category is never inferred from its parent folder name alone.** (§12 AC-4) Folder names inform *display*, never *classification for deletion*.
- **A file with a media MIME type or media extension can never be classified into a non-media category** such as cache, junk, temp, thumbnails, residual or APK. (§12 AC-10, the "CCleaner regression test".)

### 6.4 The category taxonomy (closed set)

Every category is a **rule with a documented, human-readable definition**, a confidence value, and an enumerable result set. Categories are a closed enum — no free-text "Other" bucket that can be acted on in bulk.

| Category | Definition | Media ever eligible? | Default state |
|---|---|---|---|
| `LARGE_FILE` | Size ≥ user threshold, any type | Yes (it is just a size filter) | Review only |
| `EXACT_DUPLICATE` | Identical size + content hash, ≥ 2 copies, ≥ 1 keeper identified | Yes | Review, nothing preselected |
| `SIMILAR_MEDIA` | Perceptual hash within threshold **and** within a time window; low-confidence groups flagged separately | Yes | Review only |
| `APK_INSTALLER` | `.apk` MIME/extension, older than or equal to the installed version | No | Review only |
| `EMPTY_FOLDER` | Zero entries after a completed scan; not a SAF root; not a known app path; no `.nomedia` marker | N/A | Review only |
| `PARTIAL_DOWNLOAD` | `.part`, `.crdownload`, `.tmp`, `.download`, `.opdownload` inside a download path | No | Review only |
| `ZERO_BYTE` | Size = 0 | No | Review only |
| `CRASH_LOG` | Text/log files matching known crash-report path patterns | No | Review only |
| `TEMP_FILE` | Known temp extensions **outside** protected roots and outside media types | No | Review only |
| `THUMBNAIL_CACHE` | Only the OS thumbnail cache path, only at Tier 2 | **Never** | Off |
| `ORPHANED_APP_DIR` | Package-named dir under `/Android/data` or `/Android/obb` with no installed package (needs All Files Access) | No | Off, review only |
| `APP_CACHE` | Measured via `StorageStatsManager`; **measure + deep-link only** | **Never** | Measure only |
| `OBSOLETE_MEDIA` | Media older than N years, unreviewed — a *deck*, not a delete category | Yes | Deck, never a delete button |

Rules:

1. **No category is pre-selected.** Every module opens with zero items selected.
2. **Every category view shows the exact file list and a preview grid before any action.** The action then operates on that frozen set.
3. **"Review in deck" is the primary action for media-eligible categories**, so the destructive decision is made one item at a time with the file visible and playing.
4. **No category may be acted upon without the user having seen its enumeration**, unless the user has explicitly enabled "skip the preview for this category" — per category, off by default, and never available for media-eligible categories.
5. **Sanity ceilings.** If a category's total exceeds a plausible bound (e.g. junk/cache exceeding ~2 GB, containing any file over 200 MB, or containing any path inside a protected root), the UI refuses to present the one-tap action and forces list review, with an explanation. This is the direct, literal answer to "40 GB of Visible Cache".
6. **"Cache" is not a user-facing word for anything that can contain user data.** That string does not exist in the app's resources for a destructive surface. (§12 AC-11)

### 6.5 Honest numbers

- Space freed is **measured** (delta of usable bytes via `StatFs`/`StorageStatsManager`), never estimated, never rounded up, never a sum of file sizes.
- Quarantined bytes are shown as **"reclaimable"**, never as "freed", for the whole retention window.
- Before a batch: "This will move 312 items (4.1 GB) to quarantine. Free space now: 11.2 GB. After: 7.1 GB. Nothing is permanently deleted."
- After a batch: the measured actual delta, plus any errors, plus what was skipped and why.
- Every number is tappable and drills into the exact set that produced it. **A number the user cannot expand into a file list is not allowed in the UI.**

### 6.6 Confirmation ladder

| Operation | Confirmation |
|---|---|
| Swipe left | Staged, not executed. Listed, reversible, undoable. |
| Apply staged deletes | One confirmation listing count, bytes, and a preview grid |
| Move to folder | Executed immediately (fully reversible), with undo |
| Empty quarantine | Second confirmation, typed count, explicit "cannot be undone" |
| Permanent delete | Two confirmations; the second requires stating what is being deleted; never reachable from a swipe |
| Acting inside protected roots | Unlock in Settings with typed confirmation + per-batch preview |
| Unlock "skip preview" for a category | Per-category, typed confirmation, unavailable for media-eligible categories |

### 6.7 Recovery surfaces

- **Undo strip** for the session (immediate).
- **History screen**: every batch ever, with date, count, bytes, and per-item **Restore**.
- **"Restore everything from this session"** panic button, prominent in the session summary.
- **Recovery Report** for any item that ended in an unexpected state (§6.2).
- Restores verify the hash and return files to the *original* path, recreating directories and MediaStore rows as needed; if the original path is occupied, a conflict-resolution screen appears rather than an overwrite.

---

## 7. Dashboard

### 7.1 Storage overview

- Volumes with real total/used/free from `StorageStatsManager`/`StatFs`.
- Category breakdown (Images, Video, Audio, Documents, APKs, App data, System, Other) — with honest labelling that "Other" is a residual and therefore **not bulk-actionable**.
- Trend over time from locally stored snapshots (opt-in, on-device only), so "what grew this week?" is answerable.
- Top 20 largest folders and the largest individual files, both tappable into a list and then into a deck.

### 7.2 Triage progress

- Reviewed / total, kept / deleted / moved per slot, current streak, session history.
- "X GB awaiting review" — computed from real decks, tappable to start that deck.

### 7.3 Quarantine card

- Item count, bytes held, oldest expiry countdown, **[Restore all]** **[Review]** **[Empty…]**.
- A clear statement that these bytes are not yet reclaimed.

### 7.4 Health checks

A checklist, each item tappable into its module, each reporting honest findings: large files, duplicate groups, similar-media groups, empty folders, APK installers, partial downloads, zero-byte files, plus Tier 2 items (thumbnail cache, orphaned app dirs). **Every item reports "0 items" honestly** rather than inventing findings to look useful.

### 7.5 What the dashboard must never do

No alarm-red gauges. No "boost score". No comparison to other users. No notification-driven nagging. No fabricated health score that improves when you delete things — a score that rewards deletion is a dark pattern with a body count.

---

## 8. Cleaner modules (v2) — feasibility first

| Module | Tier 0–1 (SAF + MediaStore) | Tier 2 (All files access) | Notes |
|---|---|---|---|
| **Large files** | ✅ Full | ✅ Full | Pure size filter over MediaStore + granted trees. Safest module. |
| **Exact duplicates** | ✅ Full | ✅ Full | Size pre-filter → partial hash → full hash. |
| **Similar media** | ✅ (MediaStore media) | ✅ Full | pHash/PDQ + time-window clustering. Never auto-select. |
| **Empty folders** | ⚠️ Granted trees only | ✅ Full | SAF enumeration is slow; needs resumable scan. |
| **APK installers** | ✅ MediaStore `Files` | ✅ Full | Compare against installed package versions. |
| **Partial downloads / zero-byte / temp** | ⚠️ Granted trees only | ✅ Full | Strict extension + path rules. |
| **Crash logs** | ❌ | ⚠️ Heuristic, review-only | Path patterns vary wildly; high false-positive risk. |
| **Thumbnail cache** | ❌ | ⚠️ Default OFF | Only the OS cache path; no API; fragile, high blast radius. |
| **Orphaned app dirs** | ❌ | ⚠️ Review-only | Requires All Files Access; deletion can break apps. Never bulk. |
| **Residual files** | ❌ | ⚠️ Review-only | Heuristic by nature. Never bulk, never default. |
| **App cache** | 📊 Measure + deep-link | 📊 Measure + deep-link | Cannot be cleared by us on modern Android. We say so. |
| **"Advertising cache" / "hidden cache"** | ❌ | ❌ | No reliable definition exists. We will not ship a fake category — this is exactly the "Visible Cache" trap. We map the user's *intent* to concrete, enumerable surrogates: browser download folders, app cache dirs we can actually see, and orphaned dirs — labelled truthfully. |

### 8.1 Duplicates

- **Exact:** group by size → group by head/tail hash → full content hash (xxHash/BLAKE3 for speed). Only size-identical candidates are hashed.
- **Keeper recommendation** is a *suggestion*, never a selection: prefer the copy in a protected root, then the oldest, then the shortest path, and the UI shows why.
- **Live photos, bursts and RAW+JPEG pairs get special handling** — never treated as duplicates of each other by default.
- Deleting duplicates = quarantine, with the "which copy" decision always visible and reversible.

### 8.2 Similar media

- Perceptual hash (pHash/dHash/PDQ) computed in the background with a bounded worker pool; Hamming-distance clustering.
- **Time-window gating is mandatory** so visually similar but unrelated images (two different white walls; two different memes on the same template) are not clustered. **[OPEN: window default — recommendation 60 s for burst-like groups, plus a separate low-confidence bucket for cross-window matches.]**
- Screenshots of the same app screen and shared meme templates are the classic false positives; the low-confidence bucket exists for them and is never bulk-actionable.
- **[VERIFY]** burst detection via `MediaStore` grouping where available.

### 8.3 Compression

- **Images:** re-encode (JPEG/WebP/AVIF) at a chosen quality or target size, optional downscale. **EXIF orientation and timestamps are preserved, or the operation is refused** — losing orientation is data corruption.
- **Video:** Media3 `Transformer` (preferred; no FFmpeg licensing, binary-size or Play-policy baggage) for transcode, resolution cap and bitrate control. **[VERIFY codec/format coverage and HEVC licensing.]** FFmpeg only if Transformer cannot meet requirements, and then with an explicit licence review.
- **Non-destructive by construction:** the output is always a **new file**. Verification (full decode, duration, frame count) must pass before the original is even *offered* for quarantine. Compression never mutates in place.
- Estimated savings are labelled as estimates; the real saving is measured afterwards.
- Long jobs run in `WorkManager` with a foreground notification, progress, cancellation and resume; cancellation leaves no partial output.

---

## 9. Technical architecture

### 9.1 Modules

```
:app                     Compose nav host, DI wiring, tier-specific manifest
:core:model              Domain types: MediaItem, DeckItem, Category, JournalEntry, Slot
:core:common             Result types, dispatchers, local-only logging, formatting
:core:database           Room: index cache, journal, decks, tags, snapshots
:core:storage            StorageGateway abstraction + SAF/MediaStore/Raw implementations
:core:media              Decoding, Media3 wrappers, thumbnail pipeline, hashing
:core:designsystem       Theme, tokens, shared components (incl. the slot dock)
:feature:deck            Deck engine, swipe surface, slot dock, staging strip
:feature:viewer          Full-fidelity viewer
:feature:dashboard       Overview, breakdown, trends, quarantine
:feature:cleaner         Large files, junk taxonomy, APKs, empty folders
:feature:duplicates      Exact + similar pipelines
:feature:compress        Image/video compression
:feature:settings        Grants, slots, protected roots, thresholds, journal export
```

Libraries (pin exact versions at implementation; verify latest stable): Kotlin 2.x, Jetpack Compose + Material 3, Hilt, Room, DataStore (Proto), Coil 3, Media3/ExoPlayer, WorkManager, Paging 3, kotlinx-coroutines/Flow, Turbine + Robolectric for tests, Baseline Profiles, R8. **[VERIFY]** current `compileSdk`/`targetSdk`, AGP version and the 16 KB page-size requirement.

### 9.2 The deck engine is pure Kotlin

The deck is a state machine with no Android dependencies: `DeckState(items, cursor, staging, history, deferred)`, transitions per gesture, and a reducible history. This is what makes it exhaustively unit-testable, including property-based tests over random gesture sequences asserting invariants (e.g. "no item is ever both staged and kept", "history is always invertible").

### 9.3 `StorageGateway`

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
    fun watch(source: SourceRef): Flow<ChangeEvent>   // drives incremental re-index
}
```

Implementations: `SafStorageGateway`, `MediaStoreStorageGateway`, `RawStorageGateway` (Tier 2). A `FakeStorageGateway` with fault injection backs the chaos tests in §12.

Protected-root enforcement and journal write-ahead live in a **decorator** around every implementation, so no gateway can be used without them.

### 9.4 Scanning and indexing

- `WorkManager` unique work, chunked and **cancellable between chunks**; every chunk commits to Room so an interrupted scan resumes rather than restarts.
- MediaStore cursor paging for media; SAF tree walks for granted non-media trees with explicit depth/entry caps and progress reporting.
- Hashing is **lazy** — computed on demand for duplicates/decks, stored, invalidated when size+mtime change.
- Perceptual hashes are computed in a low-priority, charging-and-idle-preferred worker, throttled with a hard CPU budget.
- **A scan never blocks the UI, and no full-storage walk ever runs during triage.** (Tier 0–1 has no full-storage walk at all.)

### 9.5 Media and memory

- `ImageDecoder` + explicit target size for all list decoding; `Bitmap.Config.HARDWARE` where possible; never hold full-resolution bitmaps in a list.
- At most one active video decoder plus one preloaded instance.
- Bounded Coil disk cache keyed by content hash, LRU, with automatic eviction when free space is low — eviction must never touch quarantine.
- Explicit memory ceiling with graceful degradation (drop preloading, then drop autoplay) rather than an OOM.

### 9.6 Background work

- Foreground service type `dataSync` for long scans/compression, declared and justified for Play. **[VERIFY current declaration requirements.]**
- **No background destructive work, ever.** Automation may only *prepare* (scan, hash, index) and *notify*.
- Battery: no wake locks beyond job scope, respect Doze/App Standby, no polling.

---
## 10. Data model (sketch)

```
MediaItem       id, uri, path, relativePath, mediaStoreId, mime, size, width, height,
                durationMs, dateAdded, dateModified, bucket, sourceId, hash?, phash?,
                protectedRoot: Boolean
SourceRef       id, treeUri, displayName, access (RO|RW), kind (SAF|MEDIASTORE|RAW),
                itemCount, totalBytes, grantedAt
Slot            id (1..5), name, icon, colour, destinationUri, mode (MOVE|TAG), sortOrder
DeckSession     id, queryJson, createdAt, cursor, status
JournalEntry    id, batchId, sessionId, itemId, opKind, fromUri, toUri, hash, size,
                state (PENDING|DONE|FAILED|ABORTED|RESTORED), createdAt, expiresAt, error
Tag             itemId, slotId, taggedAt
KeepMark        itemId, markedAt, sessionId
StorageSnapshot takenAt, volume, totalBytes, freeBytes, breakdownJson
ProtectedRoot   pattern, kind, unlockedAt?, unlockMethod
```

Journal retention: 30 days (configurable 7–90), after which entries are archived, not erased. Quarantine expiry never deletes without a notification plus a grace period. **[OPEN: default expiry behaviour — recommendation: notify, then require an explicit action; never silently purge.]**

---

## 11. Non-functional requirements

### 11.1 Performance budgets

| Metric | Budget |
|---|---|
| Cold start to deck interactive | ≤ 1.2 s on a mid-range 2020 device |
| Swipe-to-next-card latency | ≤ 16 ms frame time, sustained ≥ 60 fps |
| Deck card thumbnail ready | ≤ 100 ms cached, ≤ 400 ms first decode |
| MediaStore index of 50,000 items | ≤ 60 s, resumable |
| Scan memory ceiling | ≤ 150 MB RSS; no OOM at 100k items |
| Autoplay video start | ≤ 300 ms |

Measured with Macrobenchmark; jank and startup regressions are CI-gated.

### 11.2 Reliability

- Crash-free session rate ≥ 99.5%.
- Disk-full, permission-revoked, file-vanished and storage-unmounted are first-class, tested states.
- Never lose the journal: reconciliation covers process death at any point.

### 11.3 Privacy and security

- **No `INTERNET` permission**, in any build (a possible opt-in "check for updates" opens the browser — never an in-app network stack). **[OPEN]**
- No analytics, no remote crash reporting, no ad SDKs, no identifiers.
- Everything stays on device. Journal export is a local file the user chooses where to put it.
- Optional app lock (biometric) to protect quarantine and journal.

### 11.4 Accessibility and internationalisation

- Full TalkBack support: every gesture has an equivalent button and a custom accessibility action; gestures are never the only path.
- Touch targets ≥ 48 dp; drag-to-folder has a non-drag alternative.
- Colour is never the sole carrier of meaning; destructive actions carry icon + text.
- Respects font scale to 200%, reduced motion and high contrast; usable in landscape and on tablets/foldables.
- Strings externalised from day one; RTL-correct layout. **[OPEN: target locales.]**

---

## 12. Acceptance criteria — the "impossible" list

These are **testable requirements**, not aspirations. Each maps to an automated test. Release is blocked if any fails.

| # | Requirement | Test |
|---|---|---|
| AC-1 | No file operation occurs without a prior committed journal entry | Fault-injected gateway asserts journal write ordering |
| AC-2 | No destructive bulk operation executes without the user having seen the enumerated set (except per-category opt-in, never for media-eligible categories) | UI test per module |
| AC-3 | No user file is destroyed before a 30-day quarantine window with verified-hash restore | Quarantine/restore round-trip over every MIME type |
| AC-4 | A file's category is never derived from its parent folder name alone | Golden-file classifier tests over adversarial directory names |
| AC-5 | Process death at any point leaves no item in an unknown state; reconciliation resolves every `PENDING` record | Chaos test: force-stop at N random points per batch; assert no limbo |
| AC-6 | Zero network capability | CI manifest assertion: `INTERNET` absent; no network stack linked |
| AC-7 | Protected roots cannot be modified by any bulk flow; only an item the user decided one at a time in the deck is exempt (§6.3), and never from the quarantine directory, a granted root or permanent deletion | Per-module test attempting modification without unlock; `ReviewedExemptionScopeTest` pins who may claim the exemption |
| AC-8 | Operations act on a frozen snapshot; externally changed items abort with a visible message | Concurrent-modification test |
| AC-9 | Storage freed is measured, and quarantine bytes are reported as reclaimable, never as freed | Metric unit tests + UI string assertions |
| AC-10 | **CCleaner regression test:** a directory named `cache`, `Cache`, `.cache` or `temp` containing JPEG/PNG/MP4 files can never appear in any cache/junk/temp/thumbnail/residual category, and can never be deleted by a bulk action | Named regression test, run on every commit |
| AC-11 | No destructive user-facing surface applies the word "cache" to a heuristic set | Resource-string lint rule in CI |
| AC-12 | A category exceeding a sanity ceiling (total size, single-file size, or protected-root membership) cannot present a one-tap action | Threshold tests |
| AC-13 | Every number in the dashboard expands into the exact file list that produced it | UI test per dashboard metric |
| AC-14 | No automatic or scheduled destructive action exists in the codebase | Architecture test: destructive entry points are UI-reachable only |
| AC-15 | Cancelling any operation leaves the original file untouched | Cancellation tests for move, copy, compress, quarantine |
| AC-16 | Compression is non-destructive: the original is never modified, and outputs are verified before the original is offered for removal | Compression pipeline tests |
| AC-17 | Restore recreates original paths and MediaStore entries; conflicts never overwrite | Restore conflict tests |
| AC-18 | The journal is always exportable and complete | Export parity test |
| AC-19 | Every feature works, in a narrower form, without any restricted permission; the app never becomes unusable or shows a dead end when broad access is declined, and each narrowed panel names what it excludes and offers an in-context grant | Permission-denied matrix test across Tier 0/1/2 on API 26/30/33/36 |

---

## 13. Roadmap

| Milestone | Contents | Exit criteria |
|---|---|---|
| **M0 — Foundation** | Gradle multi-module skeleton, capability tiers, `-PnoAllFilesAccess` contingency flag, CI, design system, `StorageGateway` + decorator, journal + quarantine + restore + reconciliation, SAF onboarding ("pick your 5 folders") | AC-1, AC-3, AC-5 pass; a file can be quarantined, the app killed mid-op, and restored correctly |
| **M1 — The core loop** | Deck engine, swipe surface, five-slot dock, staging strip, undo history, full-fidelity viewer (image/GIF/video), session summary | A 500-item deck triages end-to-end at ≥ 60 fps with full undo; AC-8, AC-15 pass |
| **M2 — Dashboard & honesty** | Storage overview, measurements, trends, quarantine card, journal export, history screen | AC-9, AC-13, AC-18 pass |
| **M3 — Cleaner modules** | Large files, exact duplicates, similar media, APKs, empty folders, partial/temp/zero-byte | AC-2, AC-4, AC-7, AC-10, AC-12 pass |
| **M4 — Power features** | Junk taxonomy extensions, image/video compression, scheduled *reminders* | AC-16 passes; compression verified non-destructive |
| **M5 — Ship** | Accessibility audit, Baseline Profiles, macrobenchmark gates, Play listing, both policy declarations, positioned as file manager / media organiser (§4.2), privacy policy, F-Droid metadata | AC-6, AC-11, AC-19 pass; a11y audit clean |

Each milestone is usable on a real device. Nothing ships that regresses a prior AC.

---

## 14. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| SAF performance on large trees | Slow scans, frustration | MediaStore-first for media; SAF only for non-media grants; chunked resumable walks with honest progress; tested ceilings |
| Persisted-grant limits | Cannot hold enough folders | Cap and surface grants; evaluate limits early **[VERIFY]** |
| Cross-volume move cost for big videos | Slow "delete", space pressure | Prefer same-volume quarantine; pre-flight space check; offer MediaStore trash with an explicit trade-off |
| **All Files Access declaration rejected** | Tier 2 features unavailable on Play | File-manager positioning, MediaStore-only media access, 2000-URI delete batching, the concrete "substantially detrimental impact" argument (§4.2), and the `-PnoAllFilesAccess` contingency build. Tiers 0–1 keep the product fully usable |
| **Photo/Video declaration rejected or revoked** | Removal from Play; no whole-library decks | Justify via the policy's own "managing and maintaining all of users' photos/videos" example; keep Tier 0 (SAF + Photo Picker) genuinely complete so the app survives on picker-only access (§12 AC-19) |
| **Listing drifts toward "cleaner" marketing** | Suspension or developer-account termination for undeclared use | §4.2 condition 1 is a compliance requirement: the description must promote file management as core. Store-listing copy is reviewed against §1.3 anti-goals before every release |
| MediaStore index drift vs reality | Ghost entries, failed ops | Verify on presentation, reconcile after ops, surface failures |
| Similar-media false positives | Deleting distinct photos | Time-window clustering, low-confidence bucket, never bulk-actionable, keeper suggestions only |
| Compression quality loss / orientation bugs | Silent data corruption | Non-destructive output, orientation/timestamp preservation enforced, verification before offering removal |
| Scope creep from "cleaner" expectations | Endless feature list | §1.3 anti-goals; the answer to "advertising cache" is documenting why it does not exist |
| "Reclaimable" read as "freed" | Perceived dishonesty | Explicit wording, separate figures, quarantine card on the dashboard |
| Only the owner's device is tested | Blind spots | Emulator matrix across API 26/30/33/34/36 plus at least one low-end physical device |

---

## 15. Open questions for the product owner

1. **App name.** `SwipeDelete` is a working title describing only the destructive half. Alternatives: `Triage`, `Pile`, `Keep/Toss`, `MemeSweep`, `Deck`. Preference?
2. **Positioning.** §4.2 answers the file-manager question: one APK, one Play listing, positioned as a *file manager / media organiser*, with both the All Files Access and Photo/Video declarations, and the `-PnoAllFilesAccess` contingency build kept ready. Confirm you are comfortable with the listing being sold as a file manager rather than a cleaner.
3. **Staged vs immediate delete.** Recommendation is staged-by-default (§5.2). Do you want that, or instant quarantine per swipe with an undo toast?
4. **Slot default mode.** Move vs tag-only (§5.3). Tag-only never touches the file and is safest; move is what you described. Recommendation: move when a destination is set, with tag-only offered.
5. **Viewer vertical gesture.** Dismiss-on-swipe-down with a button for the dock (recommended), or drag-down-to-dock in the viewer too?
6. **Source grant cap**, and whether the five slots are also usable as *sources* (triage inside a destination).
7. **Quarantine location** — external files dir (same-volume speed) vs internal storage (more private, slower, counts against app storage). Recommendation: external files dir.
8. **Quarantine expiry** — never auto-purge, require an action (recommended), or purge after 30 days with a notification?
9. **Audio files** in scope for triage, or images/GIF/video only?
10. **Shizuku / Wireless ADB** support as an opt-in route to Tier 2 without the Settings toggle. Nice-to-have, adds complexity and a user-installed dependency.
11. **Locales** to support at launch.
12. **Update checking** — confirm zero network. Recommendation: no in-app updater.
13. **Minimum device** for performance work — what is the slowest phone you care about?
14. **Screenshots/screen recordings** — a smart deck, a slot, or both?

---

## 16. Glossary

- **Deck** — the ordered set of items currently being triaged.
- **Staging strip** — the visible, pending "to delete" pile produced by left swipes, not yet applied.
- **Slot / dock** — one of the five user-configured destination folders in the footer.
- **Quarantine** — app-managed holding area where deleted items live for the retention window.
- **Journal** — the write-ahead, immutable record of every operation the app has ever performed.
- **Protected root** — a path the app structurally refuses to modify in bulk.
- **Provenance** — the plain-language reason an item appears in a deck or category.
- **SAF** — Storage Access Framework; user-granted, revocable tree access.
- **All Files Access** — `MANAGE_EXTERNAL_STORAGE`; a special-access toggle, restricted by Play policy.

---

*End of draft v0.2. Changes in this revision: §2 attribution corrected (the 40 GB gallery-loss case traces to AVG Cleaner; CCleaner shows the identical failure at ~5 GB); §4 rewritten around verified Play policy text — the app is positioned as a file manager, a permitted use of All Files Access, while media access stays on MediaStore because "Media Files access" is an explicitly invalid use of that permission; capability tiers replace build flavours; the Photo and Video Permissions declaration added as a second, independent requirement; AC-19 added for the policy-mandated graceful degradation. Still to fold in: `docs/RESEARCH-PITFALLS.md` and `docs/ANDROID-CONSTRAINTS.md`, which resolve the remaining [VERIFY] markers.*
