package com.swipedelete.core.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The module's own icon set. Icons are defined here rather than pulled from
 * `material-icons-core` so the design system's destructive semantics do not
 * depend on a transitive icon artifact. All are 24 dp viewport vectors, tinted
 * by the caller.
 */
object SwipeDeleteIcons {

    /** Trash can: the icon half of every destructive affordance. */
    val Destructive: ImageVector by lazy {
        ImageVector.Builder(
            name = "SwipeDelete.Destructive",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 3f)
                lineTo(15f, 3f)
                lineTo(15f, 5f)
                lineTo(9f, 5f)
                close()
                moveTo(4f, 6f)
                lineTo(20f, 6f)
                lineTo(20f, 8f)
                lineTo(4f, 8f)
                close()
                moveTo(6f, 9f)
                lineTo(18f, 9f)
                lineTo(17f, 21f)
                lineTo(7f, 21f)
                close()
            }
        }.build()
    }

    /** An empty frame, used by the empty-state component (decorative by default). */
    val Empty: ImageVector by lazy {
        ImageVector.Builder(
            name = "SwipeDelete.Empty",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(4f, 20f)
                close()
                moveTo(7f, 7f)
                lineTo(7f, 17f)
                lineTo(17f, 17f)
                lineTo(17f, 7f)
                close()
            }
        }.build()
    }

    /** Right arrow: the non-drag "move to slot" affordance. */
    val Move: ImageVector by lazy {
        ImageVector.Builder(
            name = "SwipeDelete.Move",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 11f)
                lineTo(14f, 11f)
                lineTo(14f, 7f)
                lineTo(20f, 12f)
                lineTo(14f, 17f)
                lineTo(14f, 13f)
                lineTo(4f, 13f)
                close()
            }
        }.build()
    }
}
