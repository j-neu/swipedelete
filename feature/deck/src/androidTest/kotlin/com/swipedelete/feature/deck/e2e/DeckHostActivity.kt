package com.swipedelete.feature.deck.e2e

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/**
 * A bare host for the end-to-end deck tests. The test sets [content] before it
 * launches the activity; nothing here knows about the deck.
 *
 * It keeps the screen on and shows over the lock screen so a run on a physical
 * phone does not depend on the device staying awake.
 */
class DeckHostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContent { content() }
    }

    companion object {
        @Volatile
        var content: @Composable () -> Unit = {}
    }
}
