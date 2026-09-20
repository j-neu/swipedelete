package com.swipedelete.core.model

enum class ProtectedRootKind { PATH, BUCKET, AGE, INTERNAL, SAF_ROOT }
data class ProtectedRoot(
    val pattern: String, val kind: ProtectedRootKind,
    val unlockedAt: Long? = null, val unlockMethod: String? = null,
)
