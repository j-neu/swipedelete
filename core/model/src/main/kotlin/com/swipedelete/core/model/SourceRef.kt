package com.swipedelete.core.model

enum class SourceKind { SAF, MEDIASTORE, RAW }
data class SourceRef(
    val id: SourceId, val treeUri: String, val displayName: String,
    val access: Access, val kind: SourceKind,
    val itemCount: Long?, val totalBytes: Long?, val grantedAt: Long,
) { enum class Access { RO, RW } }
