package com.swipedelete.core.model

enum class SlotMode { MOVE, TAG }
data class Slot(val id: SlotId, val name: String, val icon: String, val colour: Long,
                val destinationUri: String?, val mode: SlotMode, val sortOrder: Int)
