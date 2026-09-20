package com.swipedelete.feature.settings.onboarding

import com.swipedelete.core.database.entity.SlotEntity
import com.swipedelete.core.model.Slot
import com.swipedelete.core.model.SlotId

/** The stored slot row, as the UI and the view model render it. */
internal fun SlotEntity.toSlot(): Slot = Slot(
    id = SlotId(id),
    name = name,
    icon = icon,
    colour = colour,
    destinationUri = destinationUri,
    mode = mode,
    sortOrder = sortOrder,
)
