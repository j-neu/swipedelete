package com.swipedelete.core.model

enum class OpKind { QUARANTINE, RESTORE, MOVE, DELETE_PERMANENT, COMPRESS }
enum class JournalState { PENDING, DONE, FAILED, ABORTED, RESTORED }
data class JournalEntry(
    val id: String, val batchId: BatchId, val sessionId: SessionId?, val itemId: ItemId,
    val opKind: OpKind, val fromUri: String, val toUri: String?, val hash: String?,
    val sizeBytes: Long, val state: JournalState, val createdAt: Long,
    val expiresAt: Long, val error: String? = null,
)
