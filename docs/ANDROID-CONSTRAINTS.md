# Android Constraints — verified toolchain and SDK targets (V1)

**Task:** V1 — Toolchain, SDK targets and 16 KB page size.
**Date checked:** 2026-09-19 (all rows).
**Scope:** documentation only. This note fixes the exact values Task 10 (M0-T01) writes into `gradle/libs.versions.toml`. No Gradle, Kotlin or manifest file is created or modified by this task.
**Method:** every value was read from a primary source (official release notes, the artifact's own `maven-metadata.xml`, or the vendor's version endpoint) on 2026-09-19. Local observations are marked as such and were taken with `sdkmanager --list` and directory listings, not inferred.

---

## 1. Required table

| claim | value | source URL | date checked |
|---|---|---|---|
| Play `targetSdk` requirement | **36 minimum.** New apps and all app updates must target Android 16 (API level 36) or higher to be submitted to Google Play, in force since 2026-08-31; an extension to 2026-11-01 can be requested. Existing apps must target API 35+ to remain available to new users. | https://support.google.com/googleplay/android-developer/answer/11926878 and https://developer.android.com/google/play/requirements/target-sdk | 2026-09-19 |
| Current release `compileSdk` | **37** — Android 17, API level 37, codename `CINNAMON_BUN`. Set `compileSdk = 37`. | https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels (table: Android 17 = 37) and https://developer.android.com/about/versions/17 | 2026-09-19 |
| Recent-stable `compileSdk` | **36** — Android 16, API level 36, codename `BAKLAVA`; the prior stable release and the highest platform installed on this machine. It is the conservative non-current pin. | https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels and https://developer.android.com/tools/releases/platforms | 2026-09-19 |
| AGP (`com.android.tools.build:gradle`) | **9.4.1** (newest stable; `9.5.0-alpha06` is the newest overall). AGP 9.4 supports a maximum API level of 37, requires Gradle ≥ 9.6.0, SDK Build Tools ≥ 36.0.0, JDK ≥ 17, KGP default 2.2.10. | https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml and https://developer.android.com/build/releases/gradle-plugin | 2026-09-19 |
| Kotlin (`org.jetbrains.kotlin.android`) | **2.4.20** (current stable; published 2026-09-07). | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml and https://github.com/JetBrains/kotlin/releases/tag/v2.4.20 | 2026-09-19 |
| Gradle (wrapper) | **9.7.1** (current stable, released 2026-08-19). | https://services.gradle.org/versions/current and https://docs.gradle.org/9.7.1/release-notes.html | 2026-09-19 |
| Compose BOM (`androidx.compose:compose-bom`) | **2026.09.00** (newest stable; no prerelease in the BOM line). | https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml and https://developer.android.com/develop/ui/compose/bom/bom-mapping | 2026-09-19 |
| Hilt (`com.google.dagger:hilt-android`) | **2.60.1** (newest stable; `hilt-android-compiler` same version; published 2026-07-06). | https://repo1.maven.org/maven2/com/google/dagger/hilt-android/maven-metadata.xml and https://github.com/google/dagger/releases | 2026-09-19 |
| Room (`androidx.room:room-runtime` / `room-compiler` / `room-ktx`) | **2.8.5** (newest stable, 2026-09-09 release-note block). | https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml and https://developer.android.com/jetpack/androidx/releases/room | 2026-09-19 |
| Media3 / ExoPlayer (`androidx.media3:media3-exoplayer`; same for `-ui`, `-session`, `-transformer`, `-common`) | **1.11.1** (newest stable; published 2026-09-11). | https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/maven-metadata.xml and https://github.com/androidx/media/releases | 2026-09-19 |
| Coil (`io.coil-kt.coil3:*`) | **3.6.3** (newest stable; published 2026-09-18). | https://repo1.maven.org/maven2/io/coil-kt/coil3/coil/maven-metadata.xml and https://github.com/coil-kt/coil/releases | 2026-09-19 |
| WorkManager (`androidx.work:work-runtime-ktx`) | **2.11.2** (newest non-prerelease; `2.12.0-rc01` is the newest overall). | https://dl.google.com/dl/android/maven2/androidx/work/work-runtime-ktx/maven-metadata.xml and https://developer.android.com/jetpack/androidx/releases/work | 2026-09-19 |
| Paging 3 (`androidx.paging:paging-runtime` / `paging-compose`) | **3.5.1** (newest stable). | https://dl.google.com/dl/android/maven2/androidx/paging/paging-runtime/maven-metadata.xml and https://developer.android.com/jetpack/androidx/releases/paging | 2026-09-19 |
| 16 KB page-size requirement | **Currently required.** "all apps targeting Android 15 (API level 35) and higher must support 16 KB memory page sizes on 64-bit devices on Google Play." The current page also states: "Starting February 1, 2027, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates." Apps that are pure Java/Kotlin with no native libraries are already compliant. | https://developer.android.com/guide/practices/page-sizes (page last updated 2026-09-16), original announcement https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html | 2026-09-19 |
| SDK platform availability on this machine | `android-33`, `android-34`, `android-35`, `android-36` installed only — **no `android-37`**. `sdkmanager` offers `platforms;android-37.0` (rev 2, stable), plus `37.1`, `37.2`. Build-tools installed: `34.0.0`, `35.0.0`, `35.0.1`, `36.0.0`. `cmdline-tools/latest` present; `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset. `gradle` is not on PATH and no wrapper exists. | Local observation: `"$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root=<sdk> --list` (SDK root `C:\Users\jakob\AppData\Local\Android\Sdk`); available list backed by https://dl.google.com/android/repository/repository2-3.xml | 2026-09-19 |

### What the plan's Global Constraint says, and whether it holds

Global Constraint 1 (`docs/tasks.md`) is `minSdk 26`, `targetSdk 36`, `compileSdk 37`.

- `targetSdk 36` — **confirmed correct**: it exactly meets the current Play requirement (36 or higher, in force since 2026-08-31).
- `compileSdk 37` — **confirmed correct as the current release** (Android 17 = API 37), **but not currently buildable on this machine**: `platforms;android-37.0` is not installed. See Gap G1. AGP 9.4's maximum supported API level is 37, so no AGP upgrade is needed for it.
- `minSdk 26` — not in scope of V1 (no Play floor applies to `minSdk`); no change.

---

## 2. Supporting versions needed by the Task 10 catalog

These are not rows required by the brief, but Task 10 cannot write a working catalog without them.

| item | value | source URL | date checked |
|---|---|---|---|
| Compose Compiler Gradle plugin (`org.jetbrains.kotlin.plugin.compose`) | **2.4.20** — the docs state its version "matches your Kotlin version". | https://developer.android.com/develop/ui/compose/compiler | 2026-09-19 |
| KSP (`com.google.devtools.ksp`) | **2.3.12** (newest release, published 2026-09-09). Versioning is no longer tied to the Kotlin compiler as of KSP 2.3.0, but the documented supported KGP range is **2.2.10–2.3.x** (see Gap G5). | https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-api/maven-metadata.xml and https://kotlinlang.org/docs/ksp-overview.html | 2026-09-19 |
| Compose library versions inside BOM 2026.09.00 | `androidx.compose.ui:ui` = **1.12.1**, `androidx.compose.material3:material3` = **1.4.0** (resolved from the BOM's own POM). | https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom | 2026-09-19 |
| SDK Build Tools | **36.0.0** (already installed; AGP 9.4 minimum and default is 36.0.0 — no download needed). | https://developer.android.com/build/releases/gradle-plugin and local `sdkmanager --list` | 2026-09-19 |
| JDK for the build | AGP 9.4 requires **JDK ≥ 17** (default 17). The machine has `JAVA_HOME=C:\Program Files\Java\jdk-21` (reports 21.0.7), which is sufficient. See G6 for the PATH discrepancy. | https://developer.android.com/build/releases/gradle-plugin | 2026-09-19 |

---

## 3. Confidence notes (rows whose source is indirect)

- **Play `targetSdk` requirement** — Direct. Two independent official pages agree (Play Console Help and developer.android.com). No confidence caveat.
- **Current release `compileSdk` (37)** — Direct, with one caveat. The official API-levels table lists Android 17 = API level 37, `sdkmanager` offers `platforms;android-37.0`, and `sdkmanager`'s Android 17 listing includes stable (non-beta) `37.0`, `37.1`, `37.2` packages alongside explicit `-beta1/2/3` packages. However, the "SDK Platform release notes" page (`/tools/releases/platforms`) still stops at Android 16 (API 36) and the Android 17 release-notes page's newest dated entry is Beta 4.1 (2026-06-01). The weighted evidence says API 37 is released; this row is rated **high, not absolute**.
- **Recent-stable `compileSdk` (36)** — Direct. Same API-levels table and platform release notes.
- **AGP 9.4.1** — Direct for the version (Maven metadata `release` is `9.5.0-alpha06`; `9.4.1` is the newest non-prerelease). Compatibility numbers come from the AGP 9.4 release-notes page (titled 9.4.0, covering the 9.4 line).
- **Kotlin 2.4.20** — Direct (Maven metadata `release=2.4.20`, GitHub non-prerelease release).
- **Gradle 9.7.1** — Direct (`services.gradle.org/versions/current`, `final=true`).
- **Compose BOM 2026.09.00** — Direct for the BOM version. The `ui=1.12.1` / `material3=1.4.0` mapping is **indirect** (read from the BOM's POM rather than a release note). The BOM's own `ui` line has newer `1.13.0-alpha03`, but the BOM intentionally pins the stable `1.12.1`.
- **Hilt 2.60.1** — Direct (Maven metadata `release=2.60.1`, non-prerelease GitHub release).
- **Room 2.8.5** — Direct for the version; the exact release date is **indirect** (the androidx release page groups patches under a "Version 2.8" block dated 2026-09-09; Maven `lastUpdated` is `20260909`).
- **Media3 1.11.1** — Direct (Maven metadata `release=1.11.1`; GitHub tag `1.11.1`, published 2026-09-11). The androidx release page's newest dated block is "Version 1.11.0 — September 10, 2026", consistent with a 1.11.1 patch.
- **Coil 3.6.3** — Direct (Maven metadata `release=3.6.3`; GitHub non-prerelease release 2026-09-18).
- **WorkManager 2.11.2** — Direct for the version; the exact patch date is **indirect** (the release page's newest non-rc block "Version 2.12 — August 12, 2026" covers the line that contains 2.11.2; `2.12.0-rc01` is a prerelease).
- **Paging 3.5.1** — Direct for the version; the patch date is **indirect** (release page block "Version 3.5 — August 12, 2026").
- **16 KB page-size requirement** — Direct for the requirement text (developer.android.com `/guide/practices/page-sizes`, last updated 2026-09-16, plus the 2025 blog that announced the original 2025-11-01 date). **Caveat:** the enforcement date in the current doc (2027-02-01) differs from the originally announced 2025-11-01; treat 2027-02-01 as the current documented date.
- **Dependency native-code status (16 KB)** — Direct evidence from the artifacts themselves: `media3-exoplayer`, `-common`, `-decoder`, `-extractor`, `-datasource`, `-database`, `-container`, `-ui`, `-session`, `-transformer`, `-effect` (1.11.1); `room-runtime` / `room-runtime-android` / `room-common` (2.8.5); `coil-android` / `coil-core-android` / `coil-compose-android` / `coil-compose-core-android` (3.6.3); `hilt-android` (2.60.1); `work-runtime` (2.11.2); `paging-runtime` (3.5.1); `compose ui-graphics-android` (1.12.1) — **none contain any `lib/` or `jni/` `.so`**. Room's default Android path uses `androidx.sqlite:sqlite-framework` (framework SQLite, no native library). The one planned-stack exception is the *opt-in* bundled SQLite driver `androidx.sqlite:sqlite-bundled-android`, which does ship `jni/*/libsqliteJni.so`; its arm64-v8a `PT_LOAD` segments are 16 KB-aligned (verified on `sqlite-bundled-android:2.7.1`). This driver is not in the plan.

---

## 4. Gaps (downloads, installs, or product-owner decisions)

**G1 — `platforms;android-37.0` is not installed (blocks `compileSdk = 37`).**
If the plan keeps `compileSdk 37`, install the exact package (note: the identifier is `37.0`, **not** `android-37`):

```powershell
& "C:\Users\jakob\AppData\Local\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" `
  --sdk_root="C:\Users\jakob\AppData\Local\Android\Sdk" "platforms;android-37.0"
```

Decision needed: keep `compileSdk 37` (requires the download above, matches the plan and the current release) **or** amend Global Constraint 1 to `compileSdk 36` (no download; compiles against the installed `android-36`, and is sufficient because Play only mandates `targetSdk 36`, not `compileSdk`). No AGP change is needed either way (AGP 9.4 supports up to API 37).

**G2 — `ANDROID_HOME` / `ANDROID_SDK_ROOT` are unset.**
AGP will not locate the SDK. Task 10 must either write `local.properties` with `sdk.dir=C\:\\Users\\jakob\\AppData\\Local\\Android\\Sdk` (already in the planned `.gitignore`) or export `ANDROID_HOME`. Decision needed: which of the two the project standardises on.

**G3 — Gradle is not on PATH and no wrapper exists (Task 10 bootstrap).**
`./gradlew` cannot be run until a wrapper exists. Concrete options: install Gradle 9.7.1 and run `gradle wrapper --gradle-version 9.7.1`, or download `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip` and generate the wrapper, or let Android Studio generate it. Decision needed: bootstrap route.

**G4 — Toolchain combination is at the forward-compatibility edge (decision).**
Kotlin's own KGP compatibility table (https://kotlinlang.org/docs/gradle-configure-project.html) states for **Kotlin 2.4.20**: Gradle **7.6.3–9.7.0**, AGP **8.5.2–9.3.1** ("fully supported"; newer versions are allowed but may produce deprecation warnings or partial feature support).

- Current stable **AGP 9.4.1** is *above* Kotlin 2.4.20's fully-supported AGP max (9.3.1). AGP 9.3.3 exists and supports API 37 with Gradle ≥ 9.5.0.
- Current stable **Gradle 9.7.1** is one patch above Kotlin 2.4.20's fully-supported max (9.7.0).

Decision needed: (a) accept the forward-compat pair AGP 9.4.1 + Gradle 9.7.1 + Kotlin 2.4.20 (matches "newest stable" for each), or (b) pin the fully-supported set **AGP 9.3.3 + Gradle 9.7.0 + Kotlin 2.4.20** (all three inside the documented ranges, still supports `compileSdk 37`).

**G5 — KSP 2.3.12 is not documented as supporting Kotlin 2.4.20 (decision).**
KSP's requirements page states: "The latest KSP version, 2.3.10, supports … Kotlin Gradle plugin (KGP) 2.2.10–2.3.x" (https://kotlinlang.org/docs/ksp-overview.html). No KSP release in the Maven metadata declares Kotlin 2.4.x; the newest KSP (2.3.12) publishes against `kotlin-stdlib 2.3.20`. KSP is required for the plan's KSP-based Room and Hilt processing.

Decision needed (one of):
1. Verify KSP 2.3.12 empirically with Kotlin 2.4.20 during Task 10 (KSP 2.3.10's notes mention a fix "so KSP works with Kotlin 2.4.0 default module names", so it may work);
2. Pin **Kotlin 2.3.21** (latest 2.3.x, published 2026-04-23) so KSP is inside its documented range — but note Kotlin 2.3.20–2.3.21's fully-supported AGP max is 9.0.0 and Gradle max 9.3.0, which then forces AGP/Gradle down as well; or
3. Keep Kotlin 2.4.20 and use **KAPT** instead of KSP for Room/Hilt, accepting slower annotation processing.

**G6 — JDK discrepancy (record, minor).**
`JAVA_HOME` = `C:\Program Files\Java\jdk-21` and `"$env:JAVA_HOME\bin\java.exe" -version` reports **21.0.7**. The `java` resolved first on `PATH` is `C:\Program Files\Common Files\Oracle\Java\javapath\java.exe`, which reports **24.0.1**, and `C:\Program Files\Java\jdk-24\bin\java.exe` is also on `PATH`. `JHAA_HOME` is **not set** (the brief named it; only `JAVA_HOME` exists). This does not block the build (Gradle uses `JAVA_HOME`/toolchain, and AGP 9.4 needs JDK ≥ 17), but the controller's note that "`java -version` reports JDK 21" was not reproduced. No action required; recorded so it is not mis-diagnosed later.

**G7 — 16 KB page size: no action for the planned dependency set.**
None of the planned dependencies ship native code (verified per-artifact, see Confidence note). `targetSdk 36` (≥ Android 15) brings the app in scope of the Play requirement, but a pure-Java/Kotlin build is compliant by construction. No emulator/device 16 KB test is required by this task. Open item only if the team later adopts `androidx.sqlite:sqlite-bundled` (native `libsqliteJni.so`; verified 16 KB-aligned in 2.7.1) or adds NDK/FFmpeg code (V7 will decide Media3 `Transformer` coverage/FFmpeg).

---

## 5. Recommended catalog pin (subject to the G1/G4/G5 decisions)

```
gradle      = 9.7.1          # G4: 9.7.0 is Kotlin 2.4.20's fully-supported max
agp         = 9.4.1          # G4: 9.3.3 is the fully-supported alternative
kotlin      = 2.4.20         # G5: KSP compatibility must be confirmed
composeBom  = 2026.09.00
hilt        = 2.60.1
room        = 2.8.5
media3      = 1.11.1
coil        = 3.6.3
work        = 2.11.2
paging      = 3.5.1
ksp         = 2.3.12         # G5
compileSdk  = 37             # G1
targetSdk   = 36             # meets Play requirement
minSdk      = 26
```

No value in this note is a range or "latest"; each is an exact string read from the cited source on 2026-09-19.
