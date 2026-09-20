package com.swipedelete.core.storage

import com.swipedelete.core.model.MediaItem

/**
 * How a [ProtectedRoot] pattern is matched against a [MediaItem].
 *
 * Patterns are matched on normalised POSIX path *segments* plus the MediaStore
 * bucket, never on the raw path string, so `/PicturesofYou/` can never match the
 * `Pictures/` pattern.
 */
enum class ProtectedRootKind {
    /** A path pattern; matched against the item's path / relativePath segments. */
    PATH,

    /**
     * A camera-roll bucket name, or a folder of the same name: matches
     * [MediaItem.bucket] (trimmed, case-insensitive) **or** any path segment.
     */
    BUCKET,

    /** Any path under the app's own quarantine directory (`quarantineRoot`). */
    INTERNAL,

    /** Media whose `dateModified` is older than [DefaultProtectedRoots.AGE_THRESHOLD_MILLIS]. */
    AGE,

    /** The SAF tree root the user granted (see the `isRoot` predicate). */
    SAF_ROOT,
}

/** A single "never touch" rule: a [pattern] label and the [kind] of match it performs. */
data class ProtectedRoot(val pattern: String, val kind: ProtectedRootKind)

/**
 * The frozen §6.3 "never touch" default set.
 *
 * The [pattern] strings double as the identifiers a user unlocks in Settings
 * (`unlocked: () -> Set<String>` in [ProtectedRootsStorageGateway]) and as the
 * `cause` reported on a refusal.
 */
object DefaultProtectedRoots {

    /** Five-year threshold for the [ProtectedRootKind.AGE] rule. */
    const val AGE_THRESHOLD_MILLIS: Long = 5L * 365 * 24 * 60 * 60 * 1000

    /** Pattern label used as the refusal cause for the [ProtectedRootKind.AGE] rule. */
    const val AGE_PATTERN: String = "media older than 5 years"

    /** Pattern label used as the refusal cause for a granted SAF root. */
    const val SAF_ROOT_PATTERN: String = "SAF root"

    /**
     * Voice/call recorder patterns.
     *
     * §6.3 names "voice recorders, call recorders" but supplies no exact
     * patterns, so this short, editable list stands in: the MediaStore buckets
     * (and matching folder names) of the common recorder apps. [BUCKET] matching
     * means a folder of the same name is protected as well as the bucket.
     */
    val VOICE_AND_CALL_RECORDER_PATTERNS: List<String> = listOf(
        "Voice Recorder",
        "Call Recorder",
        "Call Recording",
        "Call Recordings",
        "Sound Recorder",
        "Audio Recorder",
        "Recorder",
    )

    fun patterns(): List<ProtectedRoot> = buildList {
        listOf(
            "DCIM/",
            "DCIM/Camera",
            "Pictures/",
            "Movies/",
            "Music/",
            "Documents/",
            "Download/",
        ).forEach { add(ProtectedRoot(it, ProtectedRootKind.PATH)) }

        listOf(
            "Camera",
            "Screenshots",
            "Screen recordings",
            "WhatsApp Images",
            "WhatsApp Video",
            "Telegram",
            "Signal",
            "Snapseed",
            "Instagram",
            "Snapchat",
            "Pinterest",
            "Reddit",
        ).forEach { add(ProtectedRoot(it, ProtectedRootKind.BUCKET)) }

        VOICE_AND_CALL_RECORDER_PATTERNS.forEach { add(ProtectedRoot(it, ProtectedRootKind.BUCKET)) }

        add(ProtectedRoot(AGE_PATTERN, ProtectedRootKind.AGE))
        add(ProtectedRoot(SAF_ROOT_PATTERN, ProtectedRootKind.SAF_ROOT))
    }
}

/**
 * Segment-based, case-insensitive, POSIX-normalised path matching.
 *
 * A single-segment pattern matches only a whole segment equal to it (so
 * `Pictures` does not match `PicturesofYou`). A multi-segment pattern matches a
 * **contiguous** segment subsequence anywhere in the path (so `DCIM/Camera`
 * matches `/storage/emulated/0/DCIM/Camera/sub/deep.jpg` but not
 * `/storage/emulated/0/DCIM/x/Camera/`).
 */
internal object PathPatternMatcher {

    fun pathMatches(path: String, pattern: String): Boolean =
        segmentsMatch(normalise(path), normalise(pattern))

    /** True when [path] is [root] itself or sits underneath it. */
    fun isUnder(path: String, root: String): Boolean {
        val candidate = normalise(path)
        val prefix = normalise(root)
        return prefix.isNotEmpty() &&
            candidate.size >= prefix.size &&
            prefix.indices.all { candidate[it] == prefix[it] }
    }

    fun segmentsMatch(path: List<String>, pattern: List<String>): Boolean {
        if (pattern.isEmpty()) return false
        if (pattern.size == 1) return path.any { it == pattern[0] }
        if (pattern.size > path.size) return false
        for (start in 0..path.size - pattern.size) {
            var matched = true
            for (offset in pattern.indices) {
                if (path[start + offset] != pattern[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    /** `\` → `/`, split on `/`, drop empties and `.`, lower-case every segment. */
    fun normalise(value: String): List<String> =
        value.replace('\\', '/')
            .split('/')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "." }
}
