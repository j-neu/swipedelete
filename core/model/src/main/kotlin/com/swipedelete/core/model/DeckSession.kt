package com.swipedelete.core.model

enum class DeckStatus { ACTIVE, COMPLETED, ABANDONED }
data class DeckSession(val id: SessionId, val queryJson: String, val createdAt: Long,
                       val cursor: Int, val status: DeckStatus)
