package com.swipedelete.core.designsystem

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme. Dark mode is resolved in Kotlin through
 * `isSystemInDarkTheme()`, which is the Compose-idiomatic equivalent of a
 * `values-night/` resource overlay: there are no XML colours to override, so no
 * `values-night/` directory exists (see the task report).
 */
@Composable
fun SwipeDeleteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) SwipeDeleteDarkColors else SwipeDeleteLightColors
    val materialColors = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            error = colors.destructive,
            onError = colors.onDestructive,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.outline,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            error = colors.destructive,
            onError = colors.onDestructive,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceVariant,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.outline,
        )
    }

    CompositionLocalProvider(LocalSwipeDeleteColors provides colors) {
        MaterialTheme(colorScheme = materialColors, content = content)
    }
}

/** Token accessors, mirroring the `MaterialTheme` object/function pair. */
object SwipeDeleteTheme {
    val colors: SwipeDeleteColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSwipeDeleteColors.current

    val dimens: SwipeDeleteDimens
        @Composable
        @ReadOnlyComposable
        get() = SwipeDeleteDimens
}

/**
 * SPEC 11.4: animation must respect the system's reduced-motion setting.
 * `ANIMATOR_DURATION_SCALE == 0` is the platform's "animations disabled" signal.
 * Returns `false` when the setting cannot be read.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
            ) == 0f
        }.getOrDefault(false)
    }
}
