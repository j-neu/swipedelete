package com.swipedelete.core.model

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
