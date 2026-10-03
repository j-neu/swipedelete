package com.swipedelete.feature.deck.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swipedelete.core.designsystem.components.SlotDock
import com.swipedelete.core.designsystem.components.SlotSpec
import com.swipedelete.core.model.SlotId
import com.swipedelete.core.model.SlotMode
import com.swipedelete.feature.deck.R
import com.swipedelete.feature.deck.domain.effectiveSlotMode
import kotlin.math.abs

/** The test tag the deck's dock surface is addressed by. */
const val DeckSlotDockTestTag: String = "deck_slot_dock"

/** The distance the dock lifts when it is revealed by a drag (SPEC 5.2). */
private val DockLift: Dp = 24.dp

/**
 * The deck-side view of a slot: its destination and whether that destination is
 * still a live grant. A revoked grant is [granted] `false` with a localised
 * [unavailableReason], so the dock greys it and shows the reason on tap instead
 * of dropping the file or dead-ending the user (SPEC 5.2, AC-19).
 */
@Immutable
data class DockSlot(
    val id: SlotId,
    val name: String,
    val destinationUri: String?,
    val granted: Boolean = true,
    val unavailableReason: String? = null,
) {
    /** Decision 4: MOVE when a destination is set, TAG when it is not. */
    val mode: SlotMode get() = effectiveSlotMode(destinationUri)
}

/**
 * The drag/dock arithmetic of SPEC 5.2, kept pure so the exact 12 dp begin
 * threshold and 48 dp magnetic snap are proven at their boundaries rather than
 * only through timing-sensitive injected gestures.
 */
object DockGeometry {
    /** A drag only starts revealing the dock after ~12 dp. */
    val DragBeginThreshold: Dp = 12.dp

    /** The nearest slot is grabbed (magnetic snap) within ~48 dp. */
    val MagneticSnapDistance: Dp = 48.dp

    fun dragBegan(distancePx: Float, beginThresholdPx: Float): Boolean =
        distancePx >= beginThresholdPx

    /**
     * The index of the slot nearest [pointerX] within [snapDistancePx], or null
     * when the pointer is not close enough to any slot (a drop there cancels).
     */
    fun snapIndex(pointerX: Float, slotCentresX: List<Float>, snapDistancePx: Float): Int? {
        if (slotCentresX.isEmpty()) return null
        var bestIndex = 0
        var bestDistance = abs(slotCentresX[0] - pointerX)
        for (index in 1 until slotCentresX.size) {
            val distance = abs(slotCentresX[index] - pointerX)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex.takeIf { bestDistance <= snapDistancePx }
    }

    /** Null (cancel, return the card) unless the drop landed inside the dock on a slot. */
    fun resolveDrop(snapIndex: Int?, droppedInsideDock: Boolean): Int? =
        snapIndex.takeIf { droppedInsideDock }
}

/**
 * The state of one drag-to-dock gesture: whether the dock has been revealed,
 * and which slot is currently magnetically snapped. Plain state, so the reveal
 * logic is unit-testable without the Compose pointer pipeline.
 */
@Stable
class DockDragState {
    var revealed by mutableStateOf(false)
        private set

    var highlighted by mutableStateOf<Int?>(null)
        private set

    private var travelledY: Float = 0f

    fun onDrag(
        travelledY: Float,
        pointerX: Float,
        beginThresholdPx: Float,
        slotCentresX: List<Float>,
        snapDistancePx: Float,
    ) {
        this.travelledY += travelledY
        if (!revealed && this.travelledY >= beginThresholdPx) revealed = true
        highlighted = if (revealed) {
            DockGeometry.snapIndex(pointerX, slotCentresX, snapDistancePx)
        } else {
            null
        }
    }

    /** Ends the drag: the snapped slot index, or null when the drop cancels. */
    fun onDrop(): Int? {
        val target = DockGeometry.resolveDrop(highlighted, droppedInsideDock = revealed)
        reset()
        return target
    }

    fun onCancel() = reset()

    private fun reset() {
        revealed = false
        highlighted = null
        travelledY = 0f
    }
}

@Composable
fun rememberDockDragState(): DockDragState = remember { DockDragState() }

/**
 * The five-slot dock (SPEC 5.2 / 5.3 / decision 4, M1-T06).
 *
 * It renders the shared [SlotDock] and adds the deck behaviour around it: the
 * dock animates up once a drag passes ~12 dp, the nearest slot snaps within
 * ~48 dp, and a drop outside the dock cancels and returns the card. A revoked
 * destination greys out and reveals its reason on tap.
 *
 * The component is presentation-only: it reports the chosen [DockSlot] and the
 * caller performs the move/tag through the gateway.
 */
@Composable
fun DeckSlotDock(
    slots: List<DockSlot>,
    onSlotSelected: (DockSlot) -> Unit,
    modifier: Modifier = Modifier,
    onUnavailable: (DockSlot) -> Unit = {},
    dragState: DockDragState = rememberDockDragState(),
) {
    val density = LocalDensity.current
    val beginThresholdPx = with(density) { DockGeometry.DragBeginThreshold.toPx() }
    val snapDistancePx = with(density) { DockGeometry.MagneticSnapDistance.toPx() }
    val dragHandleLabel = stringResource(R.string.dock_drag_handle)
    val raised by animateFloatAsState(
        targetValue = if (dragState.revealed) 1f else 0f,
        label = "dock_reveal",
    )

    BoxWithConstraints(
        modifier = modifier
            .testTag(DeckSlotDockTestTag)
            .semantics { contentDescription = dragHandleLabel },
    ) {
        val dockWidthPx = with(density) { maxWidth.toPx() }
        val slotCentresX = remember(dockWidthPx, slots.size) {
            if (slots.isEmpty()) {
                emptyList()
            } else {
                List(slots.size) { index -> (index + 0.5f) * dockWidthPx / slots.size }
            }
        }

        Box(
            modifier = Modifier.pointerInput(slots, slotCentresX) {
                detectDragGestures(
                    onDragStart = { dragState.onCancel() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragState.onDrag(
                            travelledY = dragAmount.y,
                            pointerX = change.position.x,
                            beginThresholdPx = beginThresholdPx,
                            slotCentresX = slotCentresX,
                            snapDistancePx = snapDistancePx,
                        )
                    },
                    onDragEnd = {
                        val index = dragState.onDrop()
                        if (index != null) onSlotSelected(slots[index])
                    },
                    onDragCancel = { dragState.onCancel() },
                )
            },
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - raised) * DockLift.toPx()
                },
            ) {
                SlotDock(
                    slots = slots.map { slot ->
                        SlotSpec(
                            name = slot.name,
                            enabled = slot.granted,
                            unavailableReason = slot.unavailableReason,
                        )
                    },
                    onSlotMove = { spec ->
                        slots.firstOrNull { it.name == spec.name }?.let(onSlotSelected)
                    },
                    onSlotUnavailable = { spec ->
                        slots.firstOrNull { it.name == spec.name }?.let(onUnavailable)
                    },
                )
            }
        }
    }
}
