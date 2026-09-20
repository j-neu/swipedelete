package com.swipedelete.core.storage

import com.swipedelete.core.model.SourceRef

/**
 * A target for [StorageGateway.move]. [relativePath] is the destination
 * directory inside [source] (null means the source root); the moved item keeps
 * its filename unless that name is taken, in which case it is disambiguated.
 */
data class Destination(val source: SourceRef, val relativePath: String? = null)
