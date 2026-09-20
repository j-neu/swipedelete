package com.swipedelete.core.common

import android.util.Log

/**
 * Local-only logging.
 *
 * Every entry point writes to logcat through [android.util.Log] and nothing
 * else: no network calls, no file writes and no remote crash reporting
 * (spec §11.3). [LOCAL_ONLY_LOGGING] is a compile-time marker the M5-T02 lint
 * rule asserts on so a remote sink cannot be added quietly.
 */
object LocalLog {
    const val LOCAL_ONLY_LOGGING: Boolean = true

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }
}
