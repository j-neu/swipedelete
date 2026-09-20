package com.swipedelete.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic colour tokens (SPEC 11.4): components read meaning from here, never
 * from a raw palette. `destructive` is a *reinforcement* token — the components
 * that use it always pair it with an icon and text, because colour is never the
 * sole carrier of meaning.
 */
@Immutable
data class SwipeDeleteColors(
    val destructive: Color,
    val onDestructive: Color,
    val destructiveContainer: Color,
    val onDestructiveContainer: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val accent: Color,
    val onAccent: Color,
    val outline: Color,
)

val SwipeDeleteLightColors = SwipeDeleteColors(
    destructive = Color(0xFFB3261E),
    onDestructive = Color(0xFFFFFFFF),
    destructiveContainer = Color(0xFFF7DAD6),
    onDestructiveContainer = Color(0xFF5A1410),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    accent = Color(0xFF6750A4),
    onAccent = Color(0xFFFFFFFF),
    outline = Color(0xFF79747E),
)

val SwipeDeleteDarkColors = SwipeDeleteColors(
    destructive = Color(0xFFF2B8B5),
    onDestructive = Color(0xFF601410),
    destructiveContainer = Color(0xFF7A1A16),
    onDestructiveContainer = Color(0xFFF7DAD6),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    accent = Color(0xFFD0BCFF),
    onAccent = Color(0xFF381E72),
    outline = Color(0xFF938F99),
)

/**
 * Layout tokens. [MinTouchTarget] encodes the SPEC 11.4 floor of 48 dp; every
 * interactive component in this module applies it.
 */
object SwipeDeleteDimens {
    val MinTouchTarget: Dp = 48.dp
    val SpacingSmall: Dp = 8.dp
    val SpacingMedium: Dp = 16.dp
}

val LocalSwipeDeleteColors = staticCompositionLocalOf { SwipeDeleteLightColors }
